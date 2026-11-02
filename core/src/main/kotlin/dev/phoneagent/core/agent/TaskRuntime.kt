package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One task's driver: a turn/step state machine fed by a two-queue inbox (phase1 §3.7, D12).
 *
 * All input methods are non-suspending, thread-safe and return immediately; their effect is
 * recorded (`inbox/spliced`) before it is acted on. [Phase.Idle] and [Phase.Done] both accept
 * input (a followup starts the next turn); calls after the task reached [Phase.Stopped] are ignored.
 */
public interface TaskRuntime {
    /** The task. */
    public val id: TaskId

    /** Current phase; conflated, so observers may skip intermediate values. */
    public val phase: StateFlow<Phase>

    /**
     * Read-only observation (phase1 §3.8): every record as it is appended, streaming fragments and
     * trajectory cards. Collecting never changes behaviour. A collector that falls behind loses the
     * oldest events, so anything a UI must not miss is recovered from [phase] or the session log.
     */
    public val events: SharedFlow<LoopEvent>

    /** Queues [input] for the next turn (one message per turn). Wakes the driver. */
    public fun followup(input: UserInput)

    /**
     * Queues [input] for the next step boundary. While it is queued, a plain final answer does not
     * end the current turn: another step gives the model the steer. An explicit end
     * (`end_not_done`), a failure, a `preStep` refusal, the output cap or a limit still ends the
     * turn; the steer then stays queued and starts the next turn. Cancels an in-flight model request
     * (its delivered prefix is kept); never interrupts a running tool.
     */
    public fun steer(input: UserInput)

    /** Queues background [context] for the next step without waking an idle driver. */
    public fun inject(context: ContextInput)

    /**
     * Changes the task's security level to [level] for every call judged after it is recorded
     * (`task/level-changed`, in order with the other input). The only way a level changes.
     */
    public fun changeLevel(level: SecurityLevel)

    /**
     * Stops this task only, for [cause]; clears the inbox. An in-flight model request is cancelled
     * (its delivered prefix kept as interrupted), a pending prompt is withdrawn, and pending or
     * running tool calls end CANCELLED. Input accepted before the stop is recorded, then recorded
     * as removed, never lost; the same holds when the runtime's scope is cancelled
     * ([StopCause.SHUTDOWN]). Later calls to any method are ignored.
     */
    public fun stop(cause: StopCause)

    /**
     * Answers the pending prompt [promptId]. Returns `true` exactly when [reply] became the
     * prompt's answer. Returns `false`, changing nothing, when the prompt is not pending (answered,
     * expired, withdrawn, unknown) or [reply] does not fit its kind (see [UserReply]); the UI then
     * drops its card. A prompt stops taking replies the moment it is closed: a stop, a shutdown or
     * its expiry closes it before the task acts on that, and a reply taken just before the expiry
     * counts.
     *
     * A UI that did not see a prompt being asked recovers it from the session log:
     * [Phase.Waiting.promptId] is the id of its `approval/asked`, `question/asked` or
     * `handoff/started` record, which holds the whole card, question or handoff.
     */
    public fun answer(
        promptId: PromptId,
        reply: UserReply,
    ): Boolean
}

/** Driver state. */
public sealed interface Phase {
    /**
     * Nothing queued: not started yet, or the last turn ended without a final answer (failure,
     * limit, `preStep` refusal, output cap). Nothing continues on its own (D14); a followup does.
     */
    public data object Idle : Phase

    /** Working on [turn] (from 1), [step] (from 1 within the turn). */
    public data class Running(
        /** Turn number. */
        val turn: Int,
        /** Step number within the turn. */
        val step: Int,
    ) : Phase

    /** Suspended until [promptId] is answered (or the lock screen clears when [reason] is LOCK). */
    public data class Waiting(
        /** What it waits for. */
        val reason: WaitReason,
        /** The prompt to answer, or `null` for [WaitReason.LOCK]. */
        val promptId: PromptId?,
    ) : Phase

    /**
     * The last turn ended with the model's final answer or `end_not_done` and nothing is queued.
     * Not terminal: a followup starts the next turn.
     */
    public data object Done : Phase

    /** Stopped for good (by [TaskRuntime.stop], a shutdown or a session log failure). Terminal. */
    public data class Stopped(
        /** Why. */
        val cause: StopCause,
    ) : Phase
}

/** What a [Phase.Waiting] task waits for. */
public enum class WaitReason {
    /** A confirmation or a question. */
    USER,

    /** The user is doing a handed-off step. */
    HANDOFF,

    /** The screen is locked (D11). */
    LOCK,
}

/**
 * Something the user said: text and/or images (already stored as blobs).
 *
 * @property parts [Part.Text] and [Part.Image] only, not empty.
 */
public data class UserInput(
    val parts: List<Part>,
) {
    init {
        require(parts.isNotEmpty()) { "UserInput must not be empty" }
        require(parts.all { it is Part.Text || it is Part.Image }) { "UserInput may hold text and images only" }
    }
}

/**
 * Background context added by the app, not the user (timer fired, notification arrived).
 *
 * @property source stable origin id, e.g. `timer`, recorded with the message.
 * @property parts [Part.Text] and [Part.Image] only, not empty.
 */
public data class ContextInput(
    val source: String,
    val parts: List<Part>,
) {
    init {
        require(source.isNotBlank()) { "ContextInput source must not be blank" }
        require(parts.isNotEmpty()) { "ContextInput must not be empty" }
        require(parts.all { it is Part.Text || it is Part.Image }) { "ContextInput may hold text and images only" }
    }
}

/**
 * What the user did with a pending prompt. The runtime turns it into the waiting call's result
 * (`dev.phoneagent.core.ports.UserInteraction`); expiry and unavailability come from the runtime,
 * not from the user.
 */
public sealed interface UserReply {
    /** Confirmation granted, for that call only. Fits a confirmation. */
    public data object Approve : UserReply

    /** Confirmation denied. Fits a confirmation. */
    public data object Reject : UserReply

    /** Answer to a question. Fits a question. */
    public data class Answer(
        /** The answer text, non-blank. */
        val text: String,
    ) : UserReply {
        init {
            require(text.isNotBlank()) { "An answer must not be blank" }
        }
    }

    /** Dismissed without answering, or gave a handed-off task back undone. Fits a question or a handoff. */
    public data object Decline : UserReply

    /** The user finished the handed-off step. Fits a handoff. */
    public data object HandoffDone : UserReply
}
