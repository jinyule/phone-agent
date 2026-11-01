/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/MainScreenWatch.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the window lives in MainScreenLog, the task-stack listener
 * is registered once and reported through probe(), and arrivals are converted with ArrivalKeys.
 */
package dev.phoneagent.privileged.cap

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import dev.phoneagent.privileged.ArrivalKeys

/**
 * Registers the helper's Binder with the activity manager as an `ITaskStackListener`, the
 * "something changed" signal behind [MainScreenWatch].
 */
private class TaskStackListener(
    private val bridge: SystemBridge,
    private val signal: Binder,
) {
    fun register(): Boolean =
        try {
            val atm = bridge.activityTaskManager
            reflecting("IActivityTaskManager.registerTaskStackListener") {
                bridge.method(atm, "registerTaskStackListener", 1).invoke(atm, listener())
            }
            Log.i(TAG, "task stack listener registered")
            true
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "task stack listener unavailable, the window is polled instead", e)
            false
        }

    fun unregister() {
        try {
            val atm = bridge.activityTaskManager
            reflecting("IActivityTaskManager.unregisterTaskStackListener") {
                bridge.method(atm, "unregisterTaskStackListener", 1).invoke(atm, listener())
            }
        } catch (e: CapabilityUnavailable) {
            Log.i(TAG, "cannot unregister the task stack listener", e)
        }
    }

    // ITaskStackListener has no public equivalent; without it the window has to be polled.
    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        reflecting("ITaskStackListener.Stub.asInterface") {
            Class
                .forName("android.app.ITaskStackListener\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, signal)
        } ?: throw CapabilityUnavailable("ITaskStackListener.asInterface returned null")

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}

/**
 * Watches the main screen around an agent action (research §2.4 step 6). An app that opens another
 * app from the background (a service, a notification trampoline, a system chooser) lands on the
 * user's screen; the app side sees it here and moves it back.
 *
 * The task-stack listener is only used as a "something changed" signal — its callback parameters
 * differ between releases — and the task list is re-read on every signal. Where the listener
 * cannot be registered, the window is polled instead. Nothing is read while no window is open.
 */
internal class MainScreenWatch(
    bridge: SystemBridge,
    private val tasks: TaskTable,
    private val log: MainScreenLog = MainScreenLog(),
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val thread = HandlerThread("phoneagent-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    private val signal = StackSignal()
    private val stack = TaskStackListener(bridge, signal)

    /** True once the listener is registered, false when it could not be, null before the first watch. */
    @Volatile
    private var listening: Boolean? = null

    @Volatile
    private var polling = false

    @Volatile
    private var coalescing = false

    /** Starts or extends the window and returns the task on top of the main screen (-1 for none). */
    fun watch(windowMs: Int): Int {
        val now = clock()
        val top = synchronized(log) { log.watch(snapshot(), now, windowMs.toLong()) }
        if (listening == null) listening = stack.register()
        if (listening == false && !polling) {
            polling = true
            handler.post(::poll)
        }
        return top
    }

    /** Arrivals at or after [since]; see [ArrivalKeys]. */
    fun arrivals(since: Long): Bundle =
        synchronized(log) {
            val now = clock()
            // One last look, so a change just before the call is not missed.
            if (log.watching(now + GRACE_MS)) log.scan(snapshot(), now)
            bundle(log.since(since), log::displayOf, listening == true)
        }

    /** Tasks that arrived on the main screen while the agent was watching; see [TaskOwnership.refuse]. */
    fun spilled(): Set<Int> = synchronized(log) { log.arrivedTasks() }

    /** Registers the listener eagerly so `probe()` can report whether it works. */
    fun start(): Boolean = (listening ?: stack.register().also { listening = it })

    fun close() {
        if (listening == true) stack.unregister()
        listening = null
        thread.quitSafely()
    }

    private fun snapshot(): List<TaskRow> =
        try {
            tasks.rows()
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "cannot read the task list", e)
            emptyList()
        }

    private fun scan() {
        val now = clock()
        synchronized(log) { if (log.watching(now)) log.scan(snapshot(), now) }
    }

    /** A burst of task changes is read once. */
    private fun onSignal() {
        if (!synchronized(log) { log.watching(clock()) } || coalescing) return
        coalescing = true
        handler.postDelayed({
            coalescing = false
            scan()
        }, COALESCE_MS)
    }

    private fun poll() {
        if (!synchronized(log) { log.watching(clock()) }) {
            polling = false
            return
        }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    /**
     * The Binder the system calls back on. Every `ITaskStackListener` method is one-way and only
     * the fact that one arrived is used, so the parameters are never parsed.
     */
    private inner class StackSignal : Binder() {
        init {
            attachInterface(null, DESCRIPTOR)
        }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        const val COALESCE_MS = 30L

        /** Still take one last look shortly after the window closed: the app needs a moment to ask. */
        const val GRACE_MS = 2_000L

        /** Arrivals as parallel arrays; [now] is the display a task is on at this moment. */
        fun bundle(
            arrivals: List<MainScreenLog.Arrival>,
            now: (Int) -> Int,
            listening: Boolean,
        ): Bundle =
            Bundle().apply {
                putIntArray(ArrivalKeys.TASK, arrivals.map { it.taskId }.toIntArray())
                putStringArray(ArrivalKeys.PACKAGE, arrivals.map { it.packageName }.toTypedArray())
                putStringArray(ArrivalKeys.ACTIVITY, arrivals.map { it.topActivity }.toTypedArray())
                putIntArray(ArrivalKeys.USER, arrivals.map { it.userId }.toIntArray())
                putLongArray(ArrivalKeys.AT, arrivals.map { it.at }.toLongArray())
                putIntArray(ArrivalKeys.FROM, arrivals.map { it.from }.toIntArray())
                putBooleanArray(ArrivalKeys.SAME_TASK, arrivals.map { it.sameTask }.toBooleanArray())
                putBooleanArray(ArrivalKeys.STANDARD, arrivals.map { it.standard }.toBooleanArray())
                putIntArray(ArrivalKeys.NOW, arrivals.map { now(it.taskId) }.toIntArray())
                putBoolean(ArrivalKeys.LISTENING, listening)
            }
    }
}
