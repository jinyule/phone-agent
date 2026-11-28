package dev.phoneagent.app.adapters.screen

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screenshot taken but not yet handed over belongs to [screenshotWithin]: it frees it when the
 * wait ends without handing it over, and only then (fix list r2 S1, Codex R2-B5).
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotWithinTest {
    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    /** Releases counted by screenshot, so a double release shows. */
    private val released = ArrayList<Screenshot>()
    private val counting: (Screenshot) -> Unit = { shot ->
        released += shot
        shot.release()
    }

    @Test
    fun aShotTakenAsTheWaiterIsCancelledIsFreedOnce() =
        runTest {
            val bitmap = bitmap()
            val shot = Screenshot.Captured(bitmap)
            lateinit var waiter: Job
            waiter =
                launch {
                    screenshotWithin(1_000, counting) {
                        // The shot is in hand; the task is cancelled before it is handed over.
                        waiter.cancel()
                        shot
                    }
                }

            waiter.join()

            assertTrue(waiter.isCancelled)
            assertTrue("freed", bitmap.isRecycled)
            assertEquals("freed once", listOf<Screenshot>(shot), released)
        }

    @Test
    fun aShotHandedOverIsNotFreed() =
        runTest {
            val shot = Screenshot.Captured(bitmap())

            assertSame(shot, screenshotWithin(1_000, counting) { shot })

            assertFalse(shot.bitmap.isRecycled)
            assertTrue(released.isEmpty())
        }
}
