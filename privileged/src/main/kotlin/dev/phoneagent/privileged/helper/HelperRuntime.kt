/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperProcess.kt, `Helper.deliver`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the provider call moved to ProviderHandoff and the extras
 * to HandoffBundles, accepted replies carry the resume nonce, each acceptance is a numbered
 * session, the lifecycle decisions moved to HelperLifecycle and LifecycleDriver.
 */
package dev.phoneagent.privileged.helper

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.phoneagent.privileged.cap.AndroidCapabilities
import dev.phoneagent.privileged.cap.Capabilities
import dev.phoneagent.privileged.cap.CapabilityUnavailable
import dev.phoneagent.privileged.cap.NoCapabilities
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffContract
import dev.phoneagent.privileged.handoff.HelperOffer
import dev.phoneagent.privileged.helper.HelperLifecycle.Event
import dev.phoneagent.privileged.helper.HelperLifecycle.Outcome
import kotlin.system.exitProcess

/**
 * Wires [LifecycleDriver] to the real process: the Binder handoff, the uid watcher, the package
 * manager and `exitProcess`. Everything runs on [handler]'s thread.
 */
internal class HelperRuntime(
    context: Context,
    private val pinned: PinnedApp,
    private val packages: AppPackages,
    private val handler: Handler,
    launchNonce: String,
    args: HelperArgs,
) : LifecycleDriver.Host {
    private val capabilities = capabilitiesFor(context, pinned)
    private val core = PrivilegedCore(pinned.uid, capabilities) { driver.post(Event.ShutdownRequested) }
    private val driver =
        LifecycleDriver(HelperLifecycle(launchNonce, args.orphanGraceMs, args.packageCheckMs), handler, this)
    private val handoff = ProviderHandoff(HandoffContract.authority(pinned.packageName), pinned.packageName, pinned.uid)
    private val appProcess = AppProcessWatcher(context, handler, pinned.uid) { driver.dispatch(Event.AppStarted) }
    private var sessions = 0

    /** Starts watching the app and makes the first delivery. */
    fun start() {
        handler.post {
            appProcess.start()
            driver.dispatch(Event.Start)
        }
    }

    override fun appRunning(): Boolean = appProcess.isRunning()

    override fun appGenuine(): Boolean = pinned.matches(packages.read(pinned.packageName))

    override fun releaseAll() {
        core.releaseAll()
    }

    override fun exit(
        code: Int,
        why: String,
    ) {
        exitProcess(code)
    }

    override fun deliver(nonce: String): Outcome {
        if (!appGenuine()) {
            return Outcome.Rejected("${pinned.packageName} is missing or not signed with the pinned certificate")
        }
        val claims =
            HelperOffer(Process.myUid(), Process.myPid(), hasBinder = true, nonce, pinned.apkPath, pinned.versionCode)
        val extras = HandoffBundles.offer(core, claims)
        return when (val result = handoff.call(HandoffContract.METHOD_ATTACH, extras)) {
            is ProviderHandoff.Result.Unreachable -> {
                Outcome.Failed(result.cause)
            }

            is ProviderHandoff.Result.WrongOwner -> {
                Outcome.Rejected(result.description)
            }

            is ProviderHandoff.Result.Reply -> {
                when (val reply = HandoffBundles.readReply(result.bundle)) {
                    is HandoffBundles.Reply.Rejected -> Outcome.Rejected(reply.reason)
                    is HandoffBundles.Reply.Accepted -> attached(reply)
                }
            }
        }
    }

    private fun attached(reply: HandoffBundles.Reply.Accepted): Outcome {
        val session = ++sessions
        val died = IBinder.DeathRecipient { driver.postAppDied(session) }
        try {
            reply.token.linkToDeath(died, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "app died during the handoff", e)
            driver.postAppDied(session)
        }
        Log.i(TAG, "handed off to ${pinned.packageName} (session $session)")
        return Outcome.Accepted(session, reply.nextNonce)
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /**
         * The capabilities of this device, or [NoCapabilities] when the framework internals the
         * helper needs are missing: the handoff still works, and `probe()` reports the reason.
         */
        fun capabilitiesFor(
            context: Context,
            pinned: PinnedApp,
        ): Capabilities =
            try {
                AndroidCapabilities.create(context, pinned.packageName, pinned.uid)
            } catch (e: CapabilityUnavailable) {
                Log.e(TAG, "the privileged capabilities are unavailable on this device", e)
                NoCapabilities(e.message.orEmpty())
            }
    }
}
