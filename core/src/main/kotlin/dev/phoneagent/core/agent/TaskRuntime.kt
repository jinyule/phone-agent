package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
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

    /** Stops this task only, for [cause]; clears the inbox. Pending tool calls end CANCELLED. */
    public fun stop(cause: StopCause)

    /** Answers the pending prompt [promptId]; answers to unknown or expired prompts are ignored. */
    public fun answer(
        promptId: PromptId,
        reply: UserReply,
    )
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

/** Why a task was stopped. */
public enum class StopCause {
    /** The user pressed stop. */
    USER,

    /** The app or service is shutting down. */
    SHUTDOWN,

    /** The step or continuation limit was reached. */
    LIMIT,

    /** An unrecoverable error (e.g. the session log could not be flushed). */
    ERROR,
}

/** The user's answer to a pending prompt. */
public sealed interface UserReply {
    /** Confirmation granted (once). */
    public data object Approve : UserReply

    /** Confirmation denied. */
    public data object Reject : UserReply

    /** Answer to an `ask_user` question. */
    public data class Answer(
        /** The answer text. */
        val text: String,
    ) : UserReply

    /** The user finished a handed-off step. */
    public data object HandoffDone : UserReply

    /** The user gave up on a handed-off step. */
    public data object HandoffCancelled : UserReply
}
