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

            keyguard.locked() != false -> {
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
 * The three ways to put an activity on a display, in order of preference: the hidden
 * `startActivityAsUser` with `ActivityOptions.setLaunchDisplayId`, then `am start --display` with
 * the component, then with the action. [Launcher] has already decided that starting is allowed.
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
        val started =
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
                    ) as Int
                }
            } catch (e: CapabilityUnavailable) {
                Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", e)
                return amStart(displayId, intent)
            }
        if (started < 0) Log.w(TAG, "startActivityAsUser($intent) -> $started")
        return started >= 0
    }

    private fun amStart(
        displayId: Int,
        intent: Intent,
    ): Boolean {
        val target = intent.component?.flattenToShortString() ?: return amStartAction(displayId, intent)
        return commands.run("am", "start", "--display", "$displayId", "-n", target).ok
    }

    private fun amStartAction(
        displayId: Int,
        intent: Intent,
    ): Boolean {
        val action = intent.action ?: return false
        val arguments =
            buildList {
                addAll(listOf("am", "start", "--display", "$displayId", "-a", action))
                intent.dataString?.let { addAll(listOf("-d", it)) }
                intent.type?.let { addAll(listOf("-t", it)) }
                intent.`package`?.let { addAll(listOf("-p", it)) }
            }
        return commands.run(arguments).ok
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

/**
 * Whether the phone is locked, from `IWindowManager.isKeyguardLocked()`; null when that cannot be
 * read, which callers treat as locked. `KeyguardManager` is not used: it answers false when the
 * call to the window manager fails, and `isDeviceLocked` is about credentials, not the lock screen.
 */
internal class Keyguard(
    private val bridge: SystemBridge,
) {
    fun locked(): Boolean? =
        try {
            val wm = bridge.windowManager
            reflecting("IWindowManager.isKeyguardLocked") {
                bridge.method(wm, "isKeyguardLocked", 0).invoke(wm) as? Boolean
                    ?: throw CapabilityUnavailable("IWindowManager.isKeyguardLocked returned no boolean")
            }
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "cannot read the lock state", e)
            null
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
