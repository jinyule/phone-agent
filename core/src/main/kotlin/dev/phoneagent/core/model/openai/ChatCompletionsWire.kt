package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.openai.WireSupport.IMAGE_OMITTED
import dev.phoneagent.core.model.openai.WireSupport.TEXT_SEPARATOR
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Chat Completions request quirks of one provider (model-layer §3.1), part of its [WireOptions].
 * Presets set them and custom providers start from the defaults; "test and save" turns off
 * `parallel_tool_calls` / `stream_options` when the provider refuses them ([ProviderProbe]).
 * Models read them once and never change them.
 *
 * @property parallelToolCalls send `parallel_tool_calls:false` with tools (the loop runs one call per step).
 * @property streamUsage send `stream_options.include_usage` (usage in the last chunk).
 * @property maxTokensField field that carries `ChatRequest.maxOutputTokens`.
 * @property reasoningEffort how a requested reasoning level is sent.
 * @property recoverLeakedCalls turn tool calls written into the answer text into calls
 *   ([LeakedToolMarkup]). Off by default: text that looks like a call is otherwise an answer, and
 *   a recovered call never went through the provider's tool channel. On only for providers known
 *   to leak them: DeepSeek models that write DSML, Qwen-family models that write `<tool_call>`
 *   JSON, and Ollama, whose chat templates leave unparsed calls of local models in the text.
 *   Even then only complete calls of the request's tools that end the answer are recovered, and
 *   only when the reply has no native call; their ids are listed in the reply's
 *   `providerMetadata.recovered_tool_calls`.
 */
public data class ChatQuirks(
    val parallelToolCalls: Boolean = true,
    val streamUsage: Boolean = true,
    val maxTokensField: MaxTokensField = MaxTokensField.MAX_TOKENS,
    val reasoningEffort: ReasoningEffortStyle = ReasoningEffortStyle.REASONING_EFFORT,
    val recoverLeakedCalls: Boolean = false,
) {
    /** Defaults. */
    public companion object {
        /** What an unknown OpenAI-compatible endpoint gets. */
        public val DEFAULT: ChatQuirks = ChatQuirks()
    }
}

/** Where a Chat Completions provider takes the output cap (`ChatRequest.maxOutputTokens`). */
public enum class MaxTokensField(
    /** The request field. */
    public val wireName: String,
) {
    /** `max_tokens`: what OpenAI-compatible gateways take. */
    MAX_TOKENS("max_tokens"),

    /** `max_completion_tokens`: OpenAI's own endpoint, where `max_tokens` is deprecated. */
    MAX_COMPLETION_TOKENS("max_completion_tokens"),
}

/** Where a Chat Completions provider takes the reasoning level. */
public enum class ReasoningEffortStyle {
    /** Never sent: the provider has no such parameter (or it is unknown). */
    NONE,

    /** Top-level `reasoning_effort` (OpenAI, and gateways that copy it). */
    REASONING_EFFORT,

    /** `reasoning: {effort}` (OpenRouter). */
    OPENROUTER,
}

/**
 * Neutral request → Chat Completions JSON, as a pure function (golden-tested). Mapping:
 *
 * - The system blocks become one leading `system` message.
 * - A user message is a string, or a content array once it holds an image (`image_url` with a
 *   base64 `data:` URL; a placeholder text when the model has no vision).
 * - A tool message becomes one `tool` message per result. Tool messages carry text only, so the
 *   images of all its results follow in one `user` message, each group introduced by its call id
 *   (a result with nothing but images says so in its `tool` message).
 *   `isError` has no wire field here: the result text itself states the failure.
 * - An assistant message carries its text, its tool calls, and — when [echoReasoning] — the reasoning
 *   this provider and model produced, under [reasoningField]. Other providers' reasoning is dropped.
 */
