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
 * Events from windows passed to [ignore] (the status and navigation bars) are not a change.
 */
internal class ChangeWatch private constructor(
    private val ticks: Channel<A11yEvent>,
    private val quietMs: Long,
    private val settleCapMs: Long,
) {
    @Volatile private var ignored: Set<Int> = emptySet()

    /** From now on, events of [windowIds] are not a change, including those already received. */
    fun ignore(windowIds: Set<Int>) {
        ignored = windowIds
    }

    /** The next event that is a change. */
    private suspend fun next(): A11yEvent {
        while (true) {
            val event = ticks.receive()
            if (event.windowId !in ignored) return event
        }
    }

    /**
     * Suspends until the display settled or [timeoutMs] passed in total. Returns whether any
     * relevant event arrived, i.e. something on the display changed.
     */
    suspend fun awaitSettled(timeoutMs: Long): Boolean {
        var seen = false
        withTimeoutOrNull(timeoutMs) {
            next()
            seen = true
            withTimeoutOrNull(settleCapMs) {
                while (withTimeoutOrNull(quietMs) { next() } != null) {
                    // Another event inside the quiet window: keep waiting.
                }
            }
        }
        return seen
    }

    /**
     * Waits up to [firstWithinMs] for an event that [relevant] accepts (other events pass by);
     * after one came, waits for the display to settle as [awaitSettled] does, counting every
     * event: [quietMs] without one, at most [settleCapMs] after the relevant one. Returns whether
     * a relevant event came.
     */
    suspend fun awaitChange(
        firstWithinMs: Long,
        relevant: (A11yEvent) -> Boolean,
    ): Boolean {
        val came =
            withTimeoutOrNull(firstWithinMs) {
                while (!relevant(next())) {
                    // Not this change; keep waiting for one.
                }
            } != null
        if (came) {
            withTimeoutOrNull(settleCapMs) {
                while (withTimeoutOrNull(quietMs) { next() } != null) {
                    // Another event inside the quiet window: keep waiting.
                }
            }
        }
        return came
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
                next()
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
