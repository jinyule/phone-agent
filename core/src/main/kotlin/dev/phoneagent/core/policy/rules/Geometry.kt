package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.Bounds

/** Box arithmetic in normalized coordinates (0–999, edges inclusive). */
internal object Geometry {
    /** [start] followed by the pieces on its row to its right, in left-to-right order. */
    fun rowText(
        start: Piece,
        pieces: List<Piece>,
    ): String =
        (
            listOf(start.text) +
                pieces
                    .filter { it !== start && it.bounds.left >= start.bounds.left && sameRow(it.bounds, start.bounds) }
                    .sortedBy { it.bounds.left }
                    .map { it.text }
        ).joinToString("")

    fun contains(
        bounds: Bounds,
        x: Int,
        y: Int,
    ): Boolean = x in bounds.left..bounds.right && y in bounds.top..bounds.bottom

    fun inside(
        child: Bounds,
        parent: Bounds,
    ): Boolean =
        child.left >= parent.left && child.top >= parent.top && child.right <= parent.right &&
            child.bottom <= parent.bottom

    /** Each box's vertical center lies within the other box. */
    fun sameRow(
        a: Bounds,
        b: Bounds,
    ): Boolean = centerY(a) in b.top..b.bottom && centerY(b) in a.top..a.bottom

    private fun centerY(b: Bounds): Int = (b.top + b.bottom) / 2

    fun area(b: Bounds): Long = (b.right - b.left + 1).toLong() * (b.bottom - b.top + 1)
}
