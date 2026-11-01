package dev.phoneagent.privileged.helper

/**
 * Lifecycle of the helper process, as a pure state machine (phase1-architecture §2.2):
 *
 * - deliver the Binder to the app; retry a failed delivery on [retryDelaysMs], and give up
 *   (exit) only on the first handoff;
 * - a rejection makes the helper release everything and exit;
 * - when the app process dies the helper stays: after [orphanGraceMs] it releases everything
 *   and waits for the app to come back, then hands off again with the resume nonce;
 * - resources of a dead app session are always released before the next session can start,
 *   so a late timer can never release what the new session created.
 *
 * Not thread-safe: the caller feeds events from a single thread and runs the returned effects
 * in order. `toString` of states, outcomes and effects never contains a nonce, so they can be logged.
 */
internal class HelperLifecycle(
    launchNonce: String,
    private val orphanGraceMs: Long,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    init {
        require(orphanGraceMs > 0) { "orphanGraceMs must be > 0, was $orphanGraceMs" }
        require(retryDelaysMs.isNotEmpty() && retryDelaysMs.all { it >= 0 }) {
            "retryDelaysMs must be non-empty and non-negative, was $retryDelaysMs"
        }
    }

    sealed interface State {
        /** Started, nothing delivered yet. */
        data class Created(
            val nonce: String,
        ) : State {
            override fun toString(): String = "Created"
        }

        /** A delivery with [nonce] is in flight; [first] is the handoff right after launch. */
        data class Delivering(
            val nonce: String,
            val attempt: Int,
            val first: Boolean,
        ) : State {
            override fun toString(): String = "Delivering(attempt=$attempt, first=$first)"
        }

        /** Waiting to retry delivery [attempt]. */
        data class Backoff(
            val nonce: String,
            val attempt: Int,
            val first: Boolean,
        ) : State {
            override fun toString(): String = "Backoff(attempt=$attempt, first=$first)"
        }

        /** The app holds the Binder; [nonce] is the resume nonce for the next handoff. */
        data class Attached(
            val session: Int,
            val nonce: String,
        ) : State {
            override fun toString(): String = "Attached(session=$session)"
        }

        /** The app of [session] died; its resources are kept until the grace timer fires. */
        data class Orphaned(
            val session: Int,
            val nonce: String,
        ) : State {
            override fun toString(): String = "Orphaned(session=$session)"
        }

        /** Everything released; waiting for the app process to start again. */
        data class Idle(
            val nonce: String,
        ) : State {
            override fun toString(): String = "Idle"
        }

        /** The process is exiting. */
        data object Exited : State
    }

    sealed interface Event {
        data object Start : Event

        data class Delivered(
            val outcome: Outcome,
        ) : Event

        data class RetryDue(
            val attempt: Int,
        ) : Event

        /** [appGenuine]: the package is still installed with the pinned uid and certificate. */
        data class AppDied(
            val session: Int,
            val appGenuine: Boolean,
        ) : Event

        /** [appRunning]: a process of the app's uid exists right now. */
        data class GraceExpired(
            val session: Int,
            val appRunning: Boolean,
        ) : Event

        /** A process of the app's uid appeared. */
        data object AppStarted : Event

        /** `IPrivilegedCore.shutdown()`. */
        data object ShutdownRequested : Event
    }

    sealed interface Outcome {
        /** The app took the Binder; [session] identifies this attachment for [Event.AppDied]. */
        data class Accepted(
            val session: Int,
            val nextNonce: String,
        ) : Outcome {
            override fun toString(): String = "Accepted(session=$session)"
        }

        /** The app (or the pre-delivery check) refused this helper. */
        data class Rejected(
            val reason: String,
        ) : Outcome

        /** The app could not be reached. */
        data class Failed(
            val cause: String,
        ) : Outcome
    }

    sealed interface Effect {
        /** Hand the Binder over with [nonce], then feed back [Event.Delivered]. */
        data class Deliver(
            val nonce: String,
        ) : Effect {
            override fun toString(): String = "Deliver"
        }

        /** Feed [Event.RetryDue] after [delayMs]. */
        data class ScheduleRetry(
            val attempt: Int,
            val delayMs: Long,
        ) : Effect

        /** Feed [Event.GraceExpired] for [session] after [delayMs]. */
        data class ScheduleGrace(
            val session: Int,
            val delayMs: Long,
        ) : Effect

        /** `releaseAll()` on the core. */
        data object ReleaseAll : Effect

        /** Exit the process with [code]. */
        data class Exit(
            val code: Int,
            val why: String,
        ) : Effect
    }

    var state: State = State.Created(launchNonce)
        private set

    /** Applies [event] and returns the effects to run, in order. Events that do not fit the state are ignored. */
    fun handle(event: Event): List<Effect> {
        val current = state
        if (current is State.Exited) return emptyList()
        return when (event) {
            Event.Start -> started(current)
            is Event.Delivered -> delivered(current, event.outcome)
            is Event.RetryDue -> retryDue(current, event.attempt)
            is Event.AppDied -> appDied(current, event)
            is Event.GraceExpired -> graceExpired(current, event)
            Event.AppStarted -> appStarted(current)
            Event.ShutdownRequested -> exit(EXIT_OK, "shutdown requested by the app")
        }
    }

    private fun started(current: State): List<Effect> =
        if (current is State.Created) deliver(current.nonce, 0, first = true) else emptyList()

    private fun delivered(
        current: State,
        outcome: Outcome,
    ): List<Effect> {
        if (current !is State.Delivering) return emptyList()
        return when (outcome) {
            is Outcome.Accepted -> {
                state = State.Attached(outcome.session, outcome.nextNonce)
                emptyList()
            }

            is Outcome.Rejected -> {
                exit(EXIT_OK, "handoff rejected: ${outcome.reason}")
            }

            is Outcome.Failed -> {
                failed(current, outcome)
            }
        }
    }

    private fun failed(
        current: State.Delivering,
        outcome: Outcome.Failed,
    ): List<Effect> {
        val next = current.attempt + 1
        return when {
            next < retryDelaysMs.size -> {
                state = State.Backoff(current.nonce, next, current.first)
                listOf(Effect.ScheduleRetry(next, retryDelaysMs[next]))
            }

            current.first -> {
                exit(EXIT_UNDELIVERED, "could not reach the app: ${outcome.cause}")
            }

            else -> {
                state = State.Idle(current.nonce)
                emptyList()
            }
        }
    }

    private fun retryDue(
        current: State,
        attempt: Int,
    ): List<Effect> =
        if (current is State.Backoff && current.attempt == attempt) {
            deliver(current.nonce, current.attempt, current.first)
        } else {
            emptyList()
        }

    private fun appDied(
        current: State,
        event: Event.AppDied,
    ): List<Effect> =
        when {
            current !is State.Attached || current.session != event.session -> {
                emptyList()
            }

            !event.appGenuine -> {
                exit(EXIT_OK, "app uninstalled or no longer signed with the pinned certificate")
            }

            else -> {
                state = State.Orphaned(current.session, current.nonce)
                listOf(Effect.ScheduleGrace(current.session, orphanGraceMs))
            }
        }

    private fun graceExpired(
        current: State,
        event: Event.GraceExpired,
    ): List<Effect> =
        when {
            current !is State.Orphaned || current.session != event.session -> {
                emptyList()
            }

            event.appRunning -> {
                listOf(Effect.ReleaseAll) + deliver(current.nonce, 0, first = false)
            }

            else -> {
                state = State.Idle(current.nonce)
                listOf(Effect.ReleaseAll)
            }
        }

    private fun appStarted(current: State): List<Effect> =
        when (current) {
            is State.Orphaned -> listOf(Effect.ReleaseAll) + deliver(current.nonce, 0, first = false)
            is State.Idle -> deliver(current.nonce, 0, first = false)
            is State.Created, is State.Delivering, is State.Backoff, is State.Attached, State.Exited -> emptyList()
        }

    private fun deliver(
        nonce: String,
        attempt: Int,
        first: Boolean,
    ): List<Effect> {
        state = State.Delivering(nonce, attempt, first)
        return listOf(Effect.Deliver(nonce))
    }

    private fun exit(
        code: Int,
        why: String,
    ): List<Effect> {
        state = State.Exited
        return listOf(Effect.ReleaseAll, Effect.Exit(code, why))
    }

    companion object {
        /** Exit code after a rejection, shutdown or uninstall. */
        const val EXIT_OK: Int = 0

        /** Exit code when the first handoff never reached the app. */
        const val EXIT_UNDELIVERED: Int = 1

        /** Attempt 0 is immediate; later entries are the waits before each retry. */
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(0, 1_000, 3_000, 10_000)
    }
}
