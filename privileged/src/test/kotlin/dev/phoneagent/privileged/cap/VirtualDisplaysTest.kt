package dev.phoneagent.privileged.cap

import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class VirtualDisplaysTest {
    /** Display ids whose release throws, the way a dead system server makes it throw. */
    private val failing = mutableSetOf<Int>()
    private val readers = mutableListOf<ImageReader>()
    private val displays =
        VirtualDisplays(FakeBridge(ApplicationProvider.getApplicationContext()), SDK) { display: VirtualDisplay ->
            val id = display.display.displayId
            check(id !in failing) { "releasing display $id failed" }
            display.release()
        }

    @After
    fun closeReaders() {
        readers.forEach(ImageReader::close)
    }

    private fun newDisplay(): Int {
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2).also { readers += it }
        return displays.create(reader.surface, WIDTH, HEIGHT, DPI).also { assertTrue("display created: $it", it >= 0) }
    }

    @Test
    fun releaseAllGoesOnPastADisplayThatFailsAndKeepsItForTheNextCall() {
        val first = newDisplay()
        val second = newDisplay()
        val third = newDisplay()
        failing += second
        assertEquals("the failure does not stop the others", 2, displays.releaseAll())
        assertEquals("none is usable", emptySet<Int>(), displays.ids())
        assertEquals("the failed one waits for cleanup", setOf(second), displays.pendingCleanup)
        assertFalse(displays.owns(first) || displays.owns(second) || displays.owns(third))

        failing.clear()
        assertEquals(1, displays.releaseAll())
        assertEquals(emptySet<Int>(), displays.pendingCleanup)
    }

    @Test
    fun aSingleReleaseThatFailsKeepsTheDisplayForReleaseAllButNotForUse() {
        val id = newDisplay()
        failing += id
        displays.release(id)
        assertFalse("the app asked for its release; it is no longer usable", displays.owns(id))
        assertEquals(null, displays.sizeOf(id))
        assertEquals("releaseAll retries it", setOf(id), displays.pendingCleanup)

        failing.clear()
        assertEquals(1, displays.releaseAll())
        assertEquals(emptySet<Int>(), displays.pendingCleanup)
    }

    @Test
    fun theDisplayIsCreatedWithoutRotatingWithItsContent() {
        val id = newDisplay()
        val created =
            ShadowLog
                .getLogsForTag(
                    "PhoneAgentHelper",
                ).map { it.msg }
                .single { it.startsWith("display $id:") }
        val flags = created.substringAfter("flags 0x").toInt(HEX)
        assertEquals("ROTATES_WITH_CONTENT is never set: $created", 0, flags and ROTATES_WITH_CONTENT)
    }

    private companion object {
        const val HEX = 16

        /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`. */
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val SDK = 36
        const val WIDTH = 1_080
        const val HEIGHT = 2_340
        const val DPI = 440
    }
}
