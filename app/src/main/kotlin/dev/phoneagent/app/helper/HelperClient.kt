/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperHub.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: admission moved to HandoffAdmission, shutdown of a replaced
 * helper, armed nonces, a status call, listener-based state instead of StateFlow.
 */
package dev.phoneagent.app.helper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.handoff.AppBuild
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffContract
import dev.phoneagent.privileged.handoff.HandoffNonces
import dev.phoneagent.privileged.handoff.HandoffReject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's end of the helper connection: admits helpers that hand off through
 * [HelperHandoffProvider] (checks in [HandoffAdmission]), holds the verified Binder and follows
 * its death. An admitted helper replaces the previous one, which is told to shut down.
 *
 * Thread-safe. Listeners run on the thread that caused the change, outside the internal lock.
 */
class HelperClient internal constructor(
    private val nonces: HandoffNonces,
    appBuild: () -> AppBuild,
    private val onLaunchNeeded: (HelperState.NeedsLaunch) -> Unit = ::logLaunchNeeded,
) {
    private val lock = Any()
    private val admission = HandoffAdmission(nonces, appBuild)

    /** Lives as long as this process; the helper watches its death. */
    private val token: IBinder = Binder()
    private val listeners = CopyOnWriteArrayList<(HelperState) -> Unit>()
    private var connection: Connection? = null

    /** Current state; changes are also delivered to [addListener] listeners. */
    @Volatile
    var state: HelperState = HelperState.NotConnected
        private set

    /** The verified helper, or null when none is connected. */
    val core: IPrivilegedCore?
        get() = (state as? HelperState.Connected)?.core

    private class Connection(
        val core: IPrivilegedCore,
        val pid: Int,
        val death: IBinder.DeathRecipient,
    )

    /** A provider reply plus the state change to publish once the lock is released. */
    private class Reply(
        val bundle: Bundle,
        val changed: HelperState? = null,
    )

    /** Calls [listener] on every state change until the returned handle is closed. */
    fun addListener(listener: (HelperState) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** One-time nonce for a helper this app launches itself (in-app ADB); pass it on the helper's stdin. */
    fun issueLaunchNonce(): String = nonces.issueLaunch()

    /** `attach` from [HelperHandoffProvider]: admits or rejects the offered helper and returns the reply. */
    fun offer(
        extras: Bundle?,
        callingUid: Int,
        callingPid: Int,
    ): Bundle {
        val reply =
            synchronized(lock) {
                when (val decision = admission.decide(extras, callingUid, callingPid)) {
                    is HandoffAdmission.Decision.Rejected -> Reply(HandoffBundles.rejected(decision.reason))
                    is HandoffAdmission.Decision.Stale -> stale(decision.reason, callingPid)
                    is HandoffAdmission.Decision.Admitted -> connect(decision, callingPid)
                }
            }
        reply.changed?.let(::publish)
        return reply.bundle
    }

    /** `arm` from the shell: [sha256Hex] commits to the nonce a computer-side script will send. */
    fun arm(
        sha256Hex: String?,
        callingUid: Int,
    ): Bundle {
        val armed = callingUid == HandoffContract.SHELL_UID && nonces.arm(sha256Hex)
        Log.i(TAG, if (armed) "armed a launch nonce for a shell-started helper" else "arm refused for uid $callingUid")
        return Bundle().apply { putBoolean(HandoffContract.KEY_ACCEPTED, armed) }
    }

    /** `status` from the shell: state name and helper pid; null for any other caller. */
    fun status(callingUid: Int): Bundle? {
        if (callingUid != HandoffContract.SHELL_UID) return null
        val current = state
        val pid =
            when (current) {
                is HelperState.Connected -> current.pid
                is HelperState.NeedsLaunch -> current.lastPid
                HelperState.NotConnected -> -1
            }
        return Bundle().apply {
            putString(HandoffContract.KEY_STATE, current.name)
            putInt(HandoffContract.KEY_HELPER_PID, pid)
        }
    }

    /** A stale helper was told to release; a new one is needed unless another is connected. */
    private fun stale(
        reason: HandoffReject,
        pid: Int,
    ): Reply {
        val rejected = HandoffBundles.rejected(reason)
        if (connection != null) return Reply(rejected)
        val needs = HelperState.NeedsLaunch(HelperState.LaunchReason.Stale(reason), pid)
        state = needs
        return Reply(rejected, needs)
    }

    private fun connect(
        admitted: HandoffAdmission.Decision.Admitted,
        pid: Int,
    ): Reply {
        val binder = admitted.binder
        val death = IBinder.DeathRecipient { onHelperDied(binder) }
        try {
            binder.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "helper $pid died during the handoff", e)
            return Reply(HandoffBundles.rejected(HandoffReject.BINDER_ELSEWHERE))
        }
        val nextNonce = admission.commit(admitted)
        return if (nextNonce != null) {
            connection?.takeIf { it.core.asBinder() !== binder }?.let(::retire)
            connection = Connection(admitted.core, pid, death)
            val connected = HelperState.Connected(admitted.core, pid)
            state = connected
            Log.i(TAG, "helper pid $pid admitted")
            Reply(HandoffBundles.accepted(nextNonce, token), connected)
        } else {
            binder.unlinkToDeath(death, 0)
            Log.w(TAG, "helper $pid: its nonce expired or was replaced during the handoff")
            Reply(HandoffBundles.rejected(HandoffReject.BAD_NONCE))
        }
    }

    private fun onHelperDied(binder: IBinder) {
        val needs =
            synchronized(lock) {
                val current = connection?.takeIf { it.core.asBinder() === binder } ?: return
                connection = null
                HelperState.NeedsLaunch(HelperState.LaunchReason.HelperDied, current.pid).also { state = it }
            }
        Log.w(TAG, "helper pid ${needs.lastPid} died")
        publish(needs)
    }

    /** A newer helper was admitted: stop following the old one and tell it to release and exit. */
    private fun retire(previous: Connection) {
        previous.core.asBinder().unlinkToDeath(previous.death, 0)
        try {
            previous.core.shutdown()
        } catch (e: RemoteException) {
            Log.i(TAG, "replaced helper ${previous.pid} is already gone", e)
        }
    }

    private fun publish(changed: HelperState) {
        listeners.forEach { it(changed) }
        if (changed is HelperState.NeedsLaunch) onLaunchNeeded(changed)
    }

    companion object {
        private const val TAG = "PhoneAgentHelperClient"

        @Volatile
        private var instance: HelperClient? = null

        /** The process-wide client. Safe to call before `Application.onCreate` (the provider may be first). */
        fun of(context: Context): HelperClient =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): HelperClient {
            val build by lazy { appBuildOf(app) }
            return HelperClient(
                HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime, { bootCount(app) }),
                { build },
            )
        }

        private fun appBuildOf(app: Context): AppBuild {
            val pm = app.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(app.packageName, 0)
                }
            return AppBuild(versionCode = info.longVersionCode, apkPath = app.applicationInfo.sourceDir)
        }

        /** `Settings.Global.BOOT_COUNT`, or null (logged) when this device does not report it. */
        private fun bootCount(app: Context): Int? {
            val count = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (count < 0) Log.e(TAG, "BOOT_COUNT is unavailable; launch and armed nonces cannot match")
            return count.takeIf { it >= 0 }
        }

        private fun logLaunchNeeded(state: HelperState.NeedsLaunch) {
            // The in-app ADB launcher (WP13) replaces this; until then a developer runs the script.
            Log.w(TAG, "a new helper is needed (${state.reason}); run scripts/start-helper.sh")
        }
    }
}
