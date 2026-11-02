package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Assembles one streamed Messages reply (`message_start`, `content_block_*`, `message_delta`,
 * `message_stop`) into a [ChatResponse], showing fragments through `onDelta` as they arrive.
 *
 * - Blocks are kept verbatim: `thinking` (with the accumulated text and its `signature`),
 *   `redacted_thinking` and `compaction` become [Part.Reasoning] bound to this provider and the
 *   requested model; `tool_use` becomes [Part.ToolCall] whose arguments are the raw
 *   `input_json_delta` text (eager input streaming: possibly cut off or invalid, never parsed
 *   here) or, without deltas, the start's `input`. Schema validation happens where the call is
 *   built (`dev.phoneagent.core.tool.ToolCall`); [StopReason.MAX_TOKENS] tells the loop to run nothing.
 * - Deltas add up to the reply: text deltas to its text, reasoning deltas to its thinking
 *   summaries joined by a blank line, argument deltas to each call's arguments; a call is
 *   announced once with its complete name. The one exception is a mid-output server fallback:
 *   the declined attempt's thinking and tool calls before the last `fallback` block were shown
 *   but are dropped from the reply (they must not be echoed); `providerMetadata` says so.
 * - `stop_reason: refusal` throws [ModelError.Refusal] with `stop_details.category`; a stream
 *   `error` event throws its mapped error. A body that ends before `message_stop` is
 *   [ModelError.Network] (cut off, retryable); events out of order, blocks left open, a missing
 *   `stop_reason` or unparseable JSON are [ModelError.BadResponse]. Unknown event types are
 *   ignored (the API may add some); unknown block or delta types are refused.
 *
 * Not thread-safe; one instance per reply.
 */
internal class MessagesStream(
    private val provider: ProviderId,
    private val model: ModelId,
    private val redactor: Redactor,
    onDelta: (Delta) -> Unit,
) {
    private val protocol = Protocol(redactor)
    private val blocks = ContentBlocks(protocol, onDelta)
    private val facts = MessageFacts(protocol)
    private val bind = Bind { kind, opaque, summary -> Part.Reasoning(provider, model, kind, opaque, summary) }
    private var started = false
    private var stopped = false

    /** Consumes one SSE event; `true` once `message_stop` ends the reply. */
    fun accept(event: SseEvent): Boolean {
        if (event.data.isNotBlank() && event.data != DONE) {
            val data =
                protocol.parse(event.data, "event") as? JsonObject ?: protocol.bad("event data is not a JSON object")
            dispatch(data.str("type") ?: event.event ?: protocol.bad("event without a type"), data)
        }
        return stopped
    }

    /**
     * A whole Message object instead of a stream (a server that ignored `stream: true`): read as
     * the events that would have carried it, so the same checks and deltas apply.
     */
    fun acceptJson(text: String) {
        val message = protocol.parse(text, "reply") as? JsonObject ?: protocol.bad("reply is not a JSON object")
        if (message.str("type") == "error") throw AnthropicErrors.fromStream(AnthropicError.of(message, redactor))
        val content = message["content"] as? JsonArray ?: protocol.bad("reply without content")
        start(JsonObject(message - "content"))
        content.forEachIndexed(blocks::whole)
        val delta = JsonObject(message.filterKeys { it in MESSAGE_DELTA_FIELDS })
        facts.delta(buildJsonObject { put("delta", delta) })
        stop()
    }

    /** The reply, after the body ended. */
    fun finish(): ChatResponse {
        if (!started) protocol.bad("stream carried no message_start")
        if (!stopped) throw ModelError.Network("stream ended before message_stop")
        blocks.open()?.let { protocol.bad("message_stop with block $it still open") }
        val reason = facts.stopReason ?: protocol.bad("message_stop without a stop_reason")
        if (reason == "refusal") throw ModelError.Refusal(facts.refusalCategory)
        val content = blocks.assemble(bind)
        return ChatResponse(content.parts, stopReasonOf(reason), facts.usage(), facts.metadata(reason, content))
    }

    private fun dispatch(
        type: String,
        data: JsonObject,
    ) {
        when (type) {
            "message_start" -> start(data.obj("message") ?: protocol.bad("message_start without a message"))

            in BLOCK_EVENTS -> block(type, data)

            "message_delta" -> facts.delta(data.also { requireStarted(type) })

            "message_stop" -> stop()

            "error" -> throw AnthropicErrors.fromStream(AnthropicError.of(data, redactor))

            // `ping` and event types added later carry nothing this adapter uses.
            else -> Unit
        }
    }

    private fun block(
        type: String,
        data: JsonObject,
    ) {
        requireStarted(type)
        when (type) {
            "content_block_start" -> blocks.start(data)
            "content_block_delta" -> blocks.delta(data)
            else -> blocks.stop(data)
        }
    }

    private fun start(message: JsonObject) {
        if (started) protocol.bad("message_start twice")
        started = true
        facts.start(message)
    }

    private fun stop() {
        requireStarted("message_stop")
        stopped = true
    }

    private fun requireStarted(what: String) {
        if (!started) protocol.bad("$what before message_start")
        if (stopped) protocol.bad("$what after message_stop")
    }

    private companion object {
        const val DONE = "[DONE]"

        fun stopReasonOf(reason: String): StopReason =
            when (reason) {
                "end_turn" -> StopReason.END_TURN

                "tool_use" -> StopReason.TOOL_USE

                // Both cut the reply short: tool calls in it are not to be run (phase1 §3.7).
                "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS

                else -> StopReason.OTHER
            }
    }
}

private val BLOCK_EVENTS = setOf("content_block_start", "content_block_delta", "content_block_stop")
private val MESSAGE_DELTA_FIELDS = setOf("stop_reason", "stop_sequence", "stop_details")

/** `providerMetadata` key listing the delta channels the final reply contradicts. */
internal const val PREVIEW_DIVERGED = "preview_diverged"
