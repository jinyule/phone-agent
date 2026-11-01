package dev.phoneagent.app.adapters.screen

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event-driven settling in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeWatchTest {
    private val timings = ScreenTimings(quietMs = 300, settleCapMs = 3_000)
    private val events = MutableSharedFlow<A11yEvent>(extraBufferCapacity = 64)

    private fun event(
        type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        displayId: Int = 0,
    ) = A11yEvent(displayId, type, APP, null)

    /** Emits [event] at each of [times] (virtual ms from now). */
    private fun TestScope.emitAt(
        vararg times: Long,
        event: A11yEvent = event(),
    ) {
        launch {
            var now = 0L
            for (t in times) {
                delay(t - now)
                now = t
                events.emit(event)
            }
        }
    }

    @Test
    fun withoutEventsItGivesUpAtTheTimeout() =
        runTest {
            val seen = ChangeWatch.watching(events, 0, timings) { it.awaitSettled(timeoutMs = 2_000) }

            assertFalse(seen)
            assertEquals(2_000, currentTime)
        }

    @Test
    fun settlesAfterTheFirstEventAndAQuietWindow() =
        runTest {
            emitAt(100, 250)

            val seen = ChangeWatch.watching(events, 0, timings) { it.awaitSettled(timeoutMs = 5_000) }

            assertTrue(seen)
            assertEquals(250 + 300, currentTime)
        }

    @Test
    fun aBusyDisplayIsCappedAfterTheFirstEvent() =
        runTest {
            emitAt(*LongArray(60) { 100L + it * 100 })

            val seen = ChangeWatch.watching(events, 0, timings) { it.awaitSettled(timeoutMs = 10_000) }

            assertTrue(seen)
            assertEquals(100 + 3_000, currentTime)
        }

    @Test
    fun eventsOfOtherDisplaysAndNonVisualEventsDoNotCount() =
        runTest {
            emitAt(100, event = event(displayId = 2))
            emitAt(200, event = event(type = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
            emitAt(300, event = event(type = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED))

            val seen = ChangeWatch.watching(events, 0, timings) { it.awaitSettled(timeoutMs = 1_000) }

            assertFalse(seen)
            assertEquals(1_000, currentTime)
        }

    @Test
    fun theWatchIsSubscribedBeforeTheBlockActs() =
        runTest {
            val seen =
                ChangeWatch.watching(events, 0, timings) { watch ->
                    // The action itself emits synchronously, before anything waits.
                    assertTrue(events.tryEmit(event()))
                    watch.awaitSettled(timeoutMs = 1_000)
                }

            assertTrue(seen)
            assertEquals(300, currentTime)
        }

    @Test
    fun awaitUntilChecksFirstAndThenOnEachEvent() =
        runTest {
            var ready = true
            val immediate = ChangeWatch.watching(events, 0, timings) { it.awaitUntil(1_000) { ready } }
            assertTrue(immediate)
            assertEquals(0, currentTime)

            ready = false
            var checks = 0
            emitAt(100, 200, 300)
            val later =
                ChangeWatch.watching(events, 0, timings) {
                    it.awaitUntil(1_000) { ++checks >= 3 }
                }
            assertTrue(later)
            assertEquals(200, currentTime)
        }

    @Test
    fun awaitUntilGivesUpAtTheTimeout() =
        runTest {
            emitAt(100, 200)

            val met = ChangeWatch.watching(events, 0, timings) { it.awaitUntil(1_000) { false } }

            assertFalse(met)
            assertEquals(1_000, currentTime)
        }
}
