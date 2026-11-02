package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.flow.StateFlow

/**
 * One task's driver: a turn/step state machine fed by a two-queue inbox (phase1 §3.7, D12).
 *
 * All input methods are non-suspending, thread-safe and return immediately; their effect is
 * recorded (`inbox/spliced`) before it is acted on. Calls after the task reached [Phase.Done]
 * or [Phase.Stopped] are ignored.
 */
public interface TaskRuntime {
    /** The task. */
    public val id: TaskId

    /** Current phase; conflated, so observers may skip intermediate values. */
    public val phase: StateFlow<Phase>

    /** Queues [input] for the next turn (one message per turn). Wakes the driver. */
    public fun followup(input: UserInput)

    /**
     * Queues [input] for the next step boundary and keeps the current turn from ending. Cancels an
     * in-flight model request (its delivered prefix is kept); never interrupts a running tool.
     */
    public fun steer(input: UserInput)

    /** Queues background [context] for the next step without waking an idle driver. */
    public fun inject(context: ContextInput)

    /**
     * Stops this task's current run only, for [cause]; clears the inbox, so continuing takes a new
     * command of the user. Pending tool calls end CANCELLED (`ToolStatus.CANCELLED` says what the
     * model is told), and `task/stopped` is recorded last, once everything that may still append
     * was cancelled and joined (inbox recording included). After it this instance appends
     * nothing and takes no more input. No cause ends the session (D14): the task continues on a
     * new instance, which writes `session/resumed` first, when the user's next input opens a new
     * turn, never on its own.
     */
    public fun stop(cause: StopCause)

    /**
     * Answers the pending prompt [promptId]. Returns `false`, changing nothing, when the prompt
     * is not pending (answered, expired, withdrawn, unknown) or [reply] does not fit its kind
     * (see [UserReply]); the UI then drops its card.
     */
    public fun answer(
        promptId: PromptId,
        reply: UserReply,
    ): Boolean
}

/** Driver state. */
public sealed interface Phase {
    /** Nothing queued. */
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

    /** Finished; the model ended the task. */
    public data object Done : Phase

    /** Stopped before finishing. */
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
