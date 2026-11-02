package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

/** Why a turn ends abnormally. */
internal enum class RepairCause {
    /** The task was stopped. */
    STOPPED,

    /** A step failed (interceptor failure, session log failure) or the process died (resume). */
    FAILED,
}

/** A record the repair appends. */
internal class Repair(
    val event: SessionEvent,
    val sources: List<Long> = emptyList(),
)

/**
 * What closes everything an abnormal end left open in [records] (phase1 §3.7, D14), in this order:
 * - every prompt without a result gets `prompt/withdrawn`;
 * - every tool call of a reply without a `tool/result` gets one. With a `tool/call` record (it was
 *   dispatched, and is not in [notStarted]) it may have run: `CANCELLED` when stopped, else
 *   `OUTCOME_UNKNOWN`, both telling the model to observe first. Otherwise it never ran:
 *   `CANCELLED` when stopped, else `NOT_STARTED`;
 * - an open step gets `step/end` (`CANCELLED` when stopped, else `FAILED`);
 * - an open turn gets `turn/end` (`STOPPED` when stopped, else `FAILED`) with [detail].
 *
 * Pure: the same log gives the same repair, in-process (stop, failure) and when resuming.
 */
internal fun repairOf(
    records: List<SessionRecord>,
    cause: RepairCause,
    notStarted: Set<ToolCallId>,
    detail: String?,
    texts: LoopTexts,
): List<Repair> {
    val answered = records.mapNotNullTo(HashSet()) { (it.event as? ToolResultRecorded)?.callId }
    val dispatched = records.mapNotNull { r -> (r.event as? ToolCallRecorded)?.let { it.call.id to r.seq } }.toMap()
    val closed = records.mapNotNullTo(HashSet()) { closedPrompt(it.event) }
    val repairs =
        records
            .mapNotNull {
                openedPrompt(it.event)
            }.filter { it !in closed }
            .mapTo(mutableListOf()) { Repair(PromptWithdrawn(it)) }
    for (record in records) {
        val calls = (record.event as? AssistantMessage)?.parts.orEmpty().filterIsInstance<Part.ToolCall>()
        for (call in calls.filter { it.id !in answered }) {
            val callSeq = dispatched[call.id]
            val result = missingResult(cause, callSeq != null && call.id !in notStarted, texts)
            repairs += Repair(ToolResultRecorded(call.id, call.name, result), listOf(callSeq ?: record.seq))
        }
    }
    val stopped = cause == RepairCause.STOPPED
    openStep(records)?.let { step ->
        val outcome = if (stopped) StepOutcome.CANCELLED else StepOutcome.FAILED
        repairs += Repair(StepEnded(step.turn, step.step, outcome))
    }
    openTurn(records)?.let { turn ->
        val reason = if (stopped) TurnEndReason.STOPPED else TurnEndReason.FAILED
        repairs += Repair(TurnEnded(turn.turn, reason, detail))
    }
    return repairs
}

private fun missingResult(
    cause: RepairCause,
    mayHaveRun: Boolean,
    texts: LoopTexts,
): ToolResult =
    when (cause) {
        RepairCause.STOPPED -> {
            if (mayHaveRun) {
                ToolResult.failure(ToolStatus.CANCELLED, "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}")
            } else {
                ToolResult.failure(ToolStatus.CANCELLED, texts.stoppedBeforeRun)
            }
        }

        RepairCause.FAILED -> {
            if (mayHaveRun) {
                ToolResult.failure(ToolStatus.OUTCOME_UNKNOWN, texts.outcomeUnknown)
            } else {
                ToolResult.failure(ToolStatus.NOT_STARTED, texts.notStarted)
            }
        }
    }

private fun openedPrompt(event: SessionEvent): PromptId? =
    when {
        event is ApprovalAsked -> event.promptId
        event is QuestionAsked -> event.promptId
        event is HandoffStarted -> event.promptId
        else -> null
    }

private fun closedPrompt(event: SessionEvent): PromptId? =
    when {
        event is ApprovalDecided -> event.promptId
        event is QuestionAnswered -> event.promptId
        event is HandoffEnded -> event.promptId
        event is PromptWithdrawn -> event.promptId
        else -> null
    }

/** The step started last, when it has no `step/end`. */
internal fun openStep(records: List<SessionRecord>): StepStarted? =
    records.fold(null as StepStarted?) { open, record ->
        val event = record.event
        when {
            event is StepStarted -> event
            event is StepEnded -> null
            else -> open
        }
    }

/** The turn started last, when it has no `turn/end`. */
internal fun openTurn(records: List<SessionRecord>): TurnStarted? =
    records.fold(null as TurnStarted?) { open, record ->
        val event = record.event
        when {
            event is TurnStarted -> event
            event is TurnEnded -> null
            else -> open
        }
    }
