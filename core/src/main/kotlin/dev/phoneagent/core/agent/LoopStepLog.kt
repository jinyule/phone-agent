package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.CompactionEnded
import dev.phoneagent.core.session.CompactionPrune
import dev.phoneagent.core.session.CompactionStarted
import dev.phoneagent.core.session.CompactionSummary
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionEventTypes
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.SideRequest
import dev.phoneagent.core.session.SideResponse
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind

/**
 * The [StepLog] extensions get: reads the whole log, writes context events only ([isContextEvent]),
 * through the task's single store.
 */
internal class LoopStepLog(
    private val loop: LoopContext,
) : StepLog {
    override val taskId: TaskId get() = loop.setup.taskId

    override suspend fun records(): List<SessionRecord> = loop.log.records()

    override suspend fun appendRuntimeContext(parts: List<Part>): Boolean {
        val latest =
            loop.log.records().asReversed().firstNotNullOfOrNull { record ->
                (record.event as? UserMessage)?.takeIf { it.kind == UserMessageKind.RUNTIME_CONTEXT }
            }
        if (latest?.parts == parts) return false
        loop.log.append(UserMessage(UserMessageKind.RUNTIME_CONTEXT, parts))
        return true
    }

    override suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp,
        sources: List<Long>,
    ): SessionRecord {
        require(isContextEvent(event)) { "${describe(event)} is not a context event; only the loop writes it" }
        return loop.log.append(event, surfaceOp, sources)
    }

    private fun describe(event: SessionEvent): String {
        val type = SessionEventTypes.typeOf(event)
        return if (event is UserMessage) "$type of kind ${event.kind}" else type
    }
}

/**
 * The allowlist of [StepLog.append]: compaction (`compaction/…`), `context/reasoning-dropped` and
 * runtime context (`user/message` of kind `RUNTIME_CONTEXT`). Everything else is a loop fact
 * (turns, steps, inbox, user input, replies, requests, calls, execution facts, results, verdicts,
 * prompts, screen facts, task settings and stops, side requests) that only the loop writes.
 * Every type is listed, so a new one is classified when it is added.
 */
internal fun isContextEvent(event: SessionEvent): Boolean =
    when (event) {
        is CompactionStarted,
        is CompactionSummary,
        is CompactionPrune,
        is CompactionEnded,
        is ReasoningDropped,
        -> true

        is UserMessage -> event.kind == UserMessageKind.RUNTIME_CONTEXT

        is TaskStarted,
        is TaskLevelChanged,
        is TurnStarted,
        is TurnEnded,
        is StepStarted,
        is StepEnded,
        is InboxSpliced,
        is SessionResumed,
        is RequestHeader,
        is RequestRetry,
        is AssistantMessage,
        is AssistantAttempt,
        is SideRequest,
        is SideResponse,
        is ToolCallRecorded,
        is ToolResultRecorded,
        is PolicyVerdictRecorded,
        is ApprovalAsked,
        is ApprovalDecided,
        is QuestionAsked,
        is QuestionAnswered,
        is HandoffStarted,
        is HandoffEnded,
        is PromptWithdrawn,
        is ScreenObserved,
        is ScreenActed,
        is ScreenLaunched,
        is ScreenFrame,
        is ScreenJudged,
        is ToolStarted,
        is ToolExecuted,
        is TaskStopped,
        -> false
    }
