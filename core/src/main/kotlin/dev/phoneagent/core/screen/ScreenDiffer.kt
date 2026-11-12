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
 * Precondition: both snapshots come from the same [dev.phoneagent.core.ports.ScreenPort], so an
 * equal ref is the same element (the port keeps the ref of an element it can tell is the same and
 * gives one it cannot tell a new ref). Elements are paired only by ref, never by equal names or
 * reading order.
 *
 * - A ref printed in both snapshots is unchanged when its line reads the same (text, absorbed
 *   texts, value, hint, states and, for actionable elements, the printed center), its parent is
 *   the same and, for a line without coordinates, its center moved by at most
 *   [DiffConfig.moveTolerance]; otherwise it is *changed* (`~`).
 * - A ref printed only in [after] is *added* (`+`), also when the element was there before but
 *   not printed (absorbed, hidden): the model has not seen that ref.
 * - A ref printed only in [before] is *removed* (`-`), whatever happened to the element: gone,
 *   hidden, absorbed into a row, given a new ref, or its ref now names an element that is not
 *   printed. So a ref that changes is one element removed and one added.
 * - OCR lines are compared as printed (masked text and center), only when [after] was read with
 *   OCR: lines of [after] not in [before] are added, lines of [before] not in [after] removed; a
 *   moved line is in both. When [after] has no OCR, the lines of [before] are left unverified
 *   ([ScreenDiff.ocrUnverified]), not removed.
 *
 * So an element in no list still shows the same line under the same ref, and every ref the model
 * holds is either still as it saw it, listed with its new line, or listed as void.
 */
public class ScreenDiffer internal constructor(
    private val config: DiffConfig = DiffConfig(),
    private val format: ScreenFormatConfig = ScreenFormatConfig(),
) {
    private val lines = ElementLines(format, SnapshotDetail.FULL)

    /**
     * What changed from [before] to [after], based on [before]'s id; added and changed in
     * [after]'s order, removed in [before]'s.
     */
    public fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff {
        val was = printed(before)
        val now = printed(after)
        val added = mutableListOf<UiElement>()
        val changed = mutableListOf<UiElement>()
        for ((ref, line) in now) {
            val old = was[ref]
            if (old == null) {
                added += line.element
            } else if (differs(old, line)) {
                changed += line.element
            }
        }
        val removed = was.keys.filter { it !in now }
        val unverified = !before.ocr.isNullOrEmpty()
        val ocr = after.ocr ?: return ScreenDiff(before.id, added, removed, changed, ocrUnverified = unverified)
        val ocrBefore = ocrLines(before.ocr.orEmpty())
        val ocrAfter = ocrLines(ocr)
        return ScreenDiff(
            base = before.id,
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

    /** Whether the element under one ref reads differently, has another parent, or moved unseen. */
    private fun differs(
        was: Printed,
        now: Printed,
    ): Boolean = was.text != now.text || was.element.parent != now.element.parent || movedUnseen(was, now)

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

    /** [ocr] lines as printed (masked text and center), with the line itself. */
    private fun ocrLines(ocr: List<OcrLine>): List<Pair<String, OcrLine>> =
        ocr.map { line ->
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
