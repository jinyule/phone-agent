package dev.phoneagent.core.session

import dev.phoneagent.core.model.Part
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `turn/start`: a turn began after inputs were taken from the inbox. [turn] counts from 1. */
@Serializable
@SerialName("turn/start")
public data class TurnStarted(
    /** Turn number within the session. */
    val turn: Int,
) : SessionEvent

/**
 * `turn/end`: a turn finished.
 *
 * @property turn the turn.
 * @property reason why it ended.
 * @property detail diagnostic text (e.g. the refusing interceptor), or `null`.
 */
@Serializable
@SerialName("turn/end")
public data class TurnEnded(
    val turn: Int,
    val reason: TurnEndReason,
    val detail: String? = null,
) : SessionEvent

/** Why a turn ended (phase1 §3.7 stop conditions). */
@Serializable
public enum class TurnEndReason {
    /** The model replied without tool calls. */
    COMPLETED,

    /** The model called `end_not_done`. */
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
    /** The turn. */
    val turn: Int,
    /** Step number within the turn, from 1. */
    val step: Int,
) : SessionEvent

/** `step/end`. */
@Serializable
@SerialName("step/end")
public data class StepEnded(
    /** The turn. */
    val turn: Int,
    /** The step. */
    val step: Int,
    /** How it ended. */
    val outcome: StepOutcome,
) : SessionEvent

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
 *
 * @property droppedTailBytes bytes of a torn last line that were discarded (0 when the log was clean).
 */
@Serializable
@SerialName("session/resumed")
public data class SessionResumed(
    val droppedTailBytes: Int,
) : SessionEvent
