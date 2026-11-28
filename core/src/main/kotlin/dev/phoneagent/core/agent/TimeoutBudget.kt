package dev.phoneagent.core.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Lets time spent waiting for the user be left out of a tool's timeout (phase1 §3.7). */
internal interface UserWait {
    /** Runs [block] (a wait for the user) with the clock paused. */
    suspend fun <T> paused(block: suspend () -> T): T

    /** No timeout to pause (prompts the loop shows before a call runs). */
    object None : UserWait {
        override suspend fun <T> paused(block: suspend () -> T): T = block()
    }
}

/**
 * The single timeout of one tool call (`Tool.timeoutMs`): [run] cancels the call once [limitMs]
 * of running time passed since [run] began, where time inside [paused] does not count. Measured
 * with [time], so tests use virtual time. Pauses do not nest (a call has at most one pending prompt).
 */
internal class TimeoutBudget(
    limitMs: Long,
    private val time: TimeSource.WithComparableMarks,
) : UserWait {
    private val limit = limitMs.milliseconds
    private val lock = Any()
    private var pausedTotal = Duration.ZERO
    private val pausedFlag = MutableStateFlow(false)

    /**
     * [block]'s value, or `null` when it ran out of time and was cancelled (a value that arrives
     * at the deadline is kept). Exceptions of [block] propagate; a stop cancels both.
     */
    suspend fun <T> run(block: suspend () -> T): T? =
        coroutineScope {
            val start = time.markNow()
            val work = async { block() }
            val watchdog = launch { awaitExpiry(start) }
            select {
                work.onAwait { value ->
                    watchdog.cancel()
                    value
                }
                watchdog.onJoin {
                    work.cancelAndJoin()
                    null
                }
            }
        }

    override suspend fun <T> paused(block: suspend () -> T): T {
        val since = time.markNow()
        pausedFlag.value = true
        try {
            return block()
        } finally {
            synchronized(lock) { pausedTotal += since.elapsedNow() }
            pausedFlag.value = false
        }
    }

    private suspend fun awaitExpiry(start: TimeMark) {
        while (true) {
            pausedFlag.first { !it }
            val left = synchronized(lock) { limit - (start.elapsedNow() - pausedTotal) }
            if (left <= Duration.ZERO) return
            delay(left)
        }
    }
}
