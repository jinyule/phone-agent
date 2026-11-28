package dev.phoneagent.app.debug

import android.os.RemoteException

/**
 * Collects the lines a self-check prints. Every step is `name: ok …` or `name: FAILED …`, and the
 * last line is `PASS` or `FAIL: <the steps that failed>`, which the receiver returns as the
 * broadcast result so a script can read it without parsing logcat.
 */
internal class SelfCheckSteps {
    private val lines = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    /** Records a plain observation. */
    fun note(line: String) {
        lines += line
    }

    /** Records [name] as passed or failed, with [detail] either way. */
    fun check(
        name: String,
        passed: Boolean,
        detail: String = "",
    ): Boolean {
        val suffix = if (detail.isEmpty()) "" else " $detail"
        lines += if (passed) "$name: ok$suffix" else "$name: FAILED$suffix"
        if (!passed) failures += name
        return passed
    }

    /** Records that the check could not run at all. */
    fun fail(
        name: String,
        detail: String,
    ): Boolean = check(name, passed = false, detail = detail)

    /**
     * Runs [block], which calls across the helper boundary. When it throws, records [name] as failed
     * with the exception and returns null, so the later steps and the cleanup still run.
     */
    @Suppress("TooGenericExceptionCaught") // Parcel.readException rethrows any RuntimeException of the helper.
    fun <T> attempt(
        name: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: RemoteException) {
            fail(name, "$e")
            null
        } catch (e: RuntimeException) {
            fail(name, "$e")
            null
        }

    /** The collected lines plus the verdict. */
    fun lines(): List<String> = lines + if (failures.isEmpty()) "PASS" else "FAIL: ${failures.joinToString(", ")}"
}

/** Polls [block] until it returns a non-null value or [timeoutMs] passes. */
internal fun <T : Any> pollUntil(
    timeoutMs: Long,
    stepMs: Long = 100,
    block: () -> T?,
): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        block()?.let { return it }
        Thread.sleep(stepMs)
    }
    return block()
}
