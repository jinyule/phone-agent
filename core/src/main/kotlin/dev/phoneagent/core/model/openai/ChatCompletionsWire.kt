package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.WireProfile
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
 * @property leakedCallFormats formats a custom route declares its models write tool calls in when
 *   they leak them into the answer text: what [recoverLeakedCalls] may hold there. None by
 *   default; a preset route declares none here, its preset does ([ProviderPreset.leakedCallFormats]).
 * @property recoverLeakedCalls formats of tool calls written into the answer text that become calls
 *   ([LeakedToolMarkup]). Empty by default and in every preset: a recovered call never went
 *   through the provider's tool channel, and the PolicyEngine cannot yet ask the user to confirm
 *   one, so recovery is an explicit user choice. Only formats the route allows
 *   ([WireOptions.allowedLeakedCalls]) may be set: creating a model, or starting "test and
 *   save", with any other throws [IllegalArgumentException]. When set, a reply becomes calls only
 *   if it has no native call and its whole text, apart from whitespace, is complete calls of the
 *   request's tools in one of these formats; any other text stays as written. Recovered ids are
 *   listed in the reply's `providerMetadata.recovered_tool_calls`.
 */
public data class ChatQuirks(
    val parallelToolCalls: Boolean = true,
    val streamUsage: Boolean = true,
    val maxTokensField: MaxTokensField = MaxTokensField.MAX_TOKENS,
    val reasoningEffort: ReasoningEffortStyle = ReasoningEffortStyle.REASONING_EFFORT,
    val leakedCallFormats: Set<LeakedCallFormat> = emptySet(),
    val recoverLeakedCalls: Set<LeakedCallFormat> = emptySet(),
) {
    /** Defaults. */
    public companion object {
        /** What an unknown OpenAI-compatible endpoint gets. */
        public val DEFAULT: ChatQuirks = ChatQuirks()
    }
}

/** How a model writes tool calls into the answer text ([ChatQuirks.recoverLeakedCalls]). */
public enum class LeakedCallFormat {
    /** DeepSeek's DSML: `<｜DSML｜function_calls>` blocks of `<｜DSML｜invoke name="…">` with parameters. */
    DSML,

    /** `<tool_call>{"name": …, "arguments": {…}}</tool_call>` blocks (Qwen-family chat templates). */
    TOOL_CALL_TAG,
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
 * - A tool message becomes one `tool` message per result. Tool messages carry text only, and
 *   nothing may come between the `tool` messages answering one assistant turn, so the images of
 *   a whole run of consecutive tool messages follow it in one `user` message, each group
 *   introduced by its call id (a result with nothing but images says so in its `tool` message).
 *   `isError` has no wire field here: the result text itself states the failure.
 * - An assistant message carries its text, its tool calls, and — when [echoReasoning] — the reasoning
 *   this provider and model produced, under [reasoningField]. Other providers' reasoning is dropped.
 */
internal class ChatCompletionsWire(
    private val provider: ProviderId,
    private val model: ModelId,
    private val wire: WireProfile,
) {
    private val reasoningField = wire.reasoningField
    private val echoReasoning = wire.echoReasoning
    private val vision = wire.images == ImageMode.INLINE

    /** [request] as sent; its own snapshot is [wire]. */
    fun body(
        request: ChatRequest,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("messages", messages(request, images))
            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map(::tool)))
                put("tool_choice", "auto")
                if (wire.disableParallelToolCalls) put("parallel_tool_calls", false)
            }
            wire.maxTokensField?.let { field -> request.maxOutputTokens?.let { put(field, it) } }
            wire.reasoningEffort?.let { WireSupport.put(this, it) }
            put("stream", true)
            if (wire.streamOptions) putJsonObject("stream_options") { put("include_usage", true) }
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
            // Results with images in the current run of tool messages; their user message follows the run.
            val withImages = mutableListOf<Part.ToolResult>()
            for (message in request.messages) {
                if (message.role != Role.TOOL) toolImages(withImages, images)?.let(::add)
                when (message.role) {
                    Role.USER -> {
                        add(user(message.parts, images))
                    }

                    Role.ASSISTANT -> {
                        add(assistant(message))
                    }

                    Role.TOOL -> {
                        tool(message).forEach(::add)
                        withImages += message.parts.filterIsInstance<Part.ToolResult>().filter(::hasImage)
                    }
                }
            }
            toolImages(withImages, images)?.let(::add)
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

    private fun hasImage(result: Part.ToolResult): Boolean = vision && result.parts.any { it is Part.Image }

    /** One `tool` message per result: its text, or a placeholder when it is only images. */
    private fun tool(message: Message): List<JsonObject> =
        message.parts.filterIsInstance<Part.ToolResult>().map { result ->
            val visible = if (vision) result.parts.filterIsInstance<Part.Text>() else result.parts
            val text = visible.joinToString(TEXT_SEPARATOR, transform = ::plainText)
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.id.value)
                put("content", if (hasImage(result) && text.isEmpty()) IMAGES_FOLLOW else text)
            }
        }

    /**
     * The user message with the images of [results] (a run of tool messages), or `null` without
     * any; [results] is emptied.
     */
    private fun toolImages(
        results: MutableList<Part.ToolResult>,
        images: ImageData,
    ): JsonObject? {
        if (results.isEmpty()) return null
        val follow =
            buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (result in results) {
                        add(content(Part.Text("Images from tool call ${result.id.value}:"), images))
                        result.parts.filterIsInstance<Part.Image>().forEach { add(content(it, images)) }
                    }
                }
            }
        results.clear()
        return follow
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
