/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `start` / `restart` /
 * `startView` / `activitiesFor`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: one entry point takes the intent the app built, URI-grant
 * flags are stripped, the launch gate is a pure function, and started tasks are recorded as the
 * agent's own.
 */
package dev.phoneagent.privileged.cap

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.phoneagent.privileged.StartResult

/**
 * Starts activities on an agent display (research §2.4 step 2).
 *
 * What the helper guarantees, whatever the app asks for: the target display is one of its own, the
 * app is the copy installed for the helper's user, an app the user has visible on the main screen
 * is never started, a cold start never kills a process whose tasks live elsewhere, the intent can
 * neither hand out URI permissions nor carry another intent ([NestedIntents]), and only an
 * activity the app's own uid could start is started ([LaunchTargets]). Which intents the agent
 * may send at all is decided by the app's PolicyEngine (phase1-architecture §6); the helper is not
 * a second policy.
 */
internal class Launcher(
    platform: Platform,
    context: Context,
    app: ServedApp,
    private val displays: VirtualDisplays,
    private val tasks: TaskTable,
    private val ownership: TaskOwnership,
) {
    private val myUser = app.user
    private val commands = platform.commands
    private val starter = ActivityStarter(platform.bridge, commands)
    private val keyguard = Keyguard(platform.bridge)
    private val targets = LaunchTargets(context, app)

    /** Starts [intent] on [displayId]; see [StartResult]. */
    fun start(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int {
        val refusal = refuse(displayId, intent, userId)
        return when {
            refusal != null -> {
                Log.w(TAG, "startActivity refused: $refusal")
                StartResult.REFUSED
            }

            keyguard.read() != LockState.Known(locked = false) -> {
                Log.w(TAG, "startActivity refused: the phone is locked, or its lock state is unknown")
                StartResult.LOCKED
            }

            else -> {
                attempt(displayId, sanitize(checkNotNull(intent)), cold)
            }
        }
    }

    /** Why the helper will not even look at this request, or null. */
    private fun refuse(
        displayId: Int,
        intent: Intent?,
        userId: Int,
    ): String? =
        when {
            intent == null -> "no intent"
            !displays.owns(displayId) -> "display $displayId is not one of the agent's"
            userId != myUser && userId != USER_CURRENT -> "user $userId is not the helper's user $myUser"
            else -> NestedIntents.find(intent)
        }

    private fun attempt(
        displayId: Int,
        intent: Intent,
        cold: Boolean,
    ): Int =
        try {
            launch(displayId, intent, cold)
        } catch (e: CapabilityUnavailable) {
            Log.e(TAG, "startActivity failed", e)
            StartResult.ERROR
        }

    /**
     * Drops flags that would make the helper's uid grant someone access to its URIs, and makes the
     * activity the root of a new task on the agent display.
     */
    private fun sanitize(intent: Intent): Intent {
        val stripped =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val copy = Intent(intent)
        copy.flags = intent.flags and stripped.inv() or Intent.FLAG_ACTIVITY_NEW_TASK
        if (intent.flags and stripped != 0) Log.i(TAG, "dropped URI-grant flags from $intent")
        return copy
    }

    private fun launch(
        displayId: Int,
        intent: Intent,
        cold: Boolean,
    ): Int =
        when (val target = targets.check(intent)) {
            is LaunchTargets.Target.Refused -> {
                Log.w(TAG, "not starting on display $displayId: ${target.reason}")
                target.result
            }

            is LaunchTargets.Target.Allowed -> {
                // Start exactly the activity that was checked, not whatever resolves later.
                gated(displayId, Intent(intent).setComponent(target.activity), cold)
            }
        }

    private fun gated(
        displayId: Int,
        intent: Intent,
        cold: Boolean,
    ): Int {
        val target = checkNotNull(intent.component).packageName
        val snapshot = tasks.rows()
        val gate = LaunchGate.refuse(snapshot, listOf(target), displayId, displays.ids(), myUser)
        if (gate != null) {
            Log.i(TAG, "not starting $target on display $displayId: StartResult $gate")
            return gate
        }
        return started(displayId, intent, cold, snapshot, target)
    }

    private fun started(
        displayId: Int,
        intent: Intent,
        cold: Boolean,
        snapshot: List<TaskRow>,
        target: String,
    ): Int {
        val coldDone = cold && restart(snapshot, target, displayId)
        if (!starter.start(displayId, intent)) return StartResult.ERROR
        ownership.remember(tasks.topTask(displayId))
        return if (cold && !coldDone) StartResult.COLD_IGNORED else StartResult.OK
    }

    /**
     * Force-stops [target] so it starts from its first screen. Only when every task of the app is
     * on this display: elsewhere it is state the user or another agent task still needs.
     * Returns whether the process was actually stopped.
     */
    private fun restart(
        snapshot: List<TaskRow>,
        target: String,
        displayId: Int,
    ): Boolean {
        val decision = LaunchGate.coldStart(snapshot, target, displayId, myUser)
        if (decision == LaunchGate.ColdStart.KEEP_PROCESS) {
            Log.i(TAG, "cold start of $target downgraded: it has tasks outside display $displayId")
            return false
        }
        return commands.run("am", "force-stop", "--user", "$myUser", target).ok
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** `UserHandle.USER_CURRENT`. */
        const val USER_CURRENT = -2
    }
}

/**
 * The two ways to put an activity on a display, in order of preference: the hidden
 * `startActivityAsUser` with `ActivityOptions.setLaunchDisplayId`, then `am start --display` with
 * the intent spelled out as arguments. [Launcher] has already decided that starting is allowed.
 */
private class ActivityStarter(
    private val bridge: SystemBridge,
    private val commands: CommandRunner,
) {
    /** Starts [intent] on [displayId]; false when every path failed. */
    fun start(
        displayId: Int,
        intent: Intent,
    ): Boolean {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val atm = bridge.activityTaskManager
        val answer =
            try {
                reflecting("IActivityTaskManager.startActivityAsUser") {
                    bridge.method(atm, "startActivityAsUser", START_PARAMETERS).invoke(
                        atm,
                        null,
                        SHELL_PACKAGE,
                        null,
                        intent,
                        intent.type,
                        null,
                        null,
                        0,
                        0,
                        null,
                        options,
                        USER_CURRENT,
                    )
                }
            } catch (e: CapabilityUnavailable) {
                Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", e)
                return amStart(displayId, intent)
            }
        // It ran: an answer that is not a start result is a failure, never a reason to start again.
        val started = answer as? Int
        if (started == null || started < 0) Log.w(TAG, "startActivityAsUser($intent) -> $answer")
        return started != null && started >= 0
    }

    /**
     * `am start` with the whole intent: component, action (unless MAIN), data, type, categories
     * and flags, so it starts what `startActivityAsUser` would have. Extras have no faithful
     * spelling here yet, so an intent with extras is not started this way (false).
     */
    private fun amStart(
        displayId: Int,
        intent: Intent,
    ): Boolean {
        val component = intent.component
        val action = intent.action?.takeUnless { it == Intent.ACTION_MAIN && component != null }
        val problem =
            when {
                intent.extras?.isEmpty == false -> "am start cannot carry its extras"
                component == null && action == null -> "it names neither a component nor an action"
                else -> null
            }
        if (problem != null) {
            Log.w(TAG, "not falling back to am start for $intent: $problem")
            return false
        }
        return commands.run(amArguments(displayId, intent, action)).ok
    }

    /** `am start` arguments for [intent], whose [action] (null for MAIN with a component) is given. */
    private fun amArguments(
        displayId: Int,
        intent: Intent,
        action: String?,
    ): List<String> =
        buildList {
            val component = intent.component
            addAll(listOf("am", "start", "--display", "$displayId"))
            component?.let { addAll(listOf("-n", it.flattenToShortString())) }
            action?.let { addAll(listOf("-a", it)) }
            intent.dataString?.let { addAll(listOf("-d", it)) }
            intent.type?.let { addAll(listOf("-t", it)) }
            intent.categories?.forEach { addAll(listOf("-c", it)) }
            if (component == null) intent.`package`?.let { addAll(listOf("-p", it)) }
            // Decimal: am reads the flags with Integer.decode, which takes no hex above 0x7fffffff.
            if (intent.flags != 0) addAll(listOf("-f", "${intent.flags}"))
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** The package name the shell context reports; the activity manager checks it against our uid. */
        const val SHELL_PACKAGE = "com.android.shell"

        /** `UserHandle.USER_CURRENT`. */
        const val USER_CURRENT = -2

        /**
         * `startActivityAsUser(caller, callingPackage, callingFeatureId, intent, resolvedType,
         * resultTo, resultWho, requestCode, flags, profilerInfo, options, userId)`.
         */
        const val START_PARAMETERS = 12
    }
}

/** What [Keyguard.read] found. `startActivity` treats anything but `Known(false)` as locked. */
internal sealed interface LockState {
    /** Read from the window manager: whether the lock screen is showing. */
    data class Known(
        val locked: Boolean,
    ) : LockState

    /** Not readable; [reason] names the entry point and the failure, as `probe()` reports it. */
    data class Unknown(
        val reason: String,
    ) : LockState
}

/**
 * Whether the phone is locked, from `IWindowManager.isKeyguardLocked()`. `startActivity` and
 * `probe()` both read it here. `KeyguardManager` is not used: it answers false when the call to
 * the window manager fails, and `isDeviceLocked` is about credentials, not the lock screen.
 */
internal class Keyguard(
    private val bridge: SystemBridge,
) {
    fun read(): LockState =
        try {
            LockState.Known(locked())
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "cannot read the lock state", e)
            LockState.Unknown(reasonFor(e))
        }

    /** Throws [CapabilityUnavailable] when the method is missing, fails, or answers no boolean. */
    private fun locked(): Boolean {
        val wm = bridge.windowManager
        return reflecting("IWindowManager.isKeyguardLocked") {
            bridge.method(wm, "isKeyguardLocked", 0).invoke(wm) as? Boolean
                ?: throw CapabilityUnavailable("IWindowManager.isKeyguardLocked returned no boolean")
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
