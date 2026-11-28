package dev.phoneagent.privileged.helper

import android.os.Handler
import android.util.Log
import dev.phoneagent.privileged.helper.HelperLifecycle.Effect
import dev.phoneagent.privileged.helper.HelperLifecycle.Event
import dev.phoneagent.privileged.helper.HelperLifecycle.Outcome

/**
 * Runs [HelperLifecycle] on [handler]'s thread: every event, effect and delivery happens there,
 * in order. Other threads (death notifications, `shutdown()`, the uid listener) only [post].
 *
 * Timers sample the app when they fire, not when they are set: the grace timer asks [Host.appRunning]
 * and [Host.appGenuine], the package check asks [Host.appGenuine].
 */
internal class LifecycleDriver(
    private val lifecycle: HelperLifecycle,
    private val handler: Handler,
    private val host: Host,
) {
    /** What the lifecycle acts on. Every method is called on the handler thread. */
    interface Host {
        /** Hands the Binder over with [nonce]. */
        fun deliver(nonce: String): Outcome

        /** A process of the app's uid exists now. */
        fun appRunning(): Boolean

        /** The package is still installed with the pinned uid and certificate. */
        fun appGenuine(): Boolean

        /** `releaseAll()` on the core. A RuntimeException is logged and does not stop the lifecycle. */
        fun releaseAll()

        /** Ends the process with [code]; does not return in production. */
        fun exit(
            code: Int,
            why: String,
        )
    }

    /** Current lifecycle state; read it on the handler thread. */
    val state: HelperLifecycle.State
        get() = lifecycle.state

    /** Feeds [event] on the handler thread. */
    fun post(event: Event) {
        handler.post { dispatch(event) }
    }

    /** The app of [session] died; whether it is still genuine is checked on the handler thread. */
    fun postAppDied(session: Int) {
        handler.post { dispatch(Event.AppDied(session, host.appGenuine())) }
    }

    /** Applies [event] and runs its effects. Handler thread only. */
    fun dispatch(event: Event) {
        val before = lifecycle.state
        val effects = lifecycle.handle(event)
        if (before != lifecycle.state || effects.isNotEmpty()) Log.i(TAG, "$event: $before -> ${lifecycle.state}")
        effects.forEach(::run)
    }

    // TooGenericExceptionCaught: releaseAll may fail in any way; the Exit after it must still run.
    @Suppress("TooGenericExceptionCaught")
    private fun run(effect: Effect) {
        when (effect) {
            is Effect.Deliver -> {
                dispatch(Event.Delivered(host.deliver(effect.nonce)))
            }

            is Effect.ScheduleRetry -> {
                handler.postDelayed({ dispatch(Event.RetryDue(effect.attempt)) }, effect.delayMs)
            }

            is Effect.ScheduleGrace -> {
                Log.i(TAG, "app died; releasing its resources in ${effect.delayMs} ms unless it comes back")
                handler.postDelayed(
                    { dispatch(Event.GraceExpired(effect.session, host.appRunning(), host.appGenuine())) },
                    effect.delayMs,
                )
            }

            is Effect.SchedulePackageCheck -> {
                handler.postDelayed({ dispatch(Event.PackageChecked(effect.check, host.appGenuine())) }, effect.delayMs)
            }

            Effect.ReleaseAll -> {
                try {
                    host.releaseAll()
                } catch (e: RuntimeException) {
                    // The effects after it (an Exit, a Deliver) must still run.
                    Log.e(TAG, "releaseAll failed; carrying on with the lifecycle", e)
                }
            }

            is Effect.Exit -> {
                Log.i(TAG, "exiting with ${effect.code}: ${effect.why}")
                host.exit(effect.code, effect.why)
            }
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
