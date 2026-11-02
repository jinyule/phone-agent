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
 * `task/stopped`: the current run of the task stopped before finishing (`TaskRuntime.stop`). Written
 * last when a stop is handled, once nothing else may append: the results of the calls still
 * without one (CANCELLED, see `ToolStatus.CANCELLED`), `prompt/withdrawn`, `step/end(CANCELLED)`
 * and `turn/end(STOPPED)` come before it, and it comes with no turn open and no call that needs a
 * result without one (`SessionInvariants`). Not ignorable: the run rules depend on it.
 *
 * No cause ends the session (D14). Every cause ends this run only, and the task then waits for
 * the user: nothing runs until the user's own input (`user/message` of kind USER) is recorded in
 * a new turn, never on its own. After [StopCause.SHUTDOWN] and [StopCause.ERROR] the log is
 * resumed first: `session/resumed` is the next record. After [StopCause.USER] and
 * [StopCause.LIMIT] the next turn may start directly; a new runtime instance that takes the log
 * over writes `session/resumed` first, as always.
 *
 * Unfinished stop: when the process ended between `turn/end(STOPPED)` and this record, the log
 * ends with the `turn/end`, and the cause was never written. Recovery writes this record with
 * [StopCause.ERROR], meaning "the stop was not finished" (the cause is not guessed), then
 * `session/resumed`. When writing the log itself failed, the record may be missing altogether
 * and the log is recovered like a crashed one.
 *
 * @property cause why it stopped.
 */
@Serializable
@SerialName("task/stopped")
public data class TaskStopped(
    val cause: StopCause,
) : SessionEvent

/** Why a run of a task was stopped (`TaskRuntime.stop`, [TaskStopped]). None ends the session. */
@Serializable
public enum class StopCause {
    /** The user pressed stop. The user's next input continues the task. */
    USER,

    /** The app or service is shutting down. The log is resumed before the task continues. */
    SHUTDOWN,

    /**
     * An app-level limit across turns was reached (for example the task's total steps). The per-turn
     * limits (steps per turn, continuations) end only their turn, with [TurnEndReason.LIMIT], and
     * write no [TaskStopped].
     */
    LIMIT,

    /**
     * An unrecoverable error (e.g. the session log could not be flushed), or a stop whose
     * `task/stopped` was never written (recovery of an unfinished stop). The log is resumed before
     * the task continues.
     */
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

    /**
     * The reply hit the output cap. It stays recorded as it came, tool calls included, and each of
     * its calls was answered with a SKIPPED result that was never run (`ChatResponse.stopReason`).
     */
    MAX_TOKENS,

    /** A `preStep` interceptor refused the step. */
    PRE_STEP_REFUSED,

    /** The per-turn step limit or continuation limit was reached; ends this turn only, no `task/stopped`. */
    LIMIT,

    /**
     * The task was stopped. Followed by `task/stopped` (the log may end before it: an unfinished
     * stop); the next turn starts only with the user's own input.
     */
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

/** `step/end`: every call the step's replies asked for has its `tool/result` before it (`SessionInvariants`). */
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

    /** Failed; repair writes the missing tool results before this record. */
    FAILED,

    /** Cancelled by stop or steer; the missing tool results come before this record. */
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
 * `session/resumed`: a new runtime instance took the log over: after the process ended (a
 * restart, possibly after a crash), or within the process when a new instance continues a task
 * whose runtime stopped. Written by that instance after repair, before anything else it writes:
 * no turn is open and every call that needs a result has one (`SessionInvariants`). It is the
 * record right after a `task/stopped` with SHUTDOWN or ERROR. Like a stop, it leaves the task
 * waiting for the user: nothing runs until the user's own input is recorded in a new turn, and the
 * model must observe the screen again before acting. Not ignorable: the run rules depend on it.
 *
 * Before writing it, the runtime decides what to repair from the whole log, not from the last
 * stop alone (`SessionStore.open`).
 *
 * @property droppedTailBytes bytes of a torn last line that were discarded (0 when the log was clean).
 */
@Serializable
@SerialName("session/resumed")
public data class SessionResumed(
    val droppedTailBytes: Int,
) : SessionEvent
