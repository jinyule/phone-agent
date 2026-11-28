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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's end of the helper connection: admits helpers that hand off through
 * [HelperHandoffProvider] (checks in [HandoffAdmission]), holds the verified Binder and follows
 * its death. An admitted helper replaces the previous one, which is told to shut down as the last
 * step before the reply goes out.
 *
 * This client cannot know whether a reply arrived: the Binder framework writes it after [offer]
 * returns. If this process dies after saving an admission and before the reply goes out, the
 * admitted helper never gets its next nonce. A resuming helper can still come back with the nonce
 * it presented; a helper on its first handoff cannot, its one-time nonce is used up. Then the
 * helper that held the resume nonce current before that admission was saved, and was not told to
 * stop, can hand off, whether or not it is connected to this process. Both hold only until another
 * admission is saved. A helper that meets neither is refused ([HandoffReject.BAD_NONCE]), releases
 * and exits; unless another helper connects, a new launch is needed: a known gap
 * (ADR 2026-11-01-privileged-handoff, decision 3), pinned by HandoffCrashTest.
 *
 * Helpers are admitted one at a time: [offer] holds a lock from the decision until the replaced
 * helper was told to shut down, so no two admissions in this process are between their save and
 * that step at once. The lock does not cover a reply still on its way after [offer] returned. A
 * helper this process told to shut down is refused ([HandoffReject.BAD_NONCE]) if it hands off
 * again, until its Binder dies; a restarted process does not know it, so there the old helper may
 * come back once with the resume nonce it holds, and the newer helper is then refused when it
 * resumes.
 *
 * Listeners and `onLaunchNeeded` may run inside that lock. They must not wait for another handoff
 * (in-app launches, WP13, start the helper asynchronously): one they wait for on another thread
 * would deadlock, and one they start on their own thread fails with [IllegalStateException].
 *
 * Thread-safe. State changes are queued under the internal lock, in the order they happen, and
 * delivered outside it, one at a time: by the thread that caused the change, or by the thread that
 * is already delivering. So listeners see every change once and in order, and the last state they
 * received is [state]. `onLaunchNeeded` runs after the listeners, and only if that NeedsLaunch is
 * still the current state (a helper admitted meanwhile makes the request moot). A listener or
 * `onLaunchNeeded` that throws a RuntimeException is logged and skipped for that change: the
 * others still receive it, later changes are still delivered, and a handoff that caused the change
 * still replies.
 */
