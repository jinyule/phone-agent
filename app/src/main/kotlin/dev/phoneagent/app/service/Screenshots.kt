/*
 * Adapted from Mika, app/src/main/java/com/wangzan101/mika/cap/phone/a11y/ScreenAccessibility.kt
 * (https://github.com/wangzan101/Mika, commit 9cef3750addd20b2af9e3214a8f5e56301670174),
 * Copyright 2026 wangzan101, licensed under the Apache License, Version 2.0 (see NOTICE): the
 * screenshot that waits out the system interval once and copies the hardware buffer into a
 * software bitmap. Modified by PhoneAgent contributors: rewritten in English, any display, every
 * request ended on close or cancellation with its buffer freed, a dead connection reported.
 */
package dev.phoneagent.app.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.RemoteException
import dev.phoneagent.app.adapters.screen.Screenshot
import dev.phoneagent.app.adapters.screen.release
import dev.phoneagent.core.ports.ScreenUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Screenshots through one accessibility connection, for [ServiceScreenHost]. Every request ends:
 * with the system's answer, as failed when [close] runs meanwhile, or by the caller's
 * cancellation; a screenshot nobody waits for any more is freed when it arrives.
 *
 * @param convert turns a screenshot into a software bitmap and closes its hardware buffer.
 */
internal class Screenshots(
    private val service: AccessibilityService,
    private val convert: (AccessibilityService.ScreenshotResult) -> Bitmap?,
) {
    // Requests still waited for; ended by close().
    private val inFlight: MutableSet<CompletableDeferred<Screenshot>> = ConcurrentHashMap.newKeySet()

    @Volatile private var closed = false

    /** Ends the requests in flight as failed; later ones fail at once. */
    fun close() {
        closed = true
        inFlight.forEach { it.complete(CLOSED) }
    }

    /** Captures [displayId], waiting out the system's screenshot interval once. */
    suspend fun take(displayId: Int): Screenshot {
        val first = once(displayId)
        val tooSoon =
            first is Screenshot.Failed && first.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        if (!tooSoon) return first
        // The system allows one screenshot per interval (1 s on AOSP); wait it out once.
        delay(SCREENSHOT_INTERVAL_MS)
        return once(displayId)
    }

    /**
     * One request. The answer arrives on the process-wide [SHOTS] thread, which outlives the
     * connection, and is always converted (the hardware buffer closed); when nobody waits for it
     * any more (cancelled, timed out, closed) the bitmap is recycled; an answer that arrived just
     * as the wait was cancelled is read with `getCompleted()` and freed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun once(displayId: Int): Screenshot {
        val answer = CompletableDeferred<Screenshot>()
        inFlight += answer
        // Checked after registering, so a close() in between still ends this wait.
        if (closed) answer.complete(CLOSED) else request(displayId, answer)
        var delivered = false
        return try {
            answer.await().also { delivered = true }
        } finally {
            inFlight -= answer
            // Not delivered (cancelled): an answer already there is freed here; a later one finds
            // the wait completed and frees itself.
            if (!delivered && !answer.complete(CLOSED)) answer.getCompleted().release()
        }
    }

    /** Sends the request; the platform reports a dead connection by throwing (see the catch). */
    @Suppress("TooGenericExceptionCaught")
    private fun request(
        displayId: Int,
        answer: CompletableDeferred<Screenshot>,
    ) {
        val callback =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val shot = convert(result)?.let(Screenshot::Captured) ?: CLOSED
                    if (!answer.complete(shot)) shot.release()
                }

                override fun onFailure(errorCode: Int) {
                    answer.complete(failureOf(errorCode))
                }
            }
        try {
            service.takeScreenshot(displayId, SHOTS, callback)
        } catch (failed: RuntimeException) {
            inFlight -= answer
            // The platform rethrows a dead connection's RemoteException as a RuntimeException.
            throw if (failed.cause is RemoteException) {
                ScreenUnavailableException("the accessibility connection is gone", failed)
            } else {
                failed
            }
        }
    }

    internal companion object {
        private const val SCREENSHOT_INTERVAL_MS = 1_000L

        /** What a screenshot ends with when it cannot be taken or converted, or the connection closed. */
        private val CLOSED = Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)

        /**
         * Thread for screenshot answers, shared by every connection of the process and never shut
         * down: answers may arrive after their connection closed and must still free their buffers.
         */
        private val SHOTS: Executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "phoneagent-screenshots").apply { isDaemon = true }
            }

        /** A software copy of [result]; its hardware buffer is closed either way. */
        fun softwareCopy(result: AccessibilityService.ScreenshotResult): Bitmap? =
            result.hardwareBuffer.use { buffer ->
                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                    try {
                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardware.recycle()
                    }
                }
            }

        private fun failureOf(code: Int): Screenshot =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            ) {
                Screenshot.Secure
            } else {
                Screenshot.Failed(code)
            }
    }
}
