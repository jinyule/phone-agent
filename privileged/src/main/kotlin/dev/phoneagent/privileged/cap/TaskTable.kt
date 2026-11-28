/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `tasksOn` / `allTasks` /
 * `packagesOf` / `taskRows` / `moveTask`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: one reader produces the shared TaskRow snapshot (including
 * visibility and activity type), and missing fields are reported instead of silently defaulted.
 */
package dev.phoneagent.privileged.cap

import android.content.ComponentName
import android.util.Log
import java.lang.reflect.Field

/**
 * Reads the task list and moves tasks between displays through `IActivityTaskManager`
 * (`REAL_GET_TASKS`, which the shell user holds, makes every app's tasks visible).
 */
internal class TaskTable(
    private val bridge: SystemBridge,
    private val commands: CommandRunner = ShellCommands.runner,
) {
    private val fields by lazy { TaskFields(bridge.taskInfo) }

    /**
     * Every task on every display, most recently used first. Throws [CapabilityUnavailable] when
     * the task list cannot be read: callers must refuse to act rather than guess.
     */
    fun rows(): List<TaskRow> {
        val atm = bridge.activityTaskManager
        val withDisplay = atm.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterTypes.size == 4 }
        val tasks =
            reflecting("IActivityTaskManager.getTasks") {
                if (withDisplay != null) {
                    withDisplay.invoke(atm, MAX_TASKS, false, false, INVALID_DISPLAY)
                } else {
                    bridge.method(atm, "getTasks", 3).invoke(atm, MAX_TASKS, false, false)
                }
            } as? List<*> ?: throw CapabilityUnavailable("getTasks returned no list")
        return reflecting("TaskInfo fields") { tasks.filterNotNull().mapIndexed(fields::read) }
    }

    /** The task on top of [displayId], or -1. */
    fun topTask(displayId: Int): Int = rows().firstOrNull { it.displayId == displayId }?.taskId ?: -1

    /**
     * `IActivityTaskManager.moveRootTaskToDisplay`, the call behind `am display move-stack`.
     * Falls back to that command when the hidden method is gone.
     */
    fun moveToDisplay(
        taskId: Int,
        displayId: Int,
    ): Boolean {
        val atm = bridge.activityTaskManager
        return try {
            reflecting("IActivityTaskManager.moveRootTaskToDisplay") {
                bridge.method(atm, "moveRootTaskToDisplay", 2).invoke(atm, taskId, displayId)
            }
            true
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", e)
            commands.run("am", "display", "move-stack", "$taskId", "$displayId").ok
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val MAX_TASKS = 100

        /** `Display.INVALID_DISPLAY`: getTasks does not filter by display. */
        const val INVALID_DISPLAY = -1
    }
}

/** The `TaskInfo` fields a [TaskRow] is built from; optional ones are missing on some builds. */
private class TaskFields(
    taskInfo: Class<*>,
) {
    private val taskId = taskInfo.getField("taskId")
    private val displayId = taskInfo.getField("displayId")
    private val topActivity = taskInfo.getField("topActivity")
    private val baseActivity = taskInfo.getField("baseActivity")
    private val userId = optional(taskInfo, "userId")
    private val isVisible = optional(taskInfo, "isVisible")
    private val topActivityType = optional(taskInfo, "topActivityType")

    /** [index] is the position in the most-recently-used order, used when visibility is missing. */
    fun read(
        index: Int,
        task: Any,
    ): TaskRow {
        val top = topActivity.get(task) as? ComponentName
        val base = baseActivity.get(task) as? ComponentName
        return TaskRow(
            taskId = taskId.getInt(task),
            displayId = displayId.getInt(task),
            packages = listOfNotNull(top?.packageName, base?.packageName).distinct(),
            topActivity = top?.className,
            userId = userId?.getInt(task) ?: TaskRow.UNKNOWN_USER,
            // Without the field, only the most recently used task counts as visible (as in Mika).
            visible = isVisible?.getBoolean(task) ?: (index == 0),
            standard = topActivityType?.getInt(task)?.let { it in STANDARD_TYPES } ?: true,
        )
    }

    private companion object {
        /** `WindowConfiguration.ACTIVITY_TYPE_UNDEFINED` and `_STANDARD`. */
        val STANDARD_TYPES = setOf(0, 1)

        fun optional(
            owner: Class<*>,
            name: String,
        ): Field? =
            try {
                owner.getField(name)
            } catch (e: NoSuchFieldException) {
                android.util.Log.i("PhoneAgentHelper", "TaskInfo.$name is missing on this build", e)
                null
            }
    }
}
