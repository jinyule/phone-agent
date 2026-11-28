package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The `messages` of one request, by the rules [MessagesWire] states. */
internal class WireMessages(
    private val provider: ProviderId,
    private val rules: WireRules,
    private val images: ImageData,
) {
    /** The merged wire messages of [request], `tool_result` blocks first, with the recent breakpoints. */
    fun of(request: ChatRequest): List<WireMessage> {
        val merged = mutableListOf<WireMessage>()
        request.messages.forEachIndexed { index, message ->
            val role = if (message.role == Role.ASSISTANT) WireMessage.ASSISTANT else WireMessage.USER
            val blocks = message.parts.mapNotNull { block(index, it, message.role) }
            if (blocks.isEmpty()) return@forEachIndexed
            val last = merged.lastOrNull()
            if (last?.role == role) last.blocks += blocks else merged += WireMessage(role, blocks.toMutableList())
        }
        val recent = request.cache.recentMessages
        return merged.mapIndexed { index, message ->
            val ordered = message.resultsFirst()
            if (index < merged.size - recent) ordered else ordered.withBreakpoint()
        }
    }

    private fun block(
        index: Int,
        part: Part,
        role: Role,
    ): JsonObject? =
        when (part) {
            is Part.Text -> part.text.takeIf { it.isNotEmpty() }?.let(::textBlock)
            is Part.Image -> if (role == Role.ASSISTANT) assistantImage(index) else image(part)
            is Part.ToolCall -> toolUse(part)
            is Part.Reasoning -> reasoning(index, part)
            is Part.ToolResult -> toolResult(part)
        }

    private fun assistantImage(index: Int): Nothing =
        invalid("messages", "message $index: Anthropic does not take images in assistant messages")

    private fun image(part: Part.Image): JsonObject {
        if (!rules.inlineImages) return textBlock(MessagesWire.IMAGE_OMITTED)
        return buildJsonObject {
            put("type", "image")
            put(
                "source",
                buildJsonObject {
                    put("type", "base64")
                    put("media_type", part.mime)
                    put("data", images[part.blob])
                },
            )
        }
    }

    private fun toolUse(call: Part.ToolCall): JsonObject =
        buildJsonObject {
            put("type", "tool_use")
            put("id", call.id.value)
            put("name", call.name)
            put("input", MessagesWire.argumentsObject(call.argsJson))
        }

    /**
     * A stored Anthropic block of this provider, verbatim, whichever of its models produced it: the
     * API leaves out what the current model cannot read, and a block stripped here would be lost
     * for a switch back, break the blocks after it, or (compaction) lose history (model-layer
     * §3.3). Blocks of other providers and other kinds are dropped.
     */
    private fun reasoning(
        index: Int,
        part: Part.Reasoning,
    ): JsonObject? {
        if (part.provider != provider || part.kind !in ANTHROPIC_KINDS) return null
        return part.opaque as? JsonObject
            ?: invalid("messages", "message $index: stored ${part.kind} block is not a JSON object")
    }

    private fun toolResult(result: Part.ToolResult): JsonObject {
        val content = result.parts.mapNotNull { block(-1, it, Role.TOOL) }
        return buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", result.id.value)
            if (content.isNotEmpty()) put("content", JsonArray(content))
            if (result.isError) put("is_error", true)
        }
    }
}

private val ANTHROPIC_KINDS =
    setOf(
        ReasoningKind.ANTHROPIC_THINKING,
        ReasoningKind.ANTHROPIC_REDACTED_THINKING,
        ReasoningKind.ANTHROPIC_COMPACTION,
    )

/** One wire message under construction. */
internal class WireMessage(
    val role: String,
    val blocks: MutableList<JsonObject>,
) {
    /** `tool_result` blocks must open a user message; the rest keep their order. */
    fun resultsFirst(): WireMessage {
        val (results, rest) = blocks.partition { it["type"] == TOOL_RESULT }
        return WireMessage(role, (results + rest).toMutableList())
    }

    /** A breakpoint on the last block that can carry one (thinking blocks cannot); unchanged without one. */
    fun withBreakpoint(): WireMessage {
        val at = blocks.indexOfLast { it["type"] !in UNCACHEABLE }
        if (at < 0) return this
        return WireMessage(role, blocks.toMutableList().also { it[at] = withCache(it[at]) })
    }

    fun json(): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", JsonArray(blocks))
        }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        private val TOOL_RESULT = JsonPrimitive("tool_result")
        private val UNCACHEABLE = setOf(JsonPrimitive("thinking"), JsonPrimitive("redacted_thinking"))
    }
}

/** Requests the API would refuse, refused before anything is sent. */
internal object Preflight {
    /**
     * Rejects calls without results and results without calls: after an assistant message with
     * calls, only tool messages may follow until each call has its result.
     */
    fun toolPairs(messages: List<Message>) {
        var open = emptySet<ToolCallId>()
        messages.forEachIndexed { index, message ->
            if (message.role != Role.TOOL) {
                if (open.isNotEmpty()) invalid("messages", "message $index: tool calls $open have no result")
                open = message.parts.filterIsInstance<Part.ToolCall>().mapTo(mutableSetOf()) { it.id }
            } else {
                val answered = message.parts.filterIsInstance<Part.ToolResult>().map { it.id }
                val stray = answered.filter { it !in open }
                if (stray.isNotEmpty()) {
                    invalid(
                        "messages",
                        "message $index: results $stray answer no pending tool call",
                    )
                }
                open = open - answered.toSet()
            }
        }
        if (open.isNotEmpty()) invalid("messages", "message ${messages.size}: tool calls $open have no result")
    }

    /** Strict tools need `additionalProperties: false` on every object schema; the first offender, or `null`. */
    fun strictProblem(
        schema: JsonObject,
        path: String = "",
    ): String? {
        val at = path.ifEmpty { "the top level" }
        if (schema["type"] == JsonPrimitive("object") && schema["additionalProperties"] != JsonPrimitive(false)) {
            return "strict tools need \"additionalProperties\": false at $at"
        }
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val children =
            properties.map { (name, child) -> child to (if (path.isEmpty()) name else "$path.$name") } +
                listOfNotNull(schema["items"]?.let { it to "$path.items" })
        return children.firstNotNullOfOrNull { (child, childPath) ->
            (child as? JsonObject)?.let { strictProblem(it, childPath) }
        }
    }
}

/** A `text` block. */
internal fun textBlock(text: String): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", text)
    }

/** [block] with an ephemeral `cache_control` breakpoint. */
internal fun withCache(block: JsonObject): JsonObject = JsonObject(block + ("cache_control" to EPHEMERAL))

private val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
