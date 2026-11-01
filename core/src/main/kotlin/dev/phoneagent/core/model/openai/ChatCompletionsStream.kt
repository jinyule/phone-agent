package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.model.openai.WireSupport.array
import dev.phoneagent.core.model.openai.WireSupport.int
import dev.phoneagent.core.model.openai.WireSupport.obj
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Assembles one Chat Completions reply from stream chunks (or from a whole JSON completion when
 * the server ignored `stream:true`) and reports display deltas as they arrive. Tolerates the
 * gateway quirks in `core/src/test/resources/wire/chat/`: `usage:null` and usage inside the
 * choice, keep-alive comments, missing `[DONE]`, tool calls without `index` or `id`, an `index`
 * reused for a second call, names repeated in every fragment, mid-stream error chunks.
 *
 * Complete means `[DONE]` or a `finish_reason` arrived; a body that ends before either was cut
 * ([ModelError.Network]). Text with leaked tool markup is repaired at the end ([LeakedToolMarkup]);
 * the preview deltas hold back anything that may be markup, and the end shows what they missed
 * ([DeltaLedger.catchUp]).
 *
 * @param reasoningFields delta fields that carry reasoning, in priority order; the first one seen wins.
 */
internal class ChatCompletionsStream(
    private val provider: ProviderId,
    private val model: ModelId,
    private val reasoningFields: List<String>,
    private val newCallId: () -> String,
    onDelta: (Delta) -> Unit,
) {
    private val ledger = DeltaLedger(onDelta)
    private val text = StreamedText(ledger)
    private val refusal = StringBuilder()
    private val calls = ToolCallAssembler(newCallId, ledger)
    private var usage: Usage? = null
    private var finishReason: String? = null
    private var done = false
    private var chunks = 0
    private var responseId: String? = null
    private var reportedModel: String? = null

    /** Consumes one SSE event; `true` once the stream is complete (`[DONE]`). */
    fun accept(event: SseEvent): Boolean {
        val data = event.data.trim()
        done = data == DONE
        if (!done) chunk(parse(data), deltaKey = "delta")
        return done
    }

    /** Consumes a whole (non-streamed) completion object. */
    fun acceptJson(body: String) {
        chunk(parse(body), deltaKey = "message")
        done = true
    }

    /** The reply; throws [ModelError] for cut streams, refusals and malformed replies. */
    fun finish(): ChatResponse {
        checkComplete()
        val reason = finishReason
        checkOutcome(reason)
        val leaked = LeakedToolMarkup.scan(text.answer())
        val recovered = leaked.calls.map { Part.ToolCall(ToolCallId(newCallId()), it.name, it.argsJson) }
        val toolCalls = calls.parts() + recovered
        val parts =
            buildList {
                text.reasoning(provider, model)?.let(::add)
                if (leaked.text.isNotEmpty()) add(Part.Text(leaked.text))
                addAll(toolCalls)
            }
        ledger.catchUp(parts)
        val meta = metadata(responseId, reportedModel, reason, recovered.size)
        return ChatResponse(parts, stopReason(reason, toolCalls.isNotEmpty()), usage, meta)
    }

    private fun checkComplete() {
        if (!done && finishReason == null) throw ModelError.Network("stream ended before [DONE] or a finish_reason")
        if (chunks == 0) throw ModelError.BadResponse("stream carried no completion chunks")
    }

    private fun checkOutcome(reason: String?) {
        val refused = reason?.takeIf { it in REFUSALS } ?: REFUSAL.takeIf { refusal.isNotEmpty() }
        if (refused != null) throw ModelError.Refusal(refused)
        if (reason in FAILURES) {
            throw ModelError.Server(Status.INTERNAL_ERROR, "stream ended with finish_reason=$reason")
        }
    }

    private fun parse(data: String): JsonObject =
        try {
            Json.parseToJsonElement(data) as? JsonObject ?: throw ModelError.BadResponse("chunk is not a JSON object")
        } catch (e: SerializationException) {
            throw ModelError.BadResponse("chunk is not JSON: ${data.take(SNIPPET)}", e)
        }

    private fun chunk(
        root: JsonObject,
        deltaKey: String,
    ) {
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            throw ProviderErrors.fromStream(ProviderError.of(root), plan = false)
        }
        chunks++
        responseId = responseId ?: root.str("id")?.takeIf { it.isNotEmpty() }
        reportedModel = reportedModel ?: root.str("model")?.takeIf { it.isNotEmpty() }
        val choice = root.array("choices")?.firstOrNull() as? JsonObject
        (usageOf(root.obj("usage")) ?: usageOf(choice?.obj("usage")))?.let { usage = it }
        choice?.str("finish_reason")?.takeIf { it.isNotEmpty() }?.let { finishReason = it }
        choice?.obj(deltaKey)?.let(::delta)
    }

    private fun delta(delta: JsonObject) {
        reasoningFields.forEach { field -> delta.str(field)?.let { text.appendReasoning(field, it) } }
        textOf(delta["content"]).forEach(text::appendAnswer)
        delta.str("refusal")?.let(refusal::append)
        delta.array("tool_calls")?.forEach { (it as? JsonObject)?.let(calls::merge) }
    }

    private companion object {
        const val DONE = "[DONE]"
        const val SNIPPET = 200
        val STOP = setOf("stop", "end_turn", "eos")
        val LENGTH = setOf("length", "max_tokens")
        const val REFUSAL = "refusal"
        val REFUSALS = setOf("content_filter", "sensitive", REFUSAL)
        val FAILURES = setOf("error", "network_error")

        /** Answer text of a delta's `content`: a string, or the text parts of a content-part array. */
        fun textOf(content: JsonElement?): List<String> =
            when (content) {
                is JsonPrimitive -> {
                    listOfNotNull(content.takeIf { it.isString }?.content)
                }

                is JsonArray -> {
                    content.mapNotNull { part ->
                        (part as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
                    }
                }

                is JsonObject, null -> {
                    emptyList()
                }
            }

        fun stopReason(
            reason: String?,
            hasCalls: Boolean,
        ): StopReason =
            when {
                reason in LENGTH -> StopReason.MAX_TOKENS

                // Ollama reports "stop" next to tool calls; calls decide.
                hasCalls -> StopReason.TOOL_USE

                reason == null || reason in STOP -> StopReason.END_TURN

                else -> StopReason.OTHER
            }

        fun metadata(
            id: String?,
            model: String?,
            finishReason: String?,
            leakedCalls: Int,
        ): JsonObject? {
            val meta =
                buildJsonObject {
                    id?.let { put("id", it) }
                    model?.let { put("model", it) }
                    finishReason?.let { put("finish_reason", it) }
                    if (leakedCalls > 0) put("leaked_tool_calls", leakedCalls)
                }
            return meta.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Answer and reasoning text of one streamed reply. Answer deltas stop short of anything that may
 * be leaked tool markup ([LeakedToolMarkup.previewEnd]); reasoning keeps the first field it came in.
 */
internal class StreamedText(
    private val ledger: DeltaLedger,
) {
    private val answer = StringBuilder()
    private var shown = 0
    private val reasoning = StringBuilder()
    private var reasoningField: String? = null

    /** Appends answer text and shows what is safe to show. */
    fun appendAnswer(fragment: String) {
        if (fragment.isEmpty()) return
        answer.append(fragment)
        val end = LeakedToolMarkup.previewEnd(answer.toString()).coerceAtLeast(shown)
        if (end > shown) {
            ledger.text(answer.substring(shown, end))
            shown = end
        }
    }

    /** Appends reasoning from [field]; fragments from a second field are ignored. */
    fun appendReasoning(
        field: String,
        fragment: String,
    ) {
        if (fragment.isEmpty()) return
        if (reasoningField == null) reasoningField = field
        if (field == reasoningField) {
            reasoning.append(fragment)
            ledger.reasoning(fragment)
        }
    }

    /** The whole answer text. */
    fun answer(): String = answer.toString()

    /** The reasoning as a part (`{"<field>": text}`, the text also as summary), or `null` without any. */
    fun reasoning(
        provider: ProviderId,
        model: ModelId,
    ): Part.Reasoning? =
        reasoningField?.let { field ->
            val value = reasoning.toString()
            Part.Reasoning(
                provider,
                model,
                ReasoningKind.CHAT_REASONING_FIELD,
                chatReasoningOpaque(field, value),
                value,
            )
        }
}

/**
 * Tool calls of one streamed reply, assembled from `delta.tool_calls` fragments. A call is announced
 * ([Delta.ToolCallStarted]) once its name is known; its id is the provider's, or a fresh one when
 * the provider sends none.
 */
internal class ToolCallAssembler(
    private val newCallId: () -> String,
    private val ledger: DeltaLedger,
) {
    private class Slot(
        val index: Int?,
    ) {
        var id: String? = null
        val name = StringBuilder()
        val args = StringBuilder()
        var started = false
    }

    private val slots = mutableListOf<Slot>()

    /** Folds one fragment into its call. */
    fun merge(fragment: JsonObject) {
        val id = fragment.str("id")?.takeIf { it.isNotEmpty() }
        val function = fragment.obj("function")
        val slot = slotFor(fragment.int("index"), id)
        if (slot.id == null && !slot.started) slot.id = id
        function?.str("name")?.takeIf { it.isNotEmpty() }?.let { name ->
            // Some gateways repeat the whole name in every fragment; others split it.
            if (slot.name.toString() != name) slot.name.append(name)
        }
        if (!slot.started && slot.name.isNotEmpty()) start(slot)
        val args = argumentsOf(function?.get("arguments"))
        if (args.isNotEmpty()) {
            slot.args.append(args)
            if (slot.started) ledger.args(ToolCallId(checkNotNull(slot.id)), args)
        }
    }

    /** The finished calls, in order; empty arguments become `{}`. */
    fun parts(): List<Part.ToolCall> =
        slots.map { slot ->
            if (slot.name.isEmpty()) {
                throw ModelError.BadResponse("tool call ${slot.id ?: "#${slot.index}"} has no name")
            }
            val id = slot.id ?: newCallId()
            try {
                Part.ToolCall(ToolCallId(id), slot.name.toString(), slot.args.toString().ifEmpty { "{}" })
            } catch (e: IllegalArgumentException) {
                throw ModelError.BadResponse("unusable tool call id '${id.take(ID_SNIPPET)}'", e)
            }
        }

    private fun start(slot: Slot) {
        slot.started = true
        val callId = ToolCallId(slot.id ?: newCallId().also { slot.id = it })
        ledger.started(callId, slot.name.toString())
        ledger.args(callId, slot.args.toString())
    }

    /**
     * The call a fragment continues. With an `index`: the latest call at that index, unless the
     * fragment names a different id (a gateway reusing index 0 for a second call). Without one:
     * the call with that id, else the latest call.
     */
    private fun slotFor(
        index: Int?,
        id: String?,
    ): Slot {
        val existing =
            if (index != null) {
                slots.lastOrNull { it.index == index }?.takeIf { id == null || it.id == null || it.id == id }
            } else {
                slots.lastOrNull { id == null || it.id == id }
            }
        return existing ?: Slot(index).also { slots += it }
    }

    private fun argumentsOf(raw: JsonElement?): String =
        when (raw) {
            is JsonPrimitive -> if (raw.isString) raw.content else ""

            // A few gateways send the arguments as an object instead of a string.
            is JsonObject, is JsonArray -> raw.toString()

            null -> ""
        }

    private companion object {
        const val ID_SNIPPET = 200
    }
}

/**
 * Chat Completions usage, tolerant of `null`, of DeepSeek's `prompt_cache_hit_tokens` / `…miss…`
 * and Kimi's top-level `cached_tokens`. Counts are clamped so a provider's inconsistent numbers
 * never fail a reply.
 */
internal fun usageOf(usage: JsonObject?): Usage? = usage?.let(::usageFrom)

private fun usageFrom(usage: JsonObject): Usage? {
    val hit = usage.int("prompt_cache_hit_tokens")
    val miss = usage.int("prompt_cache_miss_tokens")
    val input = (usage.int("prompt_tokens") ?: hit?.let { h -> miss?.let { h + it } })?.coerceAtLeast(0)
    return input?.let {
        val output = (usage.int("completion_tokens") ?: 0).coerceAtLeast(0)
        val details = usage.obj("prompt_tokens_details")?.int("cached_tokens") ?: 0
        val cached = maxOf(details, hit ?: 0, usage.int("cached_tokens") ?: 0).coerceIn(0, input)
        val reasoning = usage.obj("completion_tokens_details")?.int("reasoning_tokens")?.coerceIn(0, output)
        Usage(input, output, cacheReadTokens = cached, reasoningTokens = reasoning)
    }
}
