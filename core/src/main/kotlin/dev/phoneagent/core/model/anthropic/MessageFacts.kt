package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The message-level facts of one reply, from `message_start` and `message_delta`: the stop
 * reason and its details, usage, and what goes into `providerMetadata`. Several `message_delta`
 * events may come; the last value of each field wins.
 */
internal class MessageFacts(
    private val protocol: Protocol,
) {
    private val usage = LinkedHashMap<String, JsonElement>()
    private var messageId: String? = null
    private var startModel: String? = null
    private var stopSequence: JsonElement? = null
    private var stopDetails: JsonObject? = null
    private var inputTransformations: JsonElement? = null
    private var contextManagement: JsonElement? = null

    /** The last `stop_reason`, or `null` when none came. */
    var stopReason: String? = null
        private set

    /** `stop_details.category` of a refusal. */
    val refusalCategory: String? get() = stopDetails?.str("category")

    /** The `message` of `message_start`; it must carry no content yet. */
    fun start(message: JsonObject) {
        messageId = message.str("id")
        startModel = message.str("model")
        (message["content"] as? JsonArray)?.let { if (it.isNotEmpty()) protocol.bad("message_start with content") }
        message.obj("usage")?.let(::merge)
        extras(message)
    }

    /** A `message_delta` event. */
    fun delta(event: JsonObject) {
        val delta = event.obj("delta") ?: protocol.bad("message_delta without a delta")
        if ("stop_reason" in delta) stopReason = delta.str("stop_reason")
        if ("stop_sequence" in delta) stopSequence = delta["stop_sequence"]?.orNull()
        if ("stop_details" in delta) stopDetails = delta.obj("stop_details")
        event.obj("usage")?.let(::merge)
        extras(event)
    }

    /**
     * Usage in neutral terms; `null` when the reply carried none. Input counts the uncached,
     * cache-read and cache-written tokens together.
     */
    fun usage(): Usage? {
        if (usage.isEmpty()) return null
        val input = count("input_tokens")
        val read = count("cache_read_input_tokens")
        val written = count("cache_creation_input_tokens")
        val thinking =
            (usage["output_tokens_details"] as? JsonObject)?.let {
                count("output_tokens_details.thinking_tokens", it["thinking_tokens"])
            }
        val total = input.toLong() + read + written
        if (total > Int.MAX_VALUE) protocol.bad("the input token total is out of range")
        return Usage(
            inputTokens = total.toInt(),
            outputTokens = count("output_tokens"),
            cacheReadTokens = read,
            cacheWriteTokens = written,
            reasoningTokens = thinking,
        )
    }

    /**
     * `providerMetadata`: identity, the model asked for and the one that served, stop details,
     * server-side edits and any fallback. A field holding a member name or a value (string, number
     * or boolean) that masking would change (a request secret or a credential shape, as in an error
     * body) is left out whole, never half masked.
     */
    fun metadata(
        reason: String,
        content: ContentBlocks.Assembled,
        requested: ModelId,
    ): JsonObject {
        val metadata =
            buildJsonObject {
                messageId?.let { put("id", it) }
                put("requested_model", requested.value)
                servedModel(content)?.let { put("served_model", it) }
                put("stop_reason", reason)
                stopSequence?.let { put("stop_sequence", it) }
                stopDetails?.let { put("stop_details", it) }
                inputTransformations?.let { put("input_transformations", it) }
                contextManagement?.let { put("context_management", it) }
                usage["iterations"]?.let { put("usage_iterations", it) }
                if (content.fallbacks.isNotEmpty()) put("fallbacks", JsonArray(content.fallbacks))
                if (content.dropped.isNotEmpty()) put("fallback_discarded", JsonArray(content.dropped))
                if (content.diverged.isNotEmpty()) {
                    put(
                        PREVIEW_DIVERGED,
                        JsonArray(content.diverged.map(::JsonPrimitive)),
                    )
                }
            }
        return JsonObject(metadata.filterValues { !protocol.masks(it) })
    }

    /**
     * The model that produced the reply: the target of the last `fallback` block, else the model of
     * the last `fallback_message` iteration (a turn routed to the fallback model has no block), else
     * the `message_start` model, which names the requested model when a fallback came mid-stream.
     */
    private fun servedModel(content: ContentBlocks.Assembled): String? =
        content.servedBy
            ?: (usage["iterations"] as? JsonArray)
                ?.lastOrNull { (it as? JsonObject)?.str("type") == "fallback_message" }
                ?.let { (it as JsonObject).str("model") }
            ?: startModel

    private fun extras(source: JsonObject) {
        source["input_transformations"]?.orNull()?.let { inputTransformations = it }
        source["context_management"]?.orNull()?.let { contextManagement = it }
    }

    /** Usage values are running totals: a later value replaces an earlier one. */
    private fun merge(update: JsonObject) {
        update.forEach { (key, value) -> if (value !is JsonNull) usage[key] = value }
    }

    private fun count(key: String): Int = count(key, usage[key]) ?: 0

    /** The count [value] of usage field [name]; errors name the field and the kind of value, never the value. */
    private fun count(
        name: String,
        value: JsonElement?,
    ): Int? {
        if (value == null || value is JsonNull) return null
        val long =
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: protocol.bad("usage $name is not a count: ${Unquoted.shape(value)}")
        if (long !in 0..Int.MAX_VALUE) protocol.bad("usage $name is out of range")
        return long.toInt()
    }
}
