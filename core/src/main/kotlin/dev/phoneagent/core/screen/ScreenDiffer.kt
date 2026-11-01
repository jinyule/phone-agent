package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.UiElement
import kotlin.math.abs

/**
 * Compares two snapshots of the same surface as the model reads them: line by line, the way
 * [ScreenFormatter] prints them ([PageLayout] with the same [format], whole texts as at `full`).
 * Pure.
 *
 * - A printed element of [after] is unchanged when [before] printed the same line under the same
 *   ref (so ref, text, absorbed texts, value, hint, states and, for actionable elements, the
 *   printed center are all the same) and, for an element whose line has no coordinates, its center
 *   moved by at most [DiffConfig.moveTolerance]. Any other printed element is *changed* (`~`) when
 *   it pairs with a printed element of [before] by identity, else *added* (`+`).
 * - *Removed* (`-`) are the refs [before] printed that [after] does not print at all, whatever
 *   happened to the element: gone, hidden, absorbed into a row, or its ref given to an element
 *   that is not printed. A ref printed again is not removed: its new line is listed instead.
 * - OCR lines are compared as printed (masked text and center): lines of [after] not in [before]
 *   are added, lines of [before] not in [after] removed; a moved line is in both.
 *
 * Identity is window, role, resource id and name, plus the hint of an editable field (so a field
 * keeps its identity while its text changes). Within one identity, elements with the same ref are
 * paired first and the rest in reading order, so deleting the first of three equal buttons pairs
 * the other two with themselves.
 *
 * So an element in no list still shows the same line under the same ref; a surface that renumbers
 * refs between snapshots makes most lines change, and the formatter then sends the full page.
 */
public class ScreenDiffer(
    private val config: DiffConfig = DiffConfig(),
    private val format: ScreenFormatConfig = ScreenFormatConfig(),
) {
    private val lines = ElementLines(format, SnapshotDetail.FULL)

    /** What changed from [before] to [after]; added and changed in [after]'s order, removed in [before]'s. */
    public fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff {
        val was = printed(before)
        val now = printed(after)
        val paired = pairedRefs(was.values.map { it.element }, now.values.map { it.element })
        val added = mutableListOf<UiElement>()
        val changed = mutableListOf<UiElement>()
        for ((ref, line) in now) {
            val old = was[ref]
            val same = old != null && old.text == line.text && !movedUnseen(old, line)
            when {
                same -> Unit
                ref in paired -> changed += line.element
                else -> added += line.element
            }
        }
        val removed = was.keys.filter { it !in now }
        val ocrBefore = ocrLines(before)
        val ocrAfter = ocrLines(after)
        return ScreenDiff(
            added = added,
            removed = removed,
            changed = changed,
            ocrAdded = unmatched(ocrAfter, ocrBefore),
            ocrRemoved = unmatched(ocrBefore, ocrAfter),
        )
    }

    /** Printed elements of [snapshot] by ref, in reading order, with their line. */
    private fun printed(snapshot: ScreenSnapshot): Map<ElementRef, Printed> {
        val layout = PageLayout(snapshot, format)
        val out = LinkedHashMap<ElementRef, Printed>()
        for (line in layout.blocks.flatMap { it.lines }) {
            val element = line.element
            val text =
                if (line.header) {
                    lines.list(element, added = false)
                } else {
                    lines.element(element, layout.absorbed[element.ref].orEmpty(), added = false, risky = false)
                }
            out[element.ref] = Printed(element, text, line.header || !element.isActionable())
        }
        return out
    }

    /** Whether an element whose line shows no coordinates moved beyond the tolerance. */
    private fun movedUnseen(
        was: Printed,
        now: Printed,
    ): Boolean {
        if (!now.placeUnseen) return false
        val old = was.element.bounds.center()
        val new = now.element.bounds.center()
        return abs(old.x - new.x) > config.moveTolerance || abs(old.y - new.y) > config.moveTolerance
    }

    /**
     * Refs of [now] that pair with an element of [was] by identity: same ref first, then the rest
     * of each identity in reading order.
     */
    private fun pairedRefs(
        was: List<UiElement>,
        now: List<UiElement>,
    ): Set<ElementRef> {
        val paired = HashSet<ElementRef>()
        val wasByIdentity = was.groupBy(::identity)
        for ((key, group) in now.groupBy(::identity)) {
            val candidates = wasByIdentity[key].orEmpty()
            val sameRef = group.filter { element -> candidates.any { it.ref == element.ref } }
            paired += sameRef.map { it.ref }
            val leftNow = group - sameRef.toSet()
            val leftWas = candidates.filter { candidate -> sameRef.none { it.ref == candidate.ref } }
            paired += leftNow.take(leftWas.size).map { it.ref }
        }
        return paired
    }

    private fun identity(element: UiElement): List<Any?> =
        listOf(
            element.window,
            element.role,
            element.resourceId,
            element.name,
            if (element.isEditable()) element.hint else null,
        )

    /** OCR lines of [snapshot] as printed (masked text and center), with the line itself. */
    private fun ocrLines(snapshot: ScreenSnapshot): List<Pair<String, OcrLine>> =
        snapshot.ocr.orEmpty().map { line ->
            val center = line.bounds.center()
            "${lines.clean(line.text)}|${center.x},${center.y}" to line
        }

    /** Lines of [from] without a counterpart in [other] (counted, so repeated lines pair one to one). */
    private fun unmatched(
        from: List<Pair<String, OcrLine>>,
        other: List<Pair<String, OcrLine>>,
    ): List<OcrLine> {
        val left = other.groupingBy { it.first }.eachCount().toMutableMap()
        return from
            .filter { (key, _) ->
                val count = left[key] ?: 0
                if (count > 0) left[key] = count - 1
                count == 0
            }.map { it.second }
    }

    /** A printed element, its line, and whether the line leaves its place out (no coordinates). */
    private class Printed(
        val element: UiElement,
        val text: String,
        val placeUnseen: Boolean,
    )
}

/**
 * Tuning of [ScreenDiffer]; validated on construction.
 *
 * @property moveTolerance largest center shift (normalized units, 0–999) that still counts as "not
 *   moved" for an element whose line shows no coordinates; printed coordinates count any shift.
 */
public data class DiffConfig(
    val moveTolerance: Int = 10,
) {
    init {
        require(moveTolerance in 0..MAX_TOLERANCE) { "moveTolerance must be in 0..$MAX_TOLERANCE, got $moveTolerance" }
    }

    private companion object {
        const val MAX_TOLERANCE = 999
    }
}
