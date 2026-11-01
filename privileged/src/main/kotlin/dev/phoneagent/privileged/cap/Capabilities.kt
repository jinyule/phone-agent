package dev.phoneagent.privileged.cap

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import dev.phoneagent.privileged.DisplayResult
import dev.phoneagent.privileged.GrantResult
import dev.phoneagent.privileged.PrivilegedError
import dev.phoneagent.privileged.StartResult

/** Virtual displays the agent works on. */
internal interface DisplayCaps {
    fun createDisplay(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
    ): Int

    fun releaseDisplay(displayId: Int)
}

/** Tasks: starting apps on a display, finding and moving them, watching the main screen. */
internal interface TaskCaps {
    fun startActivity(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int

    fun topTask(displayId: Int): Int

    fun moveTask(
        taskId: Int,
        displayId: Int,
    ): Boolean

    fun watchMainScreen(windowMs: Int): Int

    fun mainArrivals(sinceUptimeMs: Long): Bundle
}

/** Injected input, always on one of the agent's own displays. */
internal interface InputCaps {
    fun injectStroke(
        displayId: Int,
        xs: IntArray?,
        ys: IntArray?,
        times: LongArray?,
    ): Boolean

    fun injectKey(
        displayId: Int,
        keyCode: Int,
    ): Boolean
}

/** App-wide changes: muting an app, granting the app its one permission. */
internal interface SystemCaps {
    fun setAudioMuted(
        pkg: String?,
        muted: Boolean,
    ): Boolean

    fun grantSelf(permission: String?): Int
}

/**
 * Everything [dev.phoneagent.privileged.helper.PrivilegedCore] exposes beyond the frozen
 * handoff methods, plus the probe and the cleanup that `releaseAll` runs.
 */
internal interface Capabilities {
    val displays: DisplayCaps
    val tasks: TaskCaps
    val input: InputCaps
    val system: SystemCaps

    fun probe(): Bundle

