package dev.phoneagent.app.adapters.screen

import android.graphics.Bitmap
import android.util.Size
import androidx.core.graphics.scale
import dev.phoneagent.core.ports.Frame
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Scales a screenshot so its longer edge fits a limit and encodes it as JPEG (phase1 §3.3 `frame`). */
internal object FrameEncoder {
    /** JPEG quality: text stays legible for the model at a fraction of PNG's size. */
    const val JPEG_QUALITY: Int = 80

    /** MIME type of every frame. */
    const val MIME: String = "image/jpeg"

    /** Size of a [width]×[height] image scaled down so that its longer edge is at most [maxEdge]; never scaled up. */
    fun scaledSize(
        width: Int,
        height: Int,
        maxEdge: Int,
    ): Size {
        require(width > 0 && height > 0 && maxEdge > 0) { "Invalid scale ${width}x$height to $maxEdge" }
        val longer = max(width, height)
        if (longer <= maxEdge) return Size(width, height)
        val scale = maxEdge.toDouble() / longer
        return Size(
            (width * scale).roundToInt().coerceIn(1, maxEdge),
            (height * scale).roundToInt().coerceIn(1, maxEdge),
        )
    }

    /** Encodes [bitmap] scaled to [maxEdge]. Does not recycle [bitmap]. */
    fun encode(
        bitmap: Bitmap,
        maxEdge: Int,
    ): Frame {
        val size = scaledSize(bitmap.width, bitmap.height, maxEdge)
        val scaled =
            if (size.width == bitmap.width && size.height == bitmap.height) {
                bitmap
            } else {
                bitmap.scale(size.width, size.height)
            }
        try {
            val out = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encoding failed" }
            return Frame(out.toByteArray(), MIME, size.width, size.height, som = false)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
