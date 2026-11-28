package dev.phoneagent.app.adapters.screen

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayGeometryTest {
    private val phone = DisplayGeometry(1280, 2856)

    @Test
    fun theWholeDisplaySpansTheWholeGrid() {
        assertEquals(Bounds(0, 0, 999, 999), phone.normalize(phone.pixels))
    }

    @Test
    fun edgesAreInclusiveOnTheGrid() {
        // Pixels 0..639 are the left half: normalized 0..499, never 500.
        assertEquals(Bounds(0, 0, 499, 499), phone.normalize(Rect(0, 0, 640, 1428)))
        assertEquals(Bounds(500, 500, 999, 999), phone.normalize(Rect(640, 1428, 1280, 2856)))
    }

    @Test
    fun clipsToTheDisplayAndDropsWhatIsOutside() {
        assertEquals(Bounds(0, 0, 499, 0), phone.normalize(Rect(-100, -50, 640, 1)))
        assertEquals(Rect(1200, 2800, 1280, 2856), phone.clip(Rect(1200, 2800, 1500, 3000)))
        assertNull(phone.normalize(Rect(0, 2856, 1280, 3000)))
        assertNull(phone.normalize(Rect(10, 10, 10, 50)))
    }

    @Test
    fun pointsMapToTheCenterOfTheirCell() {
        assertEquals(PixelPoint(0.5f * 1280 / 1000, 0.5f * 2856 / 1000), phone.toPixel(Target.Point(0, 0)))
        val last = phone.toPixel(Target.Point(999, 999))
        assertEquals(1279.36f, last.x, 0.01f)
        assertEquals(2854.57f, last.y, 0.01f)
    }

    @Test
    fun aPointLandsInsideItsOwnCell() {
        for (value in listOf(0, 1, 333, 500, 998, 999)) {
            val pixel = phone.toPixel(Target.Point(value, value))
            assertEquals(value, (pixel.x * 1000 / 1280).toInt())
            assertEquals(value, (pixel.y * 1000 / 2856).toInt())
        }
    }

    @Test
    fun rejectsAnEmptyDisplay() {
        assertThrows(IllegalArgumentException::class.java) { DisplayGeometry(0, 100) }
    }
}

@RunWith(AndroidJUnit4::class)
class FrameEncoderTest {
    @Test
    fun scalesTheLongerEdgeDownToTheLimit() {
        assertEquals(Size(574, 1280), FrameEncoder.scaledSize(1280, 2856, 1280))
        assertEquals(Size(1280, 574), FrameEncoder.scaledSize(2856, 1280, 1280))
    }

    @Test
    fun neverScalesUp() {
        assertEquals(Size(640, 480), FrameEncoder.scaledSize(640, 480, 1280))
    }

    @Test
    fun keepsAtLeastOnePixel() {
        assertEquals(Size(1, 100), FrameEncoder.scaledSize(1, 5000, 100))
    }

    @Test
    fun rejectsANonPositiveLimit() {
        assertThrows(IllegalArgumentException::class.java) { FrameEncoder.scaledSize(10, 10, 0) }
    }

    @Test
    fun encodesAScaledJpegAndLeavesTheSourceToTheCaller() {
        val source = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)

        val frame = FrameEncoder.encode(source, maxEdge = 200)

        assertEquals("image/jpeg", frame.mime)
        assertEquals(100 to 200, frame.width to frame.height)
        assertEquals(false, frame.som)
        assertEquals(false, source.isRecycled)
        assertEquals(true, frame.bytes.isNotEmpty())
    }
}
