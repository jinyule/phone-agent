package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ScreenCoordinates
import dev.phoneagent.core.ports.Target

/**
 * Maps between display pixels and the normalized 0–999 grid (`ScreenCoordinates`). Pixel rects are
 * half-open (`right`/`bottom` exclusive, as in [Rect]); normalized [Bounds] are inclusive, so a
 * full-width element spans 0–999.
 */
internal class DisplayGeometry(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Display size must be positive, got ${width}x$height" }
    }

    /** The whole display as a pixel rect. */
    val pixels: Rect get() = Rect(0, 0, width, height)

    /** [rect] clipped to the display and normalized, or `null` when nothing of it is on the display. */
    fun normalize(rect: Rect): Bounds? {
        val clipped = clip(rect) ?: return null
        return Bounds(
            left = axis(clipped.left, width),
            top = axis(clipped.top, height),
            right = axis(clipped.right - 1, width),
            bottom = axis(clipped.bottom - 1, height),
        )
    }

    /** [rect] intersected with the display, or `null` when empty. */
    fun clip(rect: Rect): Rect? {
        val clipped = Rect(rect)
        return if (clipped.intersect(0, 0, width, height) && !clipped.isEmpty) clipped else null
    }

    /** The pixel at the center of the normalized cell [point]. */
    fun toPixel(point: Target.Point): PixelPoint =
        PixelPoint(
            x = (point.x + HALF) * width / CELLS,
            y = (point.y + HALF) * height / CELLS,
        )

    private fun axis(
        px: Int,
        size: Int,
    ): Int = (px.toLong() * CELLS.toLong() / size).toInt().coerceIn(0, ScreenCoordinates.MAX)

    private companion object {
        const val CELLS = ScreenCoordinates.MAX + 1
        const val HALF = 0.5f
    }
}
