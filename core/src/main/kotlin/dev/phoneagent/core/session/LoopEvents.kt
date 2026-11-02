package dev.phoneagent.core.session

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.SecurityLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `task/start`: the settings the task started with; always record 1, written by
 * `SessionStore.create` together with the header. With [TaskLevelChanged] it is the log source of
 * `PolicyContext.level` and `PolicyContext.unattended` (the task id is in the header). Not
 * ignorable: verdicts cannot be replayed without it.
 *
 * @property level the security level in force from the start.
 * @property unattended no user is watching (scheduled task); fixed for the task.
 */
@Serializable
@SerialName("task/start")
public data class TaskStarted(
    val level: SecurityLevel,
    val unattended: Boolean,
) : SessionEvent

/**
 * `task/level-changed`: the user changed the security level of the running task; it applies to
 * calls evaluated after this record. Not ignorable, for the same reason as [TaskStarted].
 *
 * @property level the new level.
 */
@Serializable
@SerialName("task/level-changed")
public data class TaskLevelChanged(
    val level: SecurityLevel,
) : SessionEvent

/**
 * `task/stopped`: the task stopped before finishing. Written last when a stop is handled: the
 * results of pending calls (CANCELLED), `prompt/withdrawn` and `turn/end` come before it. Not
 * ignorable: whether the log may continue depends on it.
 *
 * Resume: [StopCause.USER] and [StopCause.LIMIT] are final. No record may follow (the store
 * refuses the append; a log with one is corrupt), so such a task is never resumed and its log
 * stays readable as it is. After [StopCause.SHUTDOWN] or [StopCause.ERROR] the runtime may resume
 * the task explicitly: repair, then `session/resumed`. When writing the log itself failed, the
 * record may be missing and the log ends like a crashed one.
 *
 * @property cause why it stopped.
 */
@Serializable
@SerialName("task/stopped")
public data class TaskStopped(
    val cause: StopCause,
) : SessionEvent

/** Why a task was stopped (`TaskRuntime.stop`, [TaskStopped]). */
@Serializable
public enum class StopCause {
    /** The user pressed stop. Final. */
    USER,

    /** The app or service is shutting down. Resumable. */
    SHUTDOWN,

    /** The step or continuation limit was reached. Final. */
    LIMIT,

    /** An unrecoverable error (e.g. the session log could not be flushed). Resumable. */
    ERROR,
}

/** `turn/start`: a turn began after inputs were taken from the inbox. [turn] counts from 1. */
@Serializable
@SerialName("turn/start")
public data class TurnStarted(
    /** Turn number within the session, >= 1. */
    val turn: Int,
) : SessionEvent {
    init {
        require(turn >= 1) { "turn/start: turn must be >= 1, got $turn" }
    }
}

/**
 * `turn/end`: a turn finished.
 *
 * @property turn the turn, >= 1.
 * @property reason why it ended.
 * @property detail diagnostic text (e.g. the refusing interceptor), or `null`.
 */
@Serializable
@SerialName("turn/end")
public data class TurnEnded(
    val turn: Int,
    val reason: TurnEndReason,
    val detail: String? = null,
) : SessionEvent {
    init {
        require(turn >= 1) { "turn/end: turn must be >= 1, got $turn" }
    }
}

/** Why a turn ended (phase1 §3.7 stop conditions). */
@Serializable
public enum class TurnEndReason {
    /** The model replied without tool calls. */
    COMPLETED,

    /** A tool result carried `ToolControl.EndTurn` (`end_not_done`); detail is its summary. */
    END_NOT_DONE,

    /** The reply hit the output cap; its tool calls were discarded. */
    MAX_TOKENS,

    /** A `preStep` interceptor refused the step. */
    PRE_STEP_REFUSED,

    /** The step limit or the continuation limit was reached. */
    LIMIT,

    /** The task was stopped. */
    STOPPED,

    /** A step failed (model error after retries, interceptor failure, flush failure). */
    FAILED,
}

/** `step/start`: one model request plus the tool calls it asks for. */
@Serializable
@SerialName("step/start")
public data class StepStarted(
    /** The turn, >= 1. */
    val turn: Int,
    /** Step number within the turn, >= 1. */
    val step: Int,
) : SessionEvent {
    init {
        require(turn >= 1 && step >= 1) { "step/start: turn and step must be >= 1, got $turn/$step" }
    }
}

/** `step/end`. */
@Serializable
@SerialName("step/end")
public data class StepEnded(
    /** The turn, >= 1. */
    val turn: Int,
    /** The step, >= 1. */
    val step: Int,
    /** How it ended. */
    val outcome: StepOutcome,
) : SessionEvent {
    init {
        require(turn >= 1 && step >= 1) { "step/end: turn and step must be >= 1, got $turn/$step" }
    }
}

/** How a step ended. */
@Serializable
public enum class StepOutcome {
    /** Model replied and every requested call has a result. */
    COMPLETED,

    /** Failed; repair fills in missing tool results. */
    FAILED,

    /** Cancelled by stop or steer. */
    CANCELLED,
}

/**
 * `inbox/spliced`: a queue changed, so queued input survives a crash. Folding these events in
 * order reproduces each queue: drop [removed] entries from the head, then append [added].
 *
 * @property queue which queue.
 * @property removed entries taken from the head (consumed or cleared), >= 0.
 * @property added entries appended at the tail.
 */
@Serializable
@SerialName("inbox/spliced")
public data class InboxSpliced(
    val queue: InboxQueue,
    val removed: Int,
    val added: List<InboxEntry> = emptyList(),
) : SessionEvent {
    init {
        require(removed >= 0) { "removed must be >= 0" }
    }
}

/** The two inbox queues (D12). */
@Serializable
public enum class InboxQueue {
    /** One entry per turn (`followup`). */
    NEXT_TURN,

    /** Taken at the next step boundary (`steer`, `inject`). */
    NEXT_STEP,
}

/**
 * A queued input.
 *
 * @property kind how it was submitted.
 * @property parts [Part.Text] and [Part.Image] only.
 * @property source origin id for [InboxEntryKind.INJECT] (e.g. `timer`), else `null`.
 */
@Serializable
public data class InboxEntry(
    val kind: InboxEntryKind,
    val parts: List<Part>,
    val source: String? = null,
) {
    init {
        require(parts.all { it is Part.Text || it is Part.Image }) { "Inbox entries hold text and images only" }
    }
}

/** How an [InboxEntry] was submitted. */
@Serializable
public enum class InboxEntryKind {
    /** `TaskRuntime.followup`. */
    FOLLOWUP,

    /** `TaskRuntime.steer`. */
    STEER,

    /** `TaskRuntime.inject`. */
    INJECT,
}

/**
 * `session/resumed`: the log was reopened after the process ended, possibly by a crash.
 * Written by the runtime after repair; the model must observe the screen again before acting.
 * Never after a final [TaskStopped] (USER, LIMIT): the store refuses it.
 *
 * @property droppedTailBytes bytes of a torn last line that were discarded (0 when the log was clean).
 */
@Serializable
@SerialName("session/resumed")
public data class SessionResumed(
    val droppedTailBytes: Int,
) : SessionEvent
