package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val RUNNING = 0
private const val DONE = 1
private const val CLOSED = 2

/**
 * Runs the blocking [block] on [io]. Socket I/O does not react to coroutine cancellation, so when
 * the caller is cancelled (or its timeout fires) while [block] runs, [resource] is closed, which
 * makes the blocked call fail. The close is a cancellation handler: it runs on the thread that
 * cancels, at the moment it cancels, and never waits for a dispatcher, as every thread of [io] may
 * be blocked in a read that only this close can end. Whichever of the two comes first wins: a
 * [block] that finished keeps [resource] open, a close that happened stays. The failure of a
 * closed [block] is reported as the cancellation it stands for: an [IOException] thrown while the
 * caller is no longer active becomes its CancellationException.
 */
@Throws(IOException::class)
internal suspend fun <T> blockingIo(
    io: CoroutineDispatcher,
    resource: Closeable,
    block: () -> T,
): T {
    val state = AtomicInteger(RUNNING)
    return try {
        coroutineScope {
            // Undispatched, so that the handler is registered before block starts.
            val closer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Unit> { waiting ->
                        waiting.invokeOnCancellation {
                            if (state.compareAndSet(RUNNING, CLOSED)) resource.closeQuietly()
                        }
                    }
                }
            try {
                withContext(io) { block().also { state.compareAndSet(RUNNING, DONE) } }
            } finally {
                state.compareAndSet(RUNNING, DONE)
                closer.cancel()
            }
        }
    } catch (failure: IOException) {
        // If we were cancelled, the failure is our own close: rethrow the cancellation instead.
        currentCoroutineContext().ensureActive()
        throw failure
    }
}

/** Closes this, ignoring a failure to close: there is nothing left to do with the resource. */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (ignoredCloseFailure: IOException) {
        // A socket that cannot be closed cleanly is closed anyway as far as we are concerned.
    }
}

/** A millisecond timeout as a socket timeout argument, capped at what a socket takes. */
internal fun Long.toSocketTimeout(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * The SO_TIMEOUT of a socket whose operation is bounded by [operationTimeoutMs] in coroutine time:
 * twice that, so that it never ends a read before the coroutine timeout does, and only ends one that
 * closing the socket failed to end.
 */
internal fun socketTimeoutBackstop(operationTimeoutMs: Long): Int =
    (operationTimeoutMs.coerceAtMost(Int.MAX_VALUE / 2L) * 2).toInt()
