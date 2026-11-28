/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, the `start` /
 * `restart` / `inUseOnMainScreen` checks).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the decisions are pure functions over a task snapshot,
 * and moving a task is gated on the agent having owned it.
 */
package dev.phoneagent.privileged.cap

import dev.phoneagent.privileged.StartResult

/** One task as the system reports it; [packages] holds the base and top activity packages. */
internal data class TaskRow(
    val taskId: Int,
    val displayId: Int,
    val packages: List<String>,
    val topActivity: String? = null,
    val userId: Int = UNKNOWN_USER,
    val visible: Boolean = false,
    /** An ordinary app activity, not the launcher, recents or an assistant. */
    val standard: Boolean = true,
) {
    companion object {
        const val UNKNOWN_USER = -1
        const val MAIN_DISPLAY = 0
    }
}

/**
 * Who may be started where (phase1-architecture §2.2, research §2.4 step 8: never take an app the
 * user is working with). Pure functions over a task snapshot so every branch is unit-tested.
 */
internal object LaunchGate {
    /**
     * Why [targets] must not be started on [agentDisplay], or null when it may be.
     * [agentDisplays] are all displays the helper owns; [userId] scopes the check to one copy of
     * the app (a clone in another user does not block the original). A task whose user is unknown
     * counts as a match: better to refuse than to disturb the user.
     */
    fun refuse(
        tasks: List<TaskRow>,
        targets: Collection<String>,
        agentDisplay: Int,
        agentDisplays: Set<Int>,
        userId: Int,
    ): Int? {
        val mine = tasks.filter { task -> targets.any { it in task.packages } && sameUser(task, userId) }
        val onMain = mine.filter { it.displayId == TaskRow.MAIN_DISPLAY }
        val others = agentDisplays - agentDisplay
        return when {
            onMain.any { it.visible } -> StartResult.IN_USE_ON_MAIN
            mine.any { it.displayId in others } -> StartResult.IN_USE_BY_AGENT
            else -> null
        }
    }

    /** What a cold start should really do for [target] on [agentDisplay]. */
    fun coldStart(
        tasks: List<TaskRow>,
        target: String,
        agentDisplay: Int,
        userId: Int,
    ): ColdStart {
        val elsewhere =
            tasks.any { task ->
                target in task.packages && sameUser(task, userId) && task.displayId != agentDisplay
            }
        // Killing the process would throw away state the user or another task still has on screen.
        return if (elsewhere) ColdStart.KEEP_PROCESS else ColdStart.RESTART
    }

    /** Whether [task] belongs to the copy of the app installed for [userId]. */
    private fun sameUser(
        task: TaskRow,
        userId: Int,
    ): Boolean = task.userId == TaskRow.UNKNOWN_USER || task.userId == userId

    /** Outcome of [coldStart]. */
    enum class ColdStart {
        /** Force-stop the app, then start it: all of its tasks are on this agent display. */
        RESTART,

        /** Start it warm and report [StartResult.COLD_IGNORED]: it has tasks elsewhere. */
        KEEP_PROCESS,
    }
}

/**
 * Which tasks the helper may move where. The helper only moves tasks it started on one of its own
 * displays, so a task the user opened can never be pulled into the background, and a handed-over
 * task can be taken back from the main screen.
 */
internal class TaskOwnership {
    private val lock = Any()
    private val owned = mutableSetOf<Int>()

    /** Records [taskId] as started by the agent. */
    fun remember(taskId: Int) {
        if (taskId >= 0) synchronized(lock) { owned += taskId }
    }

    /** Forgets every task and returns how many there were; used by `releaseAll`. */
    fun clear(): Int = synchronized(lock) { owned.size.also { owned.clear() } }

    fun owns(taskId: Int): Boolean = synchronized(lock) { taskId in owned }

    /**
     * Why task [taskId] must not move to [displayId], or null when it may.
     *
     * [tasks] is the current snapshot and [agentDisplays] are the helper's own displays. [spilled]
     * are tasks that surfaced on the main screen while the agent was watching it (research §2.4
     * step 6): an app the agent drives can open another app from the background, and the system
     * puts that new task on the user's screen. Such a task may be moved back even though the agent
     * did not start it, which is what makes spill-back possible.
     */
    fun refuse(
        taskId: Int,
        displayId: Int,
        tasks: List<TaskRow>,
        agentDisplays: Set<Int>,
        spilled: Set<Int> = emptySet(),
    ): String? {
        val task = tasks.firstOrNull { it.taskId == taskId }
        val agents = task != null && task.displayId in agentDisplays
        return when {
            task == null -> {
                "task $taskId does not exist"
            }

            displayId != TaskRow.MAIN_DISPLAY && displayId !in agentDisplays -> {
                "display $displayId is neither the main screen nor one of the agent's displays"
            }

            !agents && !owns(taskId) && taskId !in spilled -> {
                "task $taskId was neither started by the agent nor seen arriving on the main screen"
            }

            else -> {
                null
            }
        }
    }
}
