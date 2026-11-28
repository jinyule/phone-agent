/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/MainScreenLog.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: reuses the shared TaskRow snapshot, keeps the watch window
 * inside the log (so "are we watching" is one decision), and prunes by the caller's clock only.
 */
package dev.phoneagent.privileged.cap

import dev.phoneagent.privileged.ArrivalKeys

/**
 * Tasks that surfaced on the main screen (research §2.4 step 6). The agent calls
 * `watchMainScreen` before an action and `mainArrivals` after it: an app that opens another app
 * from the background lands on the main screen, and the agent moves it back.
 *
 * Only facts are recorded, not whether the agent caused them. A different task on top is one
 * arrival; the same task with a different top activity is also one ([Arrival.sameTask]).
 * [Arrival.from] is the display that task was last seen on, so a task coming back from a handover
 * is distinguishable from a new one.
 *
 * Pure logic: the caller supplies the clock and the snapshots. Not thread-safe; [MainScreenWatch]
 * serializes access.
 */
internal class MainScreenLog(
    private val keepMs: Long = KEEP_MS,
    private val maxWindowMs: Long = MAX_WINDOW_MS,
) {
    /** A task seen on top of the main screen at [at] (`SystemClock.uptimeMillis`). */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val topActivity: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
    )

    private var top: TaskRow? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()
    private var until = 0L

    /** Whether [now] is inside the window a caller asked for. */
    fun watching(now: Long): Boolean = now < until

    /**
     * Starts (or extends) the watch window to [windowMs] from [now] and returns the task currently
     * on top of the main screen (-1 when there is none). [tasks] is the snapshot taken at [now].
     */
    fun watch(
        tasks: List<TaskRow>,
        now: Long,
        windowMs: Long,
    ): Int {
        scan(tasks, now)
        until = maxOf(until, now + windowMs.coerceIn(0, maxWindowMs))
        return topTaskId
    }

    /**
     * Feeds a snapshot. Arrivals are only recorded while the window is open: outside it there is
     * no way to tell when a change happened, so the snapshot only becomes the new baseline.
     */
    fun scan(
        tasks: List<TaskRow>,
        now: Long,
    ) {
        val mainTop = tasks.firstOrNull { it.displayId == TaskRow.MAIN_DISPLAY }
        val previous = top
        if (watching(now) && mainTop != null && previous != null) record(mainTop, previous, now)
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun record(
        mainTop: TaskRow,
        previous: TaskRow,
        now: Long,
    ) {
        val newTask = mainTop.taskId != previous.taskId
        // Nothing is recorded when neither the task nor its top page changed.
        if (newTask) {
            add(mainTop, now, displays[mainTop.taskId] ?: ArrivalKeys.FROM_NEW, sameTask = false)
        } else if (mainTop.topActivity != previous.topActivity) {
            add(mainTop, now, TaskRow.MAIN_DISPLAY, sameTask = true)
        }
    }

    private fun add(
        task: TaskRow,
        now: Long,
        from: Int,
        sameTask: Boolean,
    ) {
        arrivals.addLast(
            Arrival(
                taskId = task.taskId,
                packageName = task.packages.firstOrNull(),
                topActivity = task.topActivity,
                userId = task.userId,
                at = now,
                from = from,
                sameTask = sameTask,
                standard = task.standard,
            ),
        )
    }

    /** The task on top of the main screen as last seen, or -1. */
    val topTaskId: Int get() = top?.taskId ?: -1

    /**
     * Tasks that surfaced on the main screen while a window was open (and are still remembered).
     * These are the candidates for "an app the agent was driving opened something on the user's
     * screen": they may be moved back even though the agent did not start them itself.
     */
    fun arrivedTasks(): Set<Int> = arrivals.map { it.taskId }.toSet()

    /** Arrivals recorded at or after [since], oldest first. */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** The display [taskId] was last seen on, or -1 when it is gone. */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        /** How long an arrival stays readable. */
        const val KEEP_MS = 60_000L

        /** Longest window a caller may ask for. */
        const val MAX_WINDOW_MS = 30_000L
    }
}
