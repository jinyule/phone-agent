package dev.phoneagent.app.task

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind

/**
 * [this] after [record] was appended: the conversation, the steps ([stepped]) or the prompts it
 * changes. Every other record changes nothing the chat shows.
 */
internal fun ChatState.folded(record: SessionRecord): ChatState =
    when (val event = record.event) {
        is UserMessage -> {
            if (event.kind == UserMessageKind.USER) {
                said(ChatEntry.UserText(textOf(event.parts))).copy(commands = commands + 1)
            } else {
                this
            }
        }

        is AssistantMessage -> {
            textOf(event.parts).takeIf(String::isNotBlank)?.let { said(ChatEntry.AssistantText(it)) } ?: this
        }

        is TurnStarted, is TurnEnded -> {
            copy(openTurn = event is TurnStarted)
        }

        is TaskStopped -> {
            said(ChatEntry.Stopped(event.cause)).copy(openTurn = false)
        }

        is ToolCallRecorded, is ToolResultRecorded, is ScreenJudged -> {
            stepped(event)
        }

        is ApprovalAsked, is QuestionAsked, is HandoffStarted -> {
            asked(event)
        }

        is ApprovalDecided, is QuestionAnswered, is PromptWithdrawn, is HandoffEnded -> {
            closed(event)
        }

        else -> {
            this
        }
    }

/** [this] with [entry] at the end of the conversation. */
internal fun ChatState.said(entry: ChatEntry): ChatState = copy(entries = entries + entry)

/** The text parts of [parts], one per line. */
internal fun textOf(parts: List<Part>): String =
    parts
        .filterIsInstance<Part.Text>()
        .joinToString("\n") { it.text }
        .trim()

/** [this] with the prompt [event] asks open; a hand-off started for a call remembers the call. */
private fun ChatState.asked(event: SessionEvent): ChatState =
    when {
        event is ApprovalAsked -> opened(PendingPrompt.Confirm(event.promptId, event.card))
        event is QuestionAsked -> opened(PendingPrompt.Ask(event.promptId, event.question))
        event is HandoffStarted -> opened(PendingPrompt.Handoff(event.promptId, event.kind, event.reason), event.callId)
        else -> this
    }

private fun ChatState.opened(
    prompt: PendingPrompt,
    callId: ToolCallId? = null,
): ChatState =
    copy(
        prompts = prompts + (prompt.id to prompt),
        handoffCalls = callId?.let { handoffCalls + (prompt.id to it) } ?: handoffCalls,
    )

/** [this] without the prompt [event] closes; the end of a hand-off can also say where its app is. */
private fun ChatState.closed(event: SessionEvent): ChatState =
    when {
        event is ApprovalDecided -> without(event.promptId)
        event is QuestionAnswered -> without(event.promptId)
        event is PromptWithdrawn -> without(event.promptId)
        event is HandoffEnded -> handoffEnded(event)
        else -> this
    }

private fun ChatState.without(id: PromptId): ChatState = copy(prompts = prompts - id)

/**
 * [this] after a hand-off ended: closed, and its call's app with the user when it could not be
 * taken back ([HandoffResult.NOT_TAKEN_BACK]: it stays on the main display, `handoff/end`).
 */
private fun ChatState.handoffEnded(event: HandoffEnded): ChatState {
    val left = without(event.promptId)
    val callId = handoffCalls[event.promptId] ?: return left
    val notTakenBack = event.result == HandoffResult.NOT_TAKEN_BACK
    return if (notTakenBack) left.updated(callId) { it.copy(place = AppPlace.WITH_USER) } else left
}
