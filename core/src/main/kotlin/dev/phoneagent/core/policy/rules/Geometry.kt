package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.Bounds

/**
 * Box arithmetic in normalized coordinates (0–999, edges inclusive).
 *
 * [rightOf] and [sameRow] are adapted from Mika's `IrreversibleDetector` (Copyright 2026
 * wangzan101, Apache-2.0). Modified by PhoneAgent contributors: rows are matched by the vertical
 * centers of both boxes, and the start piece itself is left out. See NOTICE.
 */
internal object Geometry {
    /** The texts of the other [pieces] on [start]'s row that begin at or right of it, left to right. */
    fun rightOf(
        start: Piece,
        pieces: List<Piece>,
    ): String =
        pieces
            .filter { it !== start && it.bounds.left >= start.bounds.left && sameRow(it.bounds, start.bounds) }
            .sortedBy { it.bounds.left }
            .joinToString(" ") { it.text }

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

    /** The point a tap by ref lands on (the target's center, rounded down). */
    fun center(b: Bounds): Aim.Point = Aim.Point((b.left + b.right) / 2, centerY(b))

    private fun centerY(b: Bounds): Int = (b.top + b.bottom) / 2

    fun area(b: Bounds): Long = (b.right - b.left + 1).toLong() * (b.bottom - b.top + 1)
}
