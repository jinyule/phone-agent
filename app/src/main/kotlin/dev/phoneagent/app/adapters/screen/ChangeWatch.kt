package dev.phoneagent.app.adapters.screen

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a display to settle after something happened, driven by accessibility events (phase1
 * §3.3, idea from dsh's EventClock): the first event, then [quietMs] without one, never longer than
 * [settleCapMs] after the first event. No polling; all waits are coroutine time.
 *
 * Open the watch before acting ([watching]) so the first events caused by the action are not missed.
 */
internal class ChangeWatch private constructor(
    private val ticks: Channel<A11yEvent>,
    private val quietMs: Long,
    private val settleCapMs: Long,
) {
    /**
     * Suspends until the display settled or [timeoutMs] passed in total. Returns whether any
     * relevant event arrived, i.e. something on the display changed.
     */
    suspend fun awaitSettled(timeoutMs: Long): Boolean {
        var seen = false
        withTimeoutOrNull(timeoutMs) {
            ticks.receive()
            seen = true
            withTimeoutOrNull(settleCapMs) {
                while (withTimeoutOrNull(quietMs) { ticks.receive() } != null) {
                    // Another event inside the quiet window: keep waiting.
                }
            }
        }
        return seen
    }

    /**
     * Suspends until an event satisfies [predicate] or [timeoutMs] passes; checks [predicate]
     * once before waiting. Returns whether it was satisfied.
     */
    suspend fun awaitUntil(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        if (predicate()) return true
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                ticks.receive()
                if (predicate()) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    internal companion object {
        /** Event types that are not a visible change of the display. */
        const val IGNORED_TYPES: Int =
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
                AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
                AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
                AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

        /**
         * Runs [block] with a watch on [events] of [displayId]. The subscription is in place
         * before [block] starts and ends with it.
         */
        suspend fun <T> watching(
            events: Flow<A11yEvent>,
            displayId: Int,
            timings: ScreenTimings,
            block: suspend CoroutineScope.(ChangeWatch) -> T,
        ): T =
            coroutineScope {
                val ticks = Channel<A11yEvent>(Channel.UNLIMITED)
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        events.collect { event ->
                            if (event.displayId == displayId && event.type and IGNORED_TYPES == 0) ticks.trySend(event)
                        }
                    }
                try {
                    block(ChangeWatch(ticks, timings.quietMs, timings.settleCapMs))
                } finally {
                    collector.cancel()
                    ticks.close()
                }
            }
    }
}
