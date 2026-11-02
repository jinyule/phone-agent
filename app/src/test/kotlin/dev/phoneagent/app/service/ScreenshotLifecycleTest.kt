package dev.phoneagent.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.RemoteException
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.adapters.screen.FakeHost
import dev.phoneagent.app.adapters.screen.Screenshot
import dev.phoneagent.app.adapters.screen.mainPort
import dev.phoneagent.core.ports.ScreenUnavailableException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** An accessibility service that keeps every screenshot request instead of answering it. */
class CapturingService : AccessibilityService() {
    /** Requests in order: the executor and the callback the platform would answer through. */
    val requests = ArrayList<Pair<Executor, TakeScreenshotCallback>>()

    /** Thrown by the next request, as the platform throws when the connection is gone. */
    var failure: RuntimeException? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun takeScreenshot(
        displayId: Int,
        executor: Executor,
        callback: TakeScreenshotCallback,
    ) {
        failure?.let { throw it }
        requests += executor to callback
    }
}

/**
 * Screenshots end, and give their buffer back, whatever happens around them: the host closing,
 * a late answer, cancellation, no answer, a dead connection (fix list r1 B9).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenshotLifecycleTest {
    private val service = Robolectric.buildService(CapturingService::class.java).create().get()

    /** Bitmaps the host made from screenshots, in order. */
    private val made = ArrayList<Bitmap>()

    // Robolectric cannot wrap hardware buffers; the copy is a plain bitmap, the buffer is closed as on a device.
    private val host =
        ServiceScreenHost(service) { result ->
            result.hardwareBuffer.close()
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { made += it }
        }

    private fun buffer(): HardwareBuffer =
        HardwareBuffer.create(4, 4, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_CPU_READ_OFTEN)

    private fun success(buffer: HardwareBuffer): AccessibilityService.ScreenshotResult =
        ReflectionHelpers.callConstructor(
            AccessibilityService.ScreenshotResult::class.java,
            ClassParameter.from(HardwareBuffer::class.java, buffer),
            ClassParameter.from(ColorSpace::class.java, ColorSpace.get(ColorSpace.Named.SRGB)),
            ClassParameter.from(Long::class.javaPrimitiveType, 0L),
        )

    /** Answers the only request with [buffer] the way the platform does, and waits until the answer ran. */
    private fun answer(buffer: HardwareBuffer) {
        val (executor, callback) = service.requests.single()
        val ran = CountDownLatch(1)
        executor.execute {
            try {
                callback.onSuccess(success(buffer))
            } finally {
                ran.countDown()
            }
        }
        assertTrue("the answer ran", ran.await(ANSWER_WAIT_S, TimeUnit.SECONDS))
    }

    @Test
    fun closingTheHostEndsAScreenshotInFlightAndALateAnswerStillFreesItsBuffer() =
        runTest {
            val shot = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { host.screenshot(0) }

            host.close()
            runCurrent()

            assertTrue("the wait ended with the host", shot.isCompleted)
            assertEquals(
                Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR),
                shot.getCompleted(),
            )
            val late = buffer()
            answer(late)
            assertTrue(late.isClosed)
            assertTrue("the late bitmap is recycled", made.single().isRecycled)
        }

    @Test
    fun anAnswerAfterTheWaitWasCancelledFreesItsBuffer() =
        runTest {
            val shot = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { host.screenshot(0) }
            shot.cancel()
            runCurrent()

            val late = buffer()
            answer(late)

            assertTrue(late.isClosed)
            assertTrue(made.single().isRecycled)
        }

    @Test
    fun anAnswerThatArrivedJustAsTheWaitWasCancelledIsFreed() =
        runTest {
            val shot = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { host.screenshot(0) }
            answer(buffer())
            // The answer is in, but the waiter has not run yet when it is cancelled.
            shot.cancel()
            runCurrent()

            assertTrue(shot.isCancelled)
            assertTrue(made.single().isRecycled)
        }

    @Test
    fun aDeliveredScreenshotIsTheCallersToRecycle() =
        runTest {
            val shot = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { host.screenshot(0) }
            answer(buffer())
            runCurrent()

            val captured = shot.getCompleted() as Screenshot.Captured
            assertSame(made.single(), captured.bitmap)
            assertFalse(captured.bitmap.isRecycled)
        }

    @Test
    fun aDeadConnectionMakesTheScreenUnavailable() =
        runTest {
            val gone = RuntimeException(RemoteException("binder died"))
            service.failure = gone

            val error =
                try {
                    host.screenshot(0)
                    null
                } catch (expected: ScreenUnavailableException) {
                    expected
                }

            assertEquals(gone, error?.cause)
        }

    @Test
    fun aScreenshotThatNeverAnswersEndsInNoFrame() =
        runTest {
            val fake = FakeHost().apply { takeShot = { awaitCancellation() } }
            val frame = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { mainPort(fake).frame() }

            advanceTimeBy(SHOT_DEADLINE_MS + 1)

            assertTrue("the frame gave up", frame.isCompleted)
            assertNull(frame.getCompleted())
        }

    private companion object {
        const val ANSWER_WAIT_S = 5L

        /** Default screenshot deadline of the port (ScreenTimings). */
        const val SHOT_DEADLINE_MS = 10_000L
    }
}
