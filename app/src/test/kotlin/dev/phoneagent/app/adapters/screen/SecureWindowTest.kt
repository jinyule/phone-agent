package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ScreenFlag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FLAG_SECURE is found by capturing the top application window alone before a frame, and the
 * window's snapshots say so afterwards (fix list r1 S1).
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {
    private val host = FakeHost()
    private var displayShots = 0

    private fun bitmap(): Bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)

    init {
        host.show(button("Pay", Rect(0, 1000, 500, 1100)))
        host.takeShot = {
            displayShots++
            Screenshot.Captured(bitmap())
        }
    }

    @Test
    fun aSecureTopWindowGivesNoFrameAndItsSnapshotsSaySecure() =
        runTest {
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)

            assertNull(port.frame())

            assertEquals("the top application window is the one captured", listOf(1), host.windowShots)
            assertEquals("no frame of a secure window is taken", 0, displayShots)
            assertTrue(ScreenFlag.SECURE in port.snapshot().flags)
        }

    @Test
    fun aWindowThatIsCapturedLaterIsNoLongerSecure() =
        runTest {
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)
            port.frame()
            val probe = bitmap()
            host.takeWindowShot = { Screenshot.Captured(probe) }

            assertNotNull(port.frame())

            assertTrue("the window's own capture is freed", probe.isRecycled)
            assertFalse(ScreenFlag.SECURE in port.snapshot().flags)
        }

    @Test
    fun aWindowThatDroppedFlagSecureIsNoLongerSecureForAConsumerThatSkipsFramesOfSecureScreens() =
        runTest {
            // Fix list r2 S5 (Codex R2-S3): WP06 takes a frame only when the snapshot is not SECURE.
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)
            val observe =
                suspend {
                    val snapshot = port.snapshot()
                    snapshot.flags to if (ScreenFlag.SECURE in snapshot.flags) null else port.frame()
                }
            assertNull("found secure at the first frame", observe().second)
            assertTrue(ScreenFlag.SECURE in observe().first)

            host.takeWindowShot = { Screenshot.Captured(bitmap()) }
            val (flags, frame) = observe()

            assertFalse("SECURE is gone once the window can be captured", ScreenFlag.SECURE in flags)
            assertNotNull(frame)
        }

    @Test
    fun snapshotsCaptureOnlyWindowsMarkedSecure() =
        runTest {
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)

            port.snapshot()
            assertEquals("an unmarked window is not captured by a snapshot", emptyList<Int>(), host.windowShots)
            port.frame()
            port.snapshot()

            assertEquals("the frame's probe, then the snapshot's", listOf(1, 1), host.windowShots)
        }

    @Test
    fun aProbeThatTellsNothingKeepsWhatWasFound() =
        runTest {
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)
            port.frame()
            host.takeWindowShot = {
                Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
            }

            assertNull(port.frame())

            assertEquals(0, displayShots)
            assertTrue(ScreenFlag.SECURE in port.snapshot().flags)
        }

    @Test
    fun anotherWindowInFrontIsNotTakenForTheSecureOne() =
        runTest {
            host.takeWindowShot = { Screenshot.Secure }
            val port = mainPort(host)
            port.frame()
            val next = node("android.widget.FrameLayout", GEOMETRY.pixels).with(button("Done", Rect(0, 0, 500, 100)))
            host.windows = mapOf(0 to listOf(window(next, id = 2, layer = 2)) + host.windows.getValue(0))

            assertFalse(ScreenFlag.SECURE in port.snapshot().flags)
        }

    @Test
    fun beforeApi34NothingIsDetected() =
        runTest {
            // The host has no window capture before API 34 (FakeHost's default).
            val port = mainPort(host)

            assertNotNull(port.frame())

            assertEquals(1, displayShots)
            assertFalse(ScreenFlag.SECURE in port.snapshot().flags)
        }
}
