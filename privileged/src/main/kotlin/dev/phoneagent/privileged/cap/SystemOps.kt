/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `setAudioMuted` /
 * `recoverMuted` / `appops`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: grantSelf lives next to it with its own allow-list and a
 * read-back, and the ledger is restored at startup from a file named after the app's uid.
 */
package dev.phoneagent.privileged.cap

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.phoneagent.privileged.GrantResult
import java.io.File

/**
 * The two app-wide changes the helper makes for the agent: muting an app while it runs on a
 * background display (research §2.4 step 8) and granting the app `WRITE_SECURE_SETTINGS` so
 * onboarding needs one manual step (phase1-architecture D4).
 *
 * Both use a platform command with arguments this process builds; see [ShellCommands] for why
 * there is no general "run a command" path. Every mute is written to a ledger, so a helper that is
 * killed while apps are muted does not leave them silent.
 */
internal class SystemOps(
    private val context: Context,
    private val appPackage: String,
    private val appUser: Int,
    stateDir: File = File("/data/local/tmp"),
) {
    private val lock = Any()
    private val ledger = AudioMuteLedger(MuteFile(File(stateDir, "phoneagent_audio_mute_$appUser")))

    /** Mutes or unmutes [pkg] through the `PLAY_AUDIO` app-op; false when the op could not be written. */
    fun setAudioMuted(
        pkg: String?,
        muted: Boolean,
    ): Boolean {
        if (pkg == null) return false
        return synchronized(lock) { if (muted) mute(pkg) else unmute(pkg) }
    }

    private fun mute(pkg: String): Boolean {
        if (pkg !in ledger) {
            val before = appops("get", pkg)
            // Without the earlier mode there is nothing to restore later, so do not mute at all.
            if (!before.ok) return false
            ledger.put(pkg, AudioMuteLedger.parseMode(before.output))
        }
        return appops("set", pkg, "ignore").ok
    }

    private fun unmute(pkg: String): Boolean {
        val restored = appops("set", pkg, AudioMuteLedger.restoreMode(ledger[pkg])).ok
        if (restored) ledger.remove(pkg)
        return restored
    }

    /** Unmutes everything this helper muted; returns how many packages were restored. */
    fun unmuteAll(): Int =
        synchronized(lock) {
            val pending = ledger.packages()
            ledger.restoreAll { pkg, mode -> appops("set", pkg, mode).ok }
            pending.size - ledger.packages().size
        }

    /** Restores mutes left behind by an earlier helper (its process may have been killed). */
    fun recoverMutes() {
        val left = synchronized(lock) { ledger.packages() }
        if (left.isEmpty()) return
        Log.i(TAG, "restoring audio for $left left muted by an earlier helper")
        unmuteAll()
    }

    private fun appops(
        verb: String,
        pkg: String,
        mode: String? = null,
    ): CommandResult =
        ShellCommands.run(listOf("appops", verb, "--user", "$appUser", pkg, PLAY_AUDIO) + listOfNotNull(mode))

    /**
     * Grants [permission] to the app the helper serves; see [GrantSpec] for what is allowed.
     * The grant is read back, so a command that reports success but changes nothing still fails.
     */
    fun grantSelf(permission: String?): Int =
        if (!GrantSpec.isAllowed(permission)) {
            Log.w(TAG, "grantSelf refused: $permission is not grantable by the helper")
            GrantResult.NOT_ALLOWED
        } else {
            grant(checkNotNull(permission))
        }

    private fun grant(permission: String): Int {
        if (!holds(permission)) {
            ShellCommands.run("pm", "grant", "--user", "$appUser", appPackage, permission)
        }
        return if (holds(permission)) {
            Log.i(TAG, "$appPackage holds $permission")
            GrantResult.GRANTED
        } else {
            Log.w(TAG, "$appPackage still does not hold $permission")
            GrantResult.FAILED
        }
    }

    private fun holds(permission: String): Boolean =
        context.packageManager.checkPermission(permission, appPackage) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val PLAY_AUDIO = "PLAY_AUDIO"
    }
}
