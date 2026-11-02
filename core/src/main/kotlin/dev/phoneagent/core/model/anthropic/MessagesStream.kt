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
 * - Blocks are kept verbatim: `thinking` (with the accumulated text and its `signature`, which
 *   must not be empty), `redacted_thinking` and `compaction` become [Part.Reasoning] bound to
 *   this provider and the requested model; `tool_use` becomes [Part.ToolCall] whose arguments are
 *   the raw `input_json_delta` text (eager input streaming: possibly cut off or invalid, never
 *   parsed here) or, without deltas, the start's `input`. Schema validation happens where the
 *   call is built (`dev.phoneagent.core.tool.ToolCall`); [StopReason.MAX_TOKENS] tells the loop to
 *   run nothing.
 * - The request's secrets never get into the reply (model-layer §2). Content the model produced
 *   is never rewritten: a secret anywhere in it (text, tool calls, thinking and its signature,
 *   redacted thinking, compaction, blocks this adapter does not know, a declined attempt's too)
 *   rejects the whole reply with [CredentialEcho.error], also when it is split across deltas
 *   ([EchoGuard]). Deltas are not held back: the fragment that completes the secret is not shown,
 *   those before it were. What the server wrote is masked instead: a refusal category like an
 *   error message; a `providerMetadata` field that masking would change is left out.
 * - Text deltas add up exactly to the reply's text. Reasoning deltas add up to its thinking
 *   summaries joined by a blank line, call announcements (once each, with the complete name) to
 *   its calls, argument deltas to each call's arguments; the one exception is a mid-output server
 *   fallback: the declined attempts' thinking and tool calls before the last `fallback` block
 *   were shown but are dropped from the reply (they must not be echoed). `providerMetadata` then
 *   names the channels in [PREVIEW_DIVERGED] and the dropped blocks in `fallback_discarded`
 *   (which blocks, not their content); a UI re-renders from the final reply.
 * - `end_turn` with a client tool call in the reply is [StopReason.TOOL_USE] (a translating
 *   gateway may report a call's turn as `end_turn`); `providerMetadata` keeps the wire reason.
 * - `stop_reason: refusal` throws [ModelError.Refusal] with `stop_details.category`; a stream
 *   `error` event throws its mapped error. A body that ends before `message_stop` is
 *   [ModelError.Network] (cut off, retryable); events out of order, blocks that overlap, come with
 *   a smaller index or repeat a tool id, blocks left open, a missing `stop_reason` or unparseable
 *   JSON are [ModelError.BadResponse]. Before any of these failures reaches the caller, the call
 *   arguments the reply left open are checked ([abandon]): a secret there is
 *   [CredentialEcho.error] instead. Unknown event types are ignored (the API may add some), so
 *   are unknown block types before the last `fallback` block (a declined attempt's internals);
 *   anywhere else an unknown block is refused, as is an unknown delta type for a known block.
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
    private val blocks = ContentBlocks(protocol, redactor.guard(), onDelta)
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

    /**
     * The reply failed before it was finished: call arguments it left unchecked are checked now
     * ([ContentBlocks.abandon]), so a secret in them fails it as [CredentialEcho.error] instead.
     */
    fun abandon() = blocks.abandon()

    /** The reply, after the body ended. */
    fun finish(): ChatResponse {
        if (!started) protocol.bad("stream carried no message_start")
        if (!stopped) throw ModelError.Network("stream ended before message_stop")
        blocks.open()?.let { protocol.bad("message_stop with block $it still open") }
        val reason = facts.stopReason ?: protocol.bad("message_stop without a stop_reason")
        // The category is the server's text; it goes into the error's message, so it is masked.
        if (reason == "refusal") throw ModelError.Refusal(facts.refusalCategory?.let(redactor::text))
        val content = blocks.assemble(bind)
        val metadata = facts.metadata(reason, content, model)
        val calls = content.parts.any { it is Part.ToolCall }
        return ChatResponse(content.parts, stopReasonOf(reason, calls), facts.usage(), metadata)
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

        /** The neutral stop reason; [calls] tells whether the reply kept a client tool call. */
        fun stopReasonOf(
            reason: String,
            calls: Boolean,
        ): StopReason =
            when (reason) {
                "end_turn" -> if (calls) StopReason.TOOL_USE else StopReason.END_TURN

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
