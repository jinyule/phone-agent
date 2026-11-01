package dev.phoneagent.privileged.helper

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffContract
import dev.phoneagent.privileged.handoff.HelperOffer
import dev.phoneagent.privileged.helper.HelperLifecycle.Effect
import dev.phoneagent.privileged.helper.HelperLifecycle.Event
import dev.phoneagent.privileged.helper.HelperLifecycle.Outcome
import kotlin.system.exitProcess

/**
 * Runs [HelperLifecycle] on [handler]'s thread: every event, effect and delivery happens there,
 * in order. Binder threads (death notifications, `shutdown()`, the uid listener) only post events.
 */
internal class HelperRuntime(
    context: Context,
    private val pinned: PinnedApp,
    private val packages: AppPackages,
    private val handler: Handler,
    launchNonce: String,
    orphanGraceMs: Long,
) {
    private val core = PrivilegedCore(pinned.uid) { post(Event.ShutdownRequested) }
    private val lifecycle = HelperLifecycle(launchNonce, orphanGraceMs)
    private val handoff = ProviderHandoff(HandoffContract.authority(pinned.packageName), pinned.packageName, pinned.uid)
    private val appProcess = AppProcessWatcher(context, handler, pinned.uid) { dispatch(Event.AppStarted) }
    private var sessions = 0

    /** Starts watching the app and makes the first delivery. */
    fun start() {
        post {
            appProcess.start()
            dispatch(Event.Start)
        }
    }

    private fun post(event: Event) {
        handler.post { dispatch(event) }
    }

    private fun post(block: () -> Unit) {
        handler.post(block)
    }

    private fun dispatch(event: Event) {
        val before = lifecycle.state
        val effects = lifecycle.handle(event)
        if (before != lifecycle.state || effects.isNotEmpty()) Log.i(TAG, "$event: $before -> ${lifecycle.state}")
        effects.forEach(::run)
    }

    private fun run(effect: Effect) {
        when (effect) {
            is Effect.Deliver -> {
                dispatch(Event.Delivered(deliver(effect.nonce)))
            }

            is Effect.ScheduleRetry -> {
                handler.postDelayed({ dispatch(Event.RetryDue(effect.attempt)) }, effect.delayMs)
            }

            is Effect.ScheduleGrace -> {
                Log.i(TAG, "app died; releasing its resources in ${effect.delayMs} ms unless it comes back")
                handler.postDelayed(
                    { dispatch(Event.GraceExpired(effect.session, appProcess.isRunning())) },
                    effect.delayMs,
                )
            }

            Effect.ReleaseAll -> {
                core.releaseAll()
            }

            is Effect.Exit -> {
                Log.i(TAG, "exiting with ${effect.code}: ${effect.why}")
                exitProcess(effect.code)
            }
        }
    }

    private fun deliver(nonce: String): Outcome {
        if (!pinned.matches(packages.read(pinned.packageName))) {
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
        val died = IBinder.DeathRecipient { post { dispatch(Event.AppDied(session, appGenuine())) } }
        try {
            reply.token.linkToDeath(died, 0)
        } catch (e: RemoteException) {
            Log.w(TAG, "app died during the handoff", e)
            post { dispatch(Event.AppDied(session, appGenuine())) }
        }
        Log.i(TAG, "handed off to ${pinned.packageName} (session $session)")
        return Outcome.Accepted(session, reply.nextNonce)
    }

    private fun appGenuine(): Boolean = pinned.matches(packages.read(pinned.packageName))

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
