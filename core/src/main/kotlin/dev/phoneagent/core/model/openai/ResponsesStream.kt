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
import dev.phoneagent.core.model.openai.WireSupport.TEXT_SEPARATOR
import dev.phoneagent.core.model.openai.WireSupport.array
import dev.phoneagent.core.model.openai.WireSupport.int
import dev.phoneagent.core.model.openai.WireSupport.obj
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Assembles one Responses reply from its event stream (model-layer §3.2). Text, reasoning and
 * argument deltas go to `onDelta` (what they missed is shown at the end, [DeltaLedger.catchUp]).
 * Reasoning comes as summaries or as raw reasoning text; the first of the two seen is the one
 * shown, both as deltas and as the reasoning parts' summaries, so the two never mix;
 * the parts come from the output items in `output_index` order, finished on the stream
 * (`response.output_item.done`) or listed in the final `response.output` ([OutputItems]). Only
 * `response.completed` or `response.incomplete` complete a stream: a body that ends before them
 * was cut ([ModelError.Network]). `response.failed` and `error` events become [ModelError]s.
 */
internal class ResponsesStream(
    private val provider: ProviderId,
    private val model: ModelId,
    private val plan: Boolean,
    private val redactor: Redactor,
    onDelta: (Delta) -> Unit,
) {
    private val ledger = DeltaLedger(onDelta)
    private var reasoningSection: String? = null
    private var reasoningChannel: String? = null
    private val items = OutputItems()
    private val callIdsByItem = mutableMapOf<String, String>()
    private var final: JsonObject? = null
    private var incomplete = false

    /** Consumes one SSE event; `true` once the response is final. */
    fun accept(event: SseEvent): Boolean {
        val data = event.data.trim()
        if (data != "[DONE]") {
            val root = parse(data)
            val type = root.str("type") ?: event.event.orEmpty()
            if (!delta(type, root)) lifecycle(type, root)
        }
        return final != null
    }

    /** Consumes a whole response object (servers that ignore `stream:true`). */
    fun acceptJson(body: String) {
        val root = parse(body)
        root.obj("error")?.let { throw failure(it) }
        final = root
        incomplete = root.str("status") == "incomplete"
    }

    /** The reply; throws [ModelError] for cut streams, refusals and malformed replies. */
    fun finish(): ChatResponse {
        val response = final ?: throw ModelError.Network("stream ended before response.completed")
        val output = items.merged(response.array("output")?.filterIsInstance<JsonObject>().orEmpty())
        val reason = response.obj("incomplete_details")?.str("reason")
        val rawFirst = reasoningChannel == RAW_REASONING_DELTA
        val parts = ResponsesOutput.parts(output, provider, model, redactor, rawFirst)
        val hasCalls = parts.any { it is Part.ToolCall }
        ResponsesOutput.checkRefusal(output, parts, incomplete && reason == "content_filter")
        val diverged = ledger.catchUp(parts)
        val stop =
            when {
                incomplete && reason == "max_output_tokens" -> StopReason.MAX_TOKENS
                hasCalls -> StopReason.TOOL_USE
                incomplete -> StopReason.OTHER
                else -> StopReason.END_TURN
            }
        val meta = ResponsesOutput.metadata(response, reason, diverged)
        return ChatResponse(parts, stop, ResponsesOutput.usage(response), meta)
    }

    /** Display deltas; `false` when [type] is not one. */
    private fun delta(
        type: String,
        root: JsonObject,
    ): Boolean {
        val text = root.str("delta")?.takeIf { it.isNotEmpty() }
        when (type) {
            "response.output_text.delta" -> text?.let(ledger::text)
            SUMMARY_DELTA, RAW_REASONING_DELTA -> text?.let { reasoning(type, root, it) }
            "response.output_item.added" -> root.obj("item")?.let(::started)
            "response.function_call_arguments.delta" -> arguments(root.str("item_id"), text)
            else -> return false
        }
        return true
    }

    /** Items and terminal events; anything else (`response.created`, `*.done` of deltas …) is ignored. */
    private fun lifecycle(
        type: String,
        root: JsonObject,
    ) {
        when (type) {
            "response.output_item.done" -> {
                root.obj("item")?.let { items.done(root.int("output_index"), it) }
            }

            "response.completed" -> {
                final = root.obj("response") ?: JsonObject(emptyMap())
            }

            "response.incomplete" -> {
                final = root.obj("response") ?: JsonObject(emptyMap())
                incomplete = true
            }

            "response.failed" -> {
                throw failure(root.obj("response")?.obj("error") ?: root)
            }

            "error" -> {
                throw failure(root.obj("error") ?: root)
            }
        }
    }

    private fun parse(data: String): JsonObject =
        try {
            Json.parseToJsonElement(data) as? JsonObject ?: throw ModelError.BadResponse("event is not a JSON object")
        } catch (e: SerializationException) {
            throw ModelError.BadResponse("event is not JSON: ${redactor.text(data).take(SNIPPET)}", redactor.cause(e))
        }

    private fun failure(error: JsonObject): ModelError =
        ProviderErrors.fromStream(ProviderError.of(error, redactor), plan)

    /**
     * Shows a reasoning fragment of the first channel ([type]) seen; each summary or reasoning-text
     * part is a section of its own, after a blank line.
     */
    private fun reasoning(
        type: String,
        root: JsonObject,
        fragment: String,
    ) {
        if (reasoningChannel == null) reasoningChannel = type
        if (type != reasoningChannel) return
        val section = "${root.str("item_id")}/${root.int("summary_index") ?: root.int("content_index")}"
        if (section != reasoningSection && ledger.hasReasoning) ledger.reasoning(TEXT_SEPARATOR)
        reasoningSection = section
        ledger.reasoning(fragment)
    }

    private fun started(item: JsonObject) {
        val callId = item.str("call_id")
        val name = item.str("name")
        if (item.str("type") == "function_call" && callId != null && name != null) {
            item.str("id")?.let { callIdsByItem[it] = callId }
            ledger.started(ToolCallId(callId), name)
        }
    }

    private fun arguments(
        itemId: String?,
        fragment: String?,
    ) {
        val callId = itemId?.let(callIdsByItem::get)
        if (callId != null && fragment != null) ledger.args(ToolCallId(callId), fragment)
    }

    private companion object {
        const val SNIPPET = 200
    }
}