internal class ChatCompletionsWire(
    private val provider: ProviderId,
    private val model: ModelId,
    private val reasoningField: String?,
    private val echoReasoning: Boolean,
    private val vision: Boolean,
    private val reasoningLevels: List<ReasoningLevel>?,
) {
    fun body(
        request: ChatRequest,
        images: ImageData,
        options: ChatQuirks,
    ): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("messages", messages(request, images))
            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map(::tool)))
                put("tool_choice", "auto")
                if (options.parallelToolCalls) put("parallel_tool_calls", false)
            }
            request.maxOutputTokens?.let { put(options.maxTokensField.wireName, it) }
            WireSupport.effort(request.reasoning, reasoningLevels)?.let { effort ->
                when (options.reasoningEffort) {
                    ReasoningEffortStyle.NONE -> Unit
                    ReasoningEffortStyle.REASONING_EFFORT -> put("reasoning_effort", effort)
                    ReasoningEffortStyle.OPENROUTER -> putJsonObject("reasoning") { put("effort", effort) }
                }
            }
            put("stream", true)
            if (options.streamUsage) putJsonObject("stream_options") { put("include_usage", true) }
        }

    private fun messages(
        request: ChatRequest,
        images: ImageData,
    ): JsonArray =
        buildJsonArray {
            if (request.system.isNotEmpty()) {
                addJsonObject {
                    put("role", "system")
                    put("content", WireSupport.systemText(request.system))
                }
            }
            for (message in request.messages) {
                when (message.role) {
                    Role.USER -> add(user(message.parts, images))
                    Role.ASSISTANT -> add(assistant(message))
                    Role.TOOL -> tool(message, images).forEach(::add)
                }
            }
        }

    private fun user(
        parts: List<Part>,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            put("role", "user")
            if (vision && parts.any { it is Part.Image }) {
                put("content", JsonArray(parts.map { content(it, images) }))
            } else {
                put("content", parts.joinToString(TEXT_SEPARATOR, transform = ::plainText))
            }
        }

    private fun content(
        part: Part,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            if (part is Part.Image) {
                put("type", "image_url")
                putJsonObject("image_url") { put("url", WireSupport.dataUrl(part.mime, images[part.blob])) }
            } else {
                put("type", "text")
                put("text", plainText(part))
            }
        }

    private fun tool(
        message: Message,
        images: ImageData,
    ): List<JsonObject> {
        val results = message.parts.filterIsInstance<Part.ToolResult>()
        val wire =
            results.map { result ->
                val visible = if (vision) result.parts.filterIsInstance<Part.Text>() else result.parts
                val text = visible.joinToString(TEXT_SEPARATOR, transform = ::plainText)
                val onlyImages = vision && text.isEmpty() && result.parts.any { it is Part.Image }
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.id.value)
                    put("content", if (onlyImages) IMAGES_FOLLOW else text)
                }
            }
        val withImages = if (vision) results.filter { r -> r.parts.any { it is Part.Image } } else emptyList()
        if (withImages.isEmpty()) return wire
        val follow =
            buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (result in withImages) {
                        add(content(Part.Text("Images from tool call ${result.id.value}:"), images))
                        result.parts.filterIsInstance<Part.Image>().forEach { add(content(it, images)) }
                    }
                }
            }
        return wire + follow
    }

    private fun assistant(message: Message): JsonObject {
        val text = message.parts.filterIsInstance<Part.Text>().joinToString(TEXT_SEPARATOR) { it.text }
        val calls = message.parts.filterIsInstance<Part.ToolCall>()
        val reasoning = echoed(message)
        return buildJsonObject {
            put("role", "assistant")
            put("content", if (text.isEmpty() && calls.isNotEmpty()) JsonNull else JsonPrimitive(text))
            if (reasoning != null && reasoningField != null) put(reasoningField, reasoning)
            if (calls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (call in calls) {
                        addJsonObject {
                            put("id", call.id.value)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.argsJson)
                            }
                        }
                    }
                }
            }
        }
    }

    /** This provider's and model's reasoning text in [message], when it is echoed at all. */
    private fun echoed(message: Message): String? {
        val field = reasoningField?.takeIf { echoReasoning }
        val texts =
            message.parts
                .filterIsInstance<Part.Reasoning>()
                .filter { it.kind == ReasoningKind.CHAT_REASONING_FIELD }
                .filter { it.provider == provider && it.model == model }
                .mapNotNull { field?.let { f -> (it.opaque as? JsonObject)?.get(f) as? JsonPrimitive } }
                .filter { it.isString }
                .map { it.content }
        return if (texts.isEmpty()) null else texts.joinToString("")
    }

    private fun tool(def: ToolDef): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", def.name)
                put("description", def.description)
                put("parameters", def.parameters)
            }
        }

    private fun plainText(part: Part): String =
        when (part) {
            is Part.Text -> part.text

            is Part.Image -> IMAGE_OMITTED

            // Message invariants keep the other kinds out of user and tool content.
            is Part.ToolCall, is Part.ToolResult, is Part.Reasoning -> ""
        }

    private companion object {
        /** Tool content of a result that is nothing but images (some providers refuse empty content). */
        const val IMAGES_FOLLOW = "[images follow in the next message]"
    }
}

/** Builds the opaque block a Chat Completions reasoning field is stored as: `{"<field>": "<text>"}`. */
internal fun chatReasoningOpaque(
    field: String,
    text: String,
): JsonObject = buildJsonObject { put(field, text) }
