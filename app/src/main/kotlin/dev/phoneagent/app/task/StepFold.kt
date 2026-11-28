package dev.phoneagent.app.task

import dev.phoneagent.core.agent.LoopTexts
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus

private const val HAND_TO_USER = "hand_to_user"
private val SCREEN_LOST: String = ScreenUnavailableException::class.java.name

/** The product runs with the default texts (`ProductConfig.loop`). */
private val SCREEN_UNKNOWN: String = LoopTexts().screenUnknown

/** [this] after a step's [event]: its call, its result, or a screen withheld while it ran. */
internal fun ChatState.stepped(event: SessionEvent): ChatState =
    when {
        event is ToolCallRecorded -> called(event.call)
        event is ToolResultRecorded -> updated(event.callId) { finished(it, event.result) }
        event is ScreenJudged && event.outcome == JudgmentOutcome.WITHHELD -> withheld(event)
        else -> this
    }

/** [this] with the step of [callId] changed by [change]. */
internal fun ChatState.updated(
    callId: ToolCallId,
    change: (ChatEntry.Step) -> ChatEntry.Step,
): ChatState = copy(entries = entries.map { if (it is ChatEntry.Step && it.callId == callId) change(it) else it })

private fun ChatState.withheld(event: ScreenJudged): ChatState =
    updated(event.callId) { it.copy(withheld = it.withheld + event.snapshot) }

private fun ChatState.called(call: ToolCall): ChatState =
    if (entries.any { it is ChatEntry.Step && it.callId == call.id }) {
        this
    } else {
        said(ChatEntry.Step(call.id, call.name, call, cards.card(call, null)))
    }

private fun ChatState.finished(
    step: ChatEntry.Step,
    result: ToolResult,
): ChatEntry.Step =
    step.copy(
        card = step.call?.let { cards.card(it, result) } ?: step.card,
        status = result.status,
        place = placeOf(step, result),
    )

/**
 * Where the app of a hand_to_user call is after [result] (wp09-notes, WP02 r6/r8), once its
 * handoff started: an ERROR or TIMEOUT that says the screen is unknown after a lost screen
 * leaves it unknown, a stop leaves it with the user; a handoff that ended without the app taken
 * back already said so (`handoff/end` NOT_TAKEN_BACK). Every other result says nothing: the app
 * is on the agent's screen, or was never moved.
 */
private fun ChatState.placeOf(
    step: ChatEntry.Step,
    result: ToolResult,
): AppPlace? {
    if (step.tool != HAND_TO_USER || step.callId !in handoffCalls.values) return step.place
    val text = textOf(result.content)
    val failed = result.status == ToolStatus.ERROR || result.status == ToolStatus.TIMEOUT
    return when {
        result.status == ToolStatus.CANCELLED -> AppPlace.LEFT_WITH_USER
        failed && SCREEN_LOST in text && SCREEN_UNKNOWN in text -> AppPlace.MAYBE_WITH_USER
        else -> step.place
    }
}
