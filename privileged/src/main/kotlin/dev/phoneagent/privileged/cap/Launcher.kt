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

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.phoneagent.privileged.StartResult

/**
 * Starts activities on an agent display (research §2.4 step 2).
 *
 * What the helper guarantees, whatever the app asks for: the target display is one of its own, the
 * app is the copy installed for the helper's user, an app the user has visible on the main screen
 * is never started, a cold start never kills a process whose tasks live elsewhere, and the intent
 * cannot hand out URI permissions. Which intents the agent may send at all is decided by the app's
 * PolicyEngine (phase1-architecture §6); the helper is not a second policy.
 */
internal class Launcher(
    bridge: SystemBridge,
    private val context: Context,
    private val displays: VirtualDisplays,
    private val tasks: TaskTable,
    private val ownership: TaskOwnership,
    private val myUser: Int,
) {
    private val starter = ActivityStarter(bridge)

    /** Starts [intent] on [displayId]; see [StartResult]. */
    fun start(
        displayId: Int,
        intent: Intent?,
        userId: Int,
        cold: Boolean,
    ): Int =
        when (val refusal = refuse(displayId, intent, userId)) {
            null -> {
                attempt(displayId, sanitize(checkNotNull(intent)), cold)
            }

            else -> {
                Log.w(TAG, "startActivity refused: $refusal")
                StartResult.REFUSED
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
            else -> null
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
    ): Int {
        val candidates = candidates(intent)
        val snapshot = if (candidates.isEmpty()) emptyList() else tasks.rows()
        val gate =
            if (candidates.isEmpty()) {
                Log.w(TAG, "nothing can handle $intent")
                StartResult.NOT_FOUND
            } else {
                LaunchGate.refuse(snapshot, candidates, displayId, displays.ids(), myUser)
            }
        if (gate != null) {
            Log.i(TAG, "not starting $candidates on display $displayId: StartResult $gate")
            return gate
        }
        return started(displayId, intent, cold, snapshot, candidates.singleOrNull())
    }

    private fun started(
        displayId: Int,
        intent: Intent,
        cold: Boolean,
        snapshot: List<TaskRow>,
        target: String?,
    ): Int {
        val coldDone = cold && target != null && restart(snapshot, target, displayId)
        if (!starter.start(displayId, intent)) return StartResult.ERROR
        ownership.remember(tasks.topTask(displayId))
        return if (cold && !coldDone) StartResult.COLD_IGNORED else StartResult.OK
    }

    /** Packages that can handle [intent]: the named component, or whatever resolves. */
    private fun candidates(intent: Intent): List<String> {
        val component = intent.component
        val pkg = intent.`package`
        return when {
            component != null -> listOf(component.packageName)

            // A package with no matching activity may still have a launcher entry.
            pkg != null -> resolve(intent).ifEmpty { launchable(pkg) }

            else -> resolve(intent)
        }
    }

    // The helper runs as the shell user, which sees every package; manifest <queries> would not
    // apply to it anyway (app_process is not this app's process).
    @SuppressLint("QueryPermissionsNeeded")
    private fun resolve(intent: Intent): List<String> =
        reflecting("PackageManager.queryIntentActivities") {
            context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                .distinct()
        }

    /** The launcher entry of [pkg], when it has one. */
    private fun launchable(pkg: String): List<String> =
        if (context.packageManager.getLaunchIntentForPackage(pkg) != null) listOf(pkg) else emptyList()

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
        return ShellCommands.run("am", "force-stop", "--user", "$myUser", target).ok
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
        return ShellCommands.run("am", "start", "--display", "$displayId", "-n", target).ok
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
        return ShellCommands.run(arguments).ok
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
