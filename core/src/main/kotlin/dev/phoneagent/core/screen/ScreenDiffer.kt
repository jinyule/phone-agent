package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import kotlin.math.abs

/**
 * Compares two snapshots of the same surface by element identity, not by ref (refs may be
 * renumbered between snapshots). Pure.
 *
 * Identity is window, role, resource id and name; for editable fields also the hint, so a field
 * keeps its identity while its text changes; a renamed element is removed and added. Elements
 * with equal identity are paired in reading order. A paired element is *changed* (`~`) when its
 * value, hint, states or secret flag differ, when its ref differs (the model holds the old ref),
 * or when its center moved by more than [DiffConfig.moveTolerance] on either axis (the model may
 * hold its old coordinates). Unpaired elements are *added* (`+`) or *removed* (`-`).
 *
 * So "not in the diff" means the element is still there with the same ref, text and place; a
 * surface that renumbers refs between snapshots makes most elements changed, and the formatter
 * then sends the full page.
 */
public class ScreenDiffer(
    private val config: DiffConfig = DiffConfig(),
) {
    /** What changed from [before] to [after]; added and changed in [after]'s order, removed in [before]'s. */
    public fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff {
        val unpaired = before.elements.groupByTo(LinkedHashMap()) { identity(it) }.mapValues { ArrayDeque(it.value) }
        val added = mutableListOf<UiElement>()
        val changed = mutableListOf<UiElement>()
        for (now in after.elements) {
            val was = unpaired[identity(now)]?.removeFirstOrNull()
            when {
                was == null -> added += now
                differs(was, now) -> changed += now
                else -> Unit
            }
        }
        val left = unpaired.values.flatten().toSet()
        val removed = before.elements.filter { it in left }.map { it.ref }
        return ScreenDiff(added = added, removed = removed, changed = changed)
    }

    private fun identity(element: UiElement): List<Any?> =
        listOf(
            element.window,
            element.role,
            element.resourceId,
            element.name,
            if (element.isEditable()) element.hint else null,
        )

    private fun differs(
        was: UiElement,
        now: UiElement,
    ): Boolean {
        val oldCenter = was.bounds.center()
        val newCenter = now.bounds.center()
        val moved =
            abs(oldCenter.x - newCenter.x) > config.moveTolerance ||
                abs(oldCenter.y - newCenter.y) > config.moveTolerance
        return moved ||
            was.ref != now.ref ||
            was.value != now.value ||
            was.hint != now.hint ||
            was.states != now.states ||
            was.secret != now.secret
    }
}

/**
 * Tuning of [ScreenDiffer]; validated on construction.
 *
 * @property moveTolerance largest center shift (normalized units, 0–999) that still counts as "not moved".
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
