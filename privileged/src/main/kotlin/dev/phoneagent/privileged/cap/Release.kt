package dev.phoneagent.privileged.cap

import android.util.Log

private const val TAG = "PhoneAgentHelper"

/**
 * Releases each of [items] on its own: a RuntimeException from one is logged (naming it with
 * [what]) and the others are still released. Returns the items whose release threw, in order;
 * the caller keeps them so the next `releaseAll` tries again.
 */
internal fun <T> releaseEach(
    items: Iterable<T>,
    what: (T) -> String,
    release: (T) -> Unit,
): List<T> = items.filterNot { released(it, what, release) }

@Suppress("TooGenericExceptionCaught") // A release may fail in any way; the other resources must still go.
private fun <T> released(
    item: T,
    what: (T) -> String,
    release: (T) -> Unit,
): Boolean {
    try {
        release(item)
    } catch (e: RuntimeException) {
        Log.e(TAG, "releasing ${what(item)} failed; kept for the next releaseAll", e)
        return false
    }
    return true
}

/**
 * Runs the steps of a `releaseAll` in order, each on its own: a step that throws a
 * RuntimeException is logged and the later steps still run. Returns `name count` for every step,
 * or `name failed (exception)` for one that threw.
 */
@Suppress("TooGenericExceptionCaught") // One kind of resource failing must not keep the others held.
internal fun releaseSteps(vararg steps: Pair<String, () -> Int>): String =
    steps.joinToString(", ") { (name, step) ->
        try {
            "$name ${step()}"
        } catch (e: RuntimeException) {
            Log.e(TAG, "releaseAll: $name failed", e)
            "$name failed ($e)"
        }
    }