    /** Undoes everything held for the app: displays, mutes, task ownership. Returns what was released. */
    fun releaseAll(): String
}

/**
 * The capabilities as implemented on this device. Built once at startup; everything hidden is
 * reached through [SystemBridge], and a missing entry point is reported (logcat, [probe]) rather
 * than swallowed.
 */
internal class AndroidCapabilities(
    context: Context,
    appPackage: String,
    appUid: Int,
    bridge: SystemBridge = SystemBridge(context),
    sdkInt: Int = Build.VERSION.SDK_INT,
) : Capabilities {
    private val appUser = appUid / PER_USER_RANGE
    private val virtualDisplays = VirtualDisplays(bridge, sdkInt)
    private val taskTable = TaskTable(bridge)
    private val ownership = TaskOwnership()
    private val injector = InputInjector(bridge, virtualDisplays)
    private val launcher = Launcher(bridge, context, virtualDisplays, taskTable, ownership, appUser)
    private val watch = MainScreenWatch(bridge, taskTable)
    private val ops = SystemOps(context, appPackage, appUser)
    private val prober = Prober(virtualDisplays, taskTable, injector, watch)

    override val displays: DisplayCaps =
        object : DisplayCaps {
            override fun createDisplay(
                surface: Surface?,
                width: Int,
                height: Int,
                dpi: Int,
            ): Int = virtualDisplays.create(surface, width, height, dpi)

            override fun releaseDisplay(displayId: Int) = virtualDisplays.release(displayId)
        }

    override val tasks: TaskCaps =
        object : TaskCaps {
            override fun startActivity(
                displayId: Int,
                intent: Intent?,
                userId: Int,
                cold: Boolean,
            ): Int = launcher.start(displayId, intent, userId, cold)

            override fun topTask(displayId: Int): Int = top(displayId)

            override fun moveTask(
                taskId: Int,
                displayId: Int,
            ): Boolean = move(taskId, displayId)

            override fun watchMainScreen(windowMs: Int): Int = watch.watch(windowMs)

            override fun mainArrivals(sinceUptimeMs: Long): Bundle = watch.arrivals(sinceUptimeMs)
        }

    override val input: InputCaps =
        object : InputCaps {
            override fun injectStroke(
                displayId: Int,
                xs: IntArray?,
                ys: IntArray?,
                times: LongArray?,
            ): Boolean = injector.stroke(displayId, xs, ys, times)

            override fun injectKey(
                displayId: Int,
                keyCode: Int,
            ): Boolean = injector.key(displayId, keyCode)
        }

    override val system: SystemCaps =
        object : SystemCaps {
            override fun setAudioMuted(
                pkg: String?,
                muted: Boolean,
            ): Boolean = ops.setAudioMuted(pkg, muted)

            override fun grantSelf(permission: String?): Int = ops.grantSelf(permission)
        }

    override fun probe(): Bundle = prober.probe()

    /** The main screen and the agent's own displays are the only ones a task may be on. */
    private fun top(displayId: Int): Int =
        if (!virtualDisplays.owns(displayId) && displayId != TaskRow.MAIN_DISPLAY) {
            Log.w(TAG, "topTask refused: display $displayId is neither ours nor the main screen")
            -1
        } else {
            try {
                taskTable.topTask(displayId)
            } catch (e: CapabilityUnavailable) {
                Log.e(TAG, "topTask failed", e)
                -1
            }
        }

    private fun move(
        taskId: Int,
        displayId: Int,
    ): Boolean {
        val refusal =
            try {
                ownership.refuse(taskId, displayId, taskTable.rows(), virtualDisplays.ids(), watch.spilled())
            } catch (e: CapabilityUnavailable) {
                Log.e(TAG, "moveTask failed", e)
                "the task list is unreadable"
            }
        return if (refusal != null) {
            Log.w(TAG, "moveTask refused: $refusal")
            false
        } else {
            // A task handed to the user stays ours, so it can be taken back.
            ownership.remember(taskId)
            taskTable.moveToDisplay(taskId, displayId)
        }
    }

    override fun releaseAll(): String {
        val unmuted = ops.unmuteAll()
        val released = virtualDisplays.releaseAll()
        ownership.clear()
        return "$released display(s), $unmuted mute(s)"
    }

    companion object {
        private const val TAG = "PhoneAgentHelper"

        /**
         * Wires the capabilities for [appPackage] (uid [appUid]) and restores mutes an earlier
         * helper may have left behind.
         */
        fun create(
            context: Context,
            appPackage: String,
            appUid: Int,
        ): AndroidCapabilities = AndroidCapabilities(context, appPackage, appUid).apply { ops.recoverMutes() }

        private const val PER_USER_RANGE = 100_000
    }
}

/**
 * The capabilities of a helper that could not reach the framework: every call reports
 * "not implemented" with the reason, so the app learns why instead of seeing a silent failure.
 */
internal class NoCapabilities(
    private val reason: String,
) : Capabilities {
    override val displays: DisplayCaps =
        object : DisplayCaps {
            override fun createDisplay(
                surface: Surface?,
                width: Int,
                height: Int,
                dpi: Int,
            ): Int {
                Log.w(TAG, "createDisplay unavailable: $reason")
                return DisplayResult.FAILED
            }

            override fun releaseDisplay(displayId: Int) = Unit
        }

    override val tasks: TaskCaps =
        object : TaskCaps {
            override fun startActivity(
                displayId: Int,
                intent: Intent?,
                userId: Int,
                cold: Boolean,
            ): Int = StartResult.ERROR

            override fun topTask(displayId: Int): Int = -1

            override fun moveTask(
                taskId: Int,
                displayId: Int,
            ): Boolean = false

            override fun watchMainScreen(windowMs: Int): Int = -1

            override fun mainArrivals(sinceUptimeMs: Long): Bundle =
                throw PrivilegedError.notImplemented("mainArrivals ($reason)")
        }

    override val input: InputCaps =
        object : InputCaps {
            override fun injectStroke(
                displayId: Int,
                xs: IntArray?,
                ys: IntArray?,
                times: LongArray?,
            ): Boolean = false

            override fun injectKey(
                displayId: Int,
                keyCode: Int,
            ): Boolean = false
        }

    override val system: SystemCaps =
        object : SystemCaps {
            override fun setAudioMuted(
                pkg: String?,
                muted: Boolean,
            ): Boolean = false

            override fun grantSelf(permission: String?): Int = GrantResult.FAILED
        }

    override fun probe(): Bundle = throw PrivilegedError.notImplemented("probe ($reason)")

    override fun releaseAll(): String = "nothing (capabilities unavailable: $reason)"

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
