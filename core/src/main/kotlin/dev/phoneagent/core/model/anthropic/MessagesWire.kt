package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ToolDef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64

/**
 * Neutral request → Messages API body, mapping version 1 (model-layer §3.3, §5). Pure: the same
 * request (with its `wire` snapshot) and image bytes always give the same bytes. The rules:
 *
 * - **Body order**: `model`, `max_tokens` (`maxOutputTokens`, else [AnthropicWire.DEFAULT_MAX_TOKENS]),
 *   `stream: true`, `system`, `tools`, `tool_choice`, `thinking`, `output_config`, `fallbacks`,
 *   `context_management`, `messages`. No sampling parameters, no forced tool choice.
 * - **System**: one `text` block per non-empty prompt block.
 * - **Tools**: `name`, `description`, `input_schema`, then `strict` and `eager_input_streaming`
 *   when the snapshot asks; with tools, `tool_choice: {type: auto, disable_parallel_tool_use: true}`
 *   (`wire.disableParallelToolCalls`, which [WireRules] requires).
 * - **Messages**: USER parts become `text` / `image` blocks (base64, or [IMAGE_OMITTED] for a
 *   model without vision); ASSISTANT parts become `text`, `tool_use` (`input` = the arguments
 *   when they parse as a JSON object, else `{}` by intent: [argumentsObject]) and, only for this
 *   provider and model, the stored Anthropic block of a [Part.Reasoning] verbatim; TOOL parts
 *   become `tool_result` blocks (`is_error` only when true). Empty text is skipped and messages
 *   left empty are omitted. Consecutive messages with the same wire role are merged,
 *   `tool_result` blocks first.
 * - **Cache** (`cache_control: {type: ephemeral}`, [dev.phoneagent.core.model.CacheLayout]): on
 *   the last tool, the last system block, and the last block that can carry one in each of the
 *   last `recentMessages` wire messages (thinking blocks cannot).
 *
 * Requests the API would refuse are refused here first with [ModelError.InvalidRequest]: tool
 * calls without results (or results without calls), a last message on the assistant side
 * (prefill), images in assistant messages, a stored reasoning block that is not an object, and
 * strict tools whose schema allows extra properties.
 */