/**
 * The output items of one reply by `output_index` (model-layer §3.2): those the stream finished,
 * then the final `response.output` (by position) merged in, so items only one of them has are
 * kept. An item without an index takes the next position. Two versions of one item must agree on
 * what the adapter reads from it (type, ids, name, arguments, texts, encrypted content); a
 * contradiction is [ModelError.BadResponse].
 */
internal class OutputItems {
    private val byIndex = sortedMapOf<Int, JsonObject>()
    private var next = 0

    /** Records an item from `response.output_item.done`. */
    fun done(
        index: Int?,
        item: JsonObject,
    ) = put(index ?: next, item)

    /** All items in index order, with [final] (the response's `output`) merged in. */
    fun merged(final: List<JsonObject>): List<JsonObject> {
        final.forEachIndexed(::put)
        return byIndex.values.toList()
    }

    private fun put(
        index: Int,
        item: JsonObject,
    ) {
        val known = byIndex[index]
        byIndex[index] = if (known == null) item else agreed(index, known, item)
        next = maxOf(next, index + 1)
    }

    /** [known] and [other] as one item ([known] wins on keys the adapter does not read). */
    private fun agreed(
        index: Int,
        known: JsonObject,
        other: JsonObject,
    ): JsonObject {
        val a = visible(known)
        val b = visible(other)
        val clash = a.keys.firstOrNull { key -> a[key] != null && b[key] != null && a[key] != b[key] }
        if (clash != null) throw ModelError.BadResponse("output item $index has two different '$clash' values")
        return JsonObject(other + known)
    }

    private companion object {
        val READ = listOf("type", "id", "call_id", "name", "arguments", "encrypted_content")

        /** What the adapter reads from an item; `null` values are absent. */
        fun visible(item: JsonObject): Map<String, JsonElement?> =
            READ.associateWith { item[it] } +
                mapOf("content" to texts(item["content"]), "summary" to texts(item["summary"]))

        /** The `text` members of a content or summary array. */
        fun texts(parts: JsonElement?): JsonElement? =
            (parts as? JsonArray)?.let { array -> JsonArray(array.mapNotNull { (it as? JsonObject)?.get("text") }) }
    }
}

private const val SUMMARY_DELTA = "response.reasoning_summary_text.delta"
private const val RAW_REASONING_DELTA = "response.reasoning_text.delta"

/** Pure readers of a finished Responses `output` and `response` object. */
internal object ResponsesOutput {
    private const val SNIPPET = 200