class HelperClient internal constructor(
    private val nonces: HandoffNonces,
    appBuild: () -> AppBuild,
    private val onLaunchNeeded: (HelperState.NeedsLaunch) -> Unit = ::logLaunchNeeded,
) {
    private val lock = Any()

    /** Held through a whole [offer]; taken before [lock], never inside it. */
    private val admitting = Any()
    private val admission = HandoffAdmission(nonces, appBuild)

    /**
     * Binders of helpers this process told to shut down; guarded by [lock]. Strong until the Binder
     * dies: a remote Binder keeps arriving as the same proxy object only while that proxy is held.
     */
    private val retired: MutableSet<IBinder> = Collections.newSetFromMap(IdentityHashMap())

    /** How many Binders of replaced helpers are held, for tests. */
    internal val retiredHelpers: Int get() = synchronized(lock) { retired.size }

    /** Lives as long as this process; the helper watches its death. */
    private val token: IBinder = Binder()
    private val listeners = CopyOnWriteArrayList<(HelperState) -> Unit>()
    private var connection: Connection? = null

    /** Changes not delivered yet, oldest first; guarded by [lock]. */
    private val undelivered = ArrayDeque<HelperState>()

    /** Some thread is delivering [undelivered]; guarded by [lock]. */
    private var delivering = false

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

    /** Calls [listener] on every state change until the returned handle is closed. */
    fun addListener(listener: (HelperState) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * One-time nonce for a helper this app launches itself (in-app ADB); pass it on the helper's
     * stdin. Throws [IllegalStateException] with the reason when the device does not report its
     * boot ([HandoffNonces.BOOT_UNKNOWN]).
     */
    fun issueLaunchNonce(): String = nonces.issueLaunch()

    /**
     * `attach` from [HelperHandoffProvider]: admits or rejects the offered helper and returns the
     * reply. Waits for a handoff in progress; throws [IllegalStateException] when called from a
     * listener or `onLaunchNeeded` of one (see the class comment).
     */
    fun offer(
        extras: Bundle?,
        callingUid: Int,
        callingPid: Int,
    ): Bundle {
        check(!Thread.holdsLock(admitting)) { "a handoff cannot start inside the delivery of another one" }
        synchronized(admitting) {
            val (reply, replaced) =
                synchronized(lock) {
                    if (HandoffBundles.binder(extras)?.let(retired::contains) == true) {
                        Log.w(TAG, "helper $callingPid was told to shut down; it cannot hand off again")
                        HandoffBundles.rejected(HandoffReject.BAD_NONCE) to null
                    } else {
                        when (val decision = admission.decide(extras, callingUid, callingPid)) {
                            is HandoffAdmission.Decision.Rejected -> HandoffBundles.rejected(decision.reason) to null
                            is HandoffAdmission.Decision.Stale -> stale(decision.reason, callingPid) to null
                            is HandoffAdmission.Decision.Admitted -> connect(decision, callingPid)
                        }
                    }
                }
            deliverChanges()
            replaced?.let(::retire)
            return reply
        }
    }

    /**
     * `arm` from the shell: [sha256Hex] commits to the nonce a computer-side script will send. A
     * refusal carries its reason in [HandoffContract.KEY_REASON], which the script prints.
     */
    fun arm(
        sha256Hex: String?,
        callingUid: Int,
    ): Bundle {
        val refusal = if (callingUid == HandoffContract.SHELL_UID) nonces.arm(sha256Hex) else "only the shell may arm"
        Log.i(
            TAG,
            refusal?.let { "arm refused for uid $callingUid: $it" } ?: "armed a nonce for a shell-started helper",
        )
        return Bundle().apply {
            putBoolean(HandoffContract.KEY_ACCEPTED, refusal == null)
            refusal?.let { putString(HandoffContract.KEY_REASON, it) }
        }
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
    ): Bundle {
        if (connection == null) change(HelperState.NeedsLaunch(HelperState.LaunchReason.Stale(reason), pid))
        return HandoffBundles.rejected(reason)
    }

    /** The reply, and the connection the admitted helper replaces (to [retire] once the lock is released). */
    private fun connect(
        admitted: HandoffAdmission.Decision.Admitted,
        pid: Int,
    ): Pair<Bundle, Connection?> {
        val binder = admitted.binder
        val death = IBinder.DeathRecipient { onHelperDied(binder) }
        try {
            binder.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "helper $pid died during the handoff", e)
            return HandoffBundles.rejected(HandoffReject.BINDER_ELSEWHERE) to null
        }
        val nextNonce = admission.commit(admitted)
        return if (nextNonce != null) {
            val replaced = connection?.takeIf { it.core.asBinder() !== binder }
            connection = Connection(admitted.core, pid, death)
            change(HelperState.Connected(admitted.core, pid))
            Log.i(TAG, "helper pid $pid admitted")
            HandoffBundles.accepted(nextNonce, token) to replaced
        } else {
            binder.unlinkToDeath(death, 0)
            Log.w(TAG, "helper $pid: its nonce expired or was replaced during the handoff")
            HandoffBundles.rejected(HandoffReject.BAD_NONCE) to null
        }
    }

    private fun onHelperDied(binder: IBinder) {
        val pid =
            synchronized(lock) {
                val current = connection?.takeIf { it.core.asBinder() === binder } ?: return
                connection = null
                change(HelperState.NeedsLaunch(HelperState.LaunchReason.HelperDied, current.pid))
                current.pid
            }
        Log.w(TAG, "helper pid $pid died")
        deliverChanges()
    }

    /**
     * A newer helper was admitted: stop following the old one and tell it to release and exit.
     * Runs outside [lock], inside [admitting]; a death of [previous] meanwhile is ignored, it is no
     * longer [connection]. From here on [offer] refuses it, until its Binder dies.
     */
    private fun retire(previous: Connection) {
        val binder = previous.core.asBinder()
        val forget = IBinder.DeathRecipient { synchronized(lock) { retired -= binder } }
        synchronized(lock) { retired += binder }
        try {
            binder.linkToDeath(forget, 0)
        } catch (e: RemoteException) {
            Log.i(TAG, "replaced helper ${previous.pid} died before it was told to stop", e)
            synchronized(lock) { retired -= binder }
        }
        binder.unlinkToDeath(previous.death, 0)
        try {
            previous.core.shutdown()
        } catch (e: RemoteException) {
            Log.i(TAG, "replaced helper ${previous.pid} is already gone", e)
        }
    }

    /** Sets [state] and queues the change for [deliverChanges]. Hold [lock]. */
    private fun change(next: HelperState) {
        state = next
        undelivered.addLast(next)
    }

    /** Delivers queued changes in order, unless another thread already is. Do not hold [lock]. */
    private fun deliverChanges() {
        synchronized(lock) {
            if (delivering) return
            delivering = true
        }
        var drained = false
        try {
            while (!drained) {
                // Seeing the queue empty and giving up the delivery is one step: a change queued
                // right after it is delivered by the thread that queued it.
                val next =
                    synchronized(lock) {
                        undelivered.removeFirstOrNull().also {
                            drained = it == null
                            if (drained) delivering = false
                        }
                    }
                if (next != null) {
                    listeners.forEach { listener -> isolated("a state listener") { listener(next) } }
                    if (next is HelperState.NeedsLaunch && state === next) {
                        isolated("onLaunchNeeded") { onLaunchNeeded(next) }
                    }
                }
            }
        } finally {
            if (!drained) synchronized(lock) { delivering = false }
        }
    }

    companion object {
        private const val TAG = "PhoneAgentHelperClient"

        /**
         * Runs one receiver of a change; what it throws is logged and stops neither the delivery nor the
         * caller: a receiver's bug must not undo a committed handoff or starve the other receivers.
         */
        @Suppress("TooGenericExceptionCaught") // Receivers are app code of any kind; see above.
        private fun isolated(
            what: String,
            receive: () -> Unit,
        ) {
            try {
                receive()
            } catch (e: RuntimeException) {
                Log.e(TAG, "$what threw; the change still goes to the others", e)
            }
        }

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

        private val bootUnknownLogged = AtomicBoolean(false)

        /** `Settings.Global.BOOT_COUNT`, or null when this device does not report it (logged once per process). */
        private fun bootCount(app: Context): Int? {
            val count = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (count < 0 && bootUnknownLogged.compareAndSet(false, true)) Log.e(TAG, HandoffNonces.BOOT_UNKNOWN)
            return count.takeIf { it >= 0 }
        }

        private fun logLaunchNeeded(state: HelperState.NeedsLaunch) {
            // The in-app ADB launcher (WP13) replaces this; until then a developer runs the script.
            Log.w(TAG, "a new helper is needed (${state.reason}); run scripts/start-helper.sh")
        }
    }
}
