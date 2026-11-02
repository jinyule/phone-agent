package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionRecord

/**
 * Why [response] breaks the reply contract, or `null` when the loop may record it and act on it.
 * The loop treats a violation as `ModelError.BadResponse` (retryable), before anything of the
 * reply is recorded (phase1 §7.2 step 4). [earlier] is the log so far.
 *
 * Call ids are unique within a session; the loop guarantees it here, at the boundary: a reply may
 * not repeat an id, nor reuse one of an earlier reply in [earlier]. Ids are never renumbered,
 * since stateful protocols (Responses) refer to the provider's own ids.
 *
 * Only a reply that ended normally may have its calls run: tool calls in a reply that stopped for
 * [StopReason.OTHER] (incomplete, filtered, unknown) are a violation. A reply cut at the output cap
 * ([StopReason.MAX_TOKENS]) is not; the loop records it and skips its calls (a truncation).
 *
 * A reply must show something: with neither a tool call nor non-blank text (empty, or reasoning
 * only) it is no final answer. A reply cut at the output cap is exempt; it ends the turn
 * `MAX_TOKENS` instead.
 */
internal fun replyProblem(
    response: ChatResponse,
    earlier: List<SessionRecord>,
): String? {
    val ids = response.toolCalls.map { it.id }
    val used = earlier.flatMapTo(mutableSetOf()) { record -> callIdsOf(record) }
    val seen = mutableSetOf<ToolCallId>()
    val repeated = ids.firstOrNull { !seen.add(it) }
    val reused = ids.firstOrNull { it in used }
    return when {
        repeated != null -> "tool call id $repeated appears more than once in the reply"
        reused != null -> "tool call id $reused was already used in this session"
        response.stopReason == StopReason.OTHER && ids.isNotEmpty() -> ABNORMAL
        response.stopReason != StopReason.MAX_TOKENS && !response.showsSomething() -> EMPTY
        else -> null
    }
}

private const val ABNORMAL = "tool calls in a reply that did not end normally (OTHER)"

private const val EMPTY = "the reply has no text and no tool call"

private fun ChatResponse.showsSomething(): Boolean =
    parts.any { it is Part.ToolCall || (it is Part.Text && it.text.isNotBlank()) }

private fun callIdsOf(record: SessionRecord): List<ToolCallId> =
    (record.event as? AssistantMessage)
        ?.parts
        .orEmpty()
        .filterIsInstance<Part.ToolCall>()
        .map { it.id }
