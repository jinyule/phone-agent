package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the blocking [block] on [io]. Socket I/O does not react to coroutine cancellation, so when
 * the caller is cancelled (or its timeout fires) while [block] runs, [resource] is closed, which
 * makes the blocked call fail. That failure is then reported as the cancellation it stands for:
 * an [IOException] thrown while the caller is no longer active becomes its CancellationException.
 */
@Throws(IOException::class)
internal suspend fun <T> blockingIo(
    io: CoroutineDispatcher,
    resource: Closeable,
    block: () -> T,
): T =
    try {
        withContext(io) {
            val finished = AtomicBoolean(false)
            val closer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) resource.closeQuietly()
                    }
                }
            try {
                block()
            } finally {
                finished.set(true)
                closer.cancel()
            }
        }
    } catch (failure: IOException) {
        // If we were cancelled, the failure is our own close: rethrow the cancellation instead.
        currentCoroutineContext().ensureActive()
        throw failure
    }

/** Closes this, ignoring a failure to close: there is nothing left to do with the resource. */
internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (ignoredCloseFailure: IOException) {
        // A socket that cannot be closed cleanly is closed anyway as far as we are concerned.
    }
}
