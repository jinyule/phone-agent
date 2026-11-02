/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, the reflection helpers).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: every lookup is named and cached, failures carry the reason
 * instead of returning null, and the per-capability probe uses the same entry points.
 */
package dev.phoneagent.privileged.cap

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import java.lang.reflect.Method

/**
 * A hidden framework entry point the helper needs is missing or refused. The message names the
 * capability so it can be reported through `probe()` instead of failing silently
 * (research §5.3 point 7).
 */
internal class CapabilityUnavailable(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The hidden framework interfaces the helper reflects on. Each accessor throws
 * [CapabilityUnavailable] naming what is missing; nothing is swallowed.
 *
 * `getSystemService` is not used for [displayManager]: that one is bound to the system context and
 * reports the wrong package for the shell uid, so the display would not belong to us.
 */
@SuppressLint("PrivateApi")
internal open class SystemBridge(
    private val context: Context,
) {
    /**
     * `IActivityTaskManager`: tasks, cross-display starts, the task-stack listener. Open, like
     * [windowManager], so a test can stand in an object with the same method names.
     */
    open val activityTaskManager: Any by lazy {
        reflect("IActivityTaskManager") {
            Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        }
    }

    /** `IWindowManager`: the display IME policy. */
    open val windowManager: Any by lazy {
        reflect("IWindowManager") {
            Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
        }
    }

    /** A `DisplayManager` bound to the shell context, so virtual displays belong to the helper. */
    val displayManager: DisplayManager by lazy {
        reflect("DisplayManager(Context)") {
            DisplayManager::class.java
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)
        }
    }

    /** `InputManagerGlobal` (API 34+) or `InputManager`, whichever carries `injectInputEvent`. */
    val inputManager: Any by lazy {
        val global =
            try {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
            } catch (e: ReflectiveOperationException) {
                Log.i(TAG, "InputManagerGlobal is not available on this build", e)
                null
            }
        global ?: reflect("InputManager.getInstance") {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        }
    }

    /** `TaskInfo`, whose public fields describe a task. */
    val taskInfo: Class<*> by lazy { reflect("TaskInfo") { Class.forName("android.app.TaskInfo") } }

    /**
     * The method named [name] on [owner] whose parameter count is [parameters], preferring the
     * first match. Overloads differ between API levels, so the count picks the right one.
     */
    fun method(
        owner: Any,
        name: String,
        parameters: Int,
    ): Method =
        owner.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == parameters }
            ?: throw CapabilityUnavailable("$name with $parameters parameters is missing on ${owner.javaClass.name}")

    private fun <T> reflect(
        what: String,
        lookup: () -> T?,
    ): T =
        try {
            lookup() ?: throw CapabilityUnavailable("$what is not available on this build")
        } catch (e: ReflectiveOperationException) {
            throw CapabilityUnavailable("$what is not available on this build", e)
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}

/** Runs [block], turning reflection failures into [CapabilityUnavailable] that names [what]. */
internal inline fun <T> reflecting(
    what: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: ReflectiveOperationException) {
        throw CapabilityUnavailable("$what failed through reflection", e)
    }