internal class MessagesWire(
    private val provider: ProviderId,
    private val model: ModelId,
) {
    /** The request body. */
    fun body(
        request: ChatRequest,
        rules: WireRules,
        images: ImageData,
    ): JsonObject {
        Preflight.toolPairs(request.messages)
        val messages = WireMessages(provider, model, rules, images).of(request)
        if (messages.isEmpty()) invalid("messages", "nothing to send: every message is empty")
        if (messages.last().role != WireMessage.USER) {
            invalid("messages", "the last message is the assistant's; prefill is not supported")
        }
        return buildJsonObject {
            put("model", model.value)
            put("max_tokens", request.maxOutputTokens ?: AnthropicWire.DEFAULT_MAX_TOKENS)
            put("stream", true)
            system(request)?.let { put("system", it) }
            if (request.tools.isNotEmpty()) {
                put("tools", tools(request.tools, rules, request.cache.tools))
                put("tool_choice", TOOL_CHOICE)
            }
            thinking(rules)?.let { put("thinking", it) }
            rules.effort?.let { effort -> put("output_config", buildJsonObject { put("effort", effort) }) }
            if (rules.fallbacks) put("fallbacks", AnthropicSettings.FALLBACKS_DEFAULT)
            contextManagement(request.contextEdits)?.let { put("context_management", it) }
            put("messages", JsonArray(messages.map { it.json() }))
        }
    }

    /** The `anthropic-beta` values the request needs, in a fixed order. */
    fun betas(
        request: ChatRequest,
        rules: WireRules,
    ): List<String> {
        val edits = request.contextEdits as? ContextEditPolicy.ServerSide
        return listOfNotNull(
            BETA_FALLBACK.takeIf { rules.fallbacks },
            BETA_BINDING.takeIf { rules.prefixMismatch != null },
            BETA_CONTEXT.takeIf { edits?.clearToolUsesAboveTokens != null },
            BETA_COMPACT.takeIf { edits?.compactAboveTokens != null },
        )
    }

    private fun system(request: ChatRequest): JsonArray? {
        val blocks = request.system.filter { it.text.isNotEmpty() }.map { textBlock(it.text) }
        if (blocks.isEmpty()) return null
        return JsonArray(if (request.cache.system) blocks.dropLast(1) + withCache(blocks.last()) else blocks)
    }

    private fun tools(
        tools: List<ToolDef>,
        rules: WireRules,
        cacheLast: Boolean,
    ): JsonArray {
        val wire =
            tools.map { tool ->
                if (rules.strict) {
                    Preflight
                        .strictProblem(
                            tool.parameters,
                        )?.let { invalid("tools", "${tool.name}: $it") }
                }
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                    if (rules.strict) put("strict", true)
                    if (rules.eager) put("eager_input_streaming", true)
                }
            }
        return JsonArray(if (cacheLast) wire.dropLast(1) + withCache(wire.last()) else wire)
    }

    private fun thinking(rules: WireRules): JsonObject? {
        if (!rules.adaptiveThinking) return null
        return buildJsonObject {
            put("type", AnthropicSettings.ADAPTIVE)
            rules.display?.let { put("display", it) }
            rules.prefixMismatch?.let { behavior ->
                put("block_binding", buildJsonObject { put("prefix_mismatch_behavior", behavior) })
            }
        }
    }

    /** Server-side clearing and compaction (beta shapes of the Java SDK 3.3.0); `null` when nothing is enabled. */
    private fun contextManagement(policy: ContextEditPolicy): JsonObject? {
        val server = policy as? ContextEditPolicy.ServerSide ?: return null
        val edits =
            listOfNotNull(
                server.clearToolUsesAboveTokens?.let { tokens ->
                    buildJsonObject {
                        put("type", "clear_tool_uses_20250919")
                        put("trigger", count("input_tokens", tokens))
                        put("keep", count("tool_uses", server.keepToolUses))
                    }
                },
                server.compactAboveTokens?.let { tokens ->
                    buildJsonObject {
                        put("type", "compact_20260112")
                        put("trigger", count("input_tokens", tokens))
                    }
                },
            )
        return if (edits.isEmpty()) null else buildJsonObject { put("edits", JsonArray(edits)) }
    }

    private fun count(
        type: String,
        value: Int,
    ) = buildJsonObject {
        put("type", type)
        put("value", value)
    }

    companion object {
        /**
         * Shown instead of an image to a model without vision; model-visible, so in Chinese, and
         * fixed for prefix stability.
         */
        const val IMAGE_OMITTED: String = "[图片已省略：当前模型不能看图]"

        const val BETA_FALLBACK = "server-side-fallback-2026-07-01"
        const val BETA_BINDING = "thinking-binding-controls-2026-08-01"
        const val BETA_CONTEXT = "context-management-2025-06-27"
        const val BETA_COMPACT = "compact-2026-01-12"

        private val TOOL_CHOICE =
            buildJsonObject {
                put("type", "auto")
                put("disable_parallel_tool_use", true)
            }

        /**
         * The arguments as the `input` object. Mapping version 1 sends `{}` on purpose when the text
         * is not a JSON object (a cut-off or invalid stream): `input` must be an object, the raw text
         * stays in the log (`argsJson`), so the request is still a function of the log, and no
         * made-up field such as `{"_raw": …}` is sent that the model might imitate. Changing this
         * means a new mapping version.
         */
        @Suppress("SwallowedException") // Not an error: the raw text stays in the log, the wire needs an object.
        fun argumentsObject(argsJson: String): JsonObject =
            try {
                Json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
            } catch (notJson: SerializationException) {
                // `{}` is deterministic, so the request stays a function of the log.
                JsonObject(emptyMap())
            }
    }
}

/** Base64 of every image a request sends, read once per request. */
internal class ImageData(
    private val base64: Map<BlobRef, String>,
) {
    /** The encoded bytes of [ref]; [load] read every image of the request. */
    operator fun get(ref: BlobRef): String = base64.getValue(ref)

    companion object {
        /** No images (sent as placeholders, or there are none). */
        val NONE: ImageData = ImageData(emptyMap())

        /** The images in [request] (messages and tool results), each read once from [blobs]. */
        suspend fun load(
            request: ChatRequest,
            blobs: BlobSource,
        ): ImageData {
            val refs =
                request.messages
                    .flatMap { it.parts }
                    .flatMap { part -> if (part is Part.ToolResult) part.parts else listOf(part) }
                    .filterIsInstance<Part.Image>()
                    .map { it.blob }
                    .distinct()
            val encoder = Base64.getEncoder()
            return ImageData(refs.associateWith { encoder.encodeToString(read(blobs, it)) })
        }

        @Suppress("SwallowedException") // InvalidRequest has no cause slot; the message keeps the reason.
        private suspend fun read(
            blobs: BlobSource,
            ref: BlobRef,
        ): ByteArray =
            try {
                blobs.bytes(ref)
            } catch (unreadable: IOException) {
                // A missing or corrupted blob makes the logged request impossible to rebuild: not retryable.
                throw ModelError.InvalidRequest("image", "image $ref unreadable: ${unreadable.message}")
            }
    }
}

/** Reads a string member, `null` when absent, not a string, or JSON `null`. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Reads an object member. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** [element] when it is not JSON `null`. */
internal fun JsonElement.orNull(): JsonElement? = takeUnless { it is JsonNull }