    /**
     * The parts of [output], in order: `reasoning` items verbatim (with their summary for the UI),
     * one text part per `message`, `function_call`s. Hosted tool items are never requested and
     * anything else is not model-visible content, so both are skipped.
     *
     * @param rawFirst show a reasoning item's raw reasoning text rather than its summary when it has both.
     */
    fun parts(
        output: List<JsonObject>,
        provider: ProviderId,
        model: ModelId,
        redactor: Redactor,
        rawFirst: Boolean,
    ): List<Part> =
        output.mapNotNull { item ->
            when (item.str("type")) {
                "reasoning" -> {
                    Part.Reasoning(provider, model, ReasoningKind.RESPONSES_ENCRYPTED, item, summary(item, rawFirst))
                }

                "message" -> {
                    text(item)?.let(Part::Text)
                }

                "function_call" -> {
                    call(item, redactor)
                }

                else -> {
                    null
                }
            }
        }

    /** Throws [ModelError.Refusal] when the reply was filtered or is nothing but a refusal. */
    fun checkRefusal(
        output: List<JsonObject>,
        parts: List<Part>,
        filtered: Boolean,
    ) {
        if (filtered) throw ModelError.Refusal("content_filter")
        val answered = parts.any { it is Part.Text || it is Part.ToolCall }
        if (!answered && output.any(::isRefusal)) throw ModelError.Refusal("refusal")
    }

    /** `response.usage`, clamped like Chat Completions usage. */
    fun usage(response: JsonObject): Usage? {
        val usage = response.obj("usage")
        val input = usage?.int("input_tokens")?.coerceAtLeast(0)
        return if (usage == null || input == null) {
            null
        } else {
            val output = (usage.int("output_tokens") ?: 0).coerceAtLeast(0)
            val cached = (usage.obj("input_tokens_details")?.int("cached_tokens") ?: 0).coerceIn(0, input)
            val reasoning = usage.obj("output_tokens_details")?.int("reasoning_tokens")?.coerceIn(0, output)
            Usage(input, output, cacheReadTokens = cached, reasoningTokens = reasoning)
        }
    }

    /** Response id, reported model, status, incomplete reason and contradicted delta channels, for the log. */
    fun metadata(
        response: JsonObject,
        incompleteReason: String?,
        diverged: List<String>,
    ): JsonObject? {
        val meta =
            buildJsonObject {
                response.str("id")?.let { put("id", it) }
                response.str("model")?.let { put("model", it) }
                response.str("status")?.let { put("status", it) }
                incompleteReason?.let { put("incomplete_reason", it) }
                if (diverged.isNotEmpty()) putJsonArray(PREVIEW_DIVERGED) { diverged.forEach { add(it) } }
            }
        return meta.takeIf { it.isNotEmpty() }
    }

    /** A reasoning item's readable text: its summary or its raw reasoning text ([rawFirst] picks between both). */
    private fun summary(
        item: JsonObject,
        rawFirst: Boolean,
    ): String? =
        if (rawFirst) {
            texts(item, "content") ?: texts(item, "summary")
        } else {
            texts(item, "summary") ?: texts(item, "content")
        }

    private fun texts(
        item: JsonObject,
        key: String,
    ): String? =
        item
            .array(key)
            ?.mapNotNull { (it as? JsonObject)?.str("text") }
            ?.joinToString(TEXT_SEPARATOR)
            ?.takeIf { it.isNotEmpty() }

    private fun text(item: JsonObject): String? =
        item
            .array("content")
            ?.mapNotNull { (it as? JsonObject)?.takeIf { c -> c.str("type") == "output_text" }?.str("text") }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() }

    private fun call(
        item: JsonObject,
        redactor: Redactor,
    ): Part.ToolCall {
        val id = item.str("call_id")
        val name = item.str("name")
        if (id == null || name == null) {
            val shown = redactor.text(item.toString()).take(SNIPPET)
            throw ModelError.BadResponse("function_call without call_id or name: $shown")
        }
        return try {
            Part.ToolCall(ToolCallId(id), name, item.str("arguments")?.ifEmpty { null } ?: "{}")
        } catch (e: IllegalArgumentException) {
            throw ModelError.BadResponse("unusable call_id '${redactor.text(id).take(SNIPPET)}'", redactor.cause(e))
        }
    }

    private fun isRefusal(item: JsonObject): Boolean =
        item.str("type") == "message" &&
            item.array("content").orEmpty().any { (it as? JsonObject)?.str("type") == "refusal" }
}
