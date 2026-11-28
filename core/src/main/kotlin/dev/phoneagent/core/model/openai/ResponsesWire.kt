package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.openai.WireSupport.IMAGE_OMITTED
import dev.phoneagent.core.model.openai.WireSupport.TEXT_SEPARATOR
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Neutral request → OpenAI Responses JSON, as a pure function (model-layer §3.2, golden-tested).
 * Stateless: `store:false`, never `previous_response_id`, the whole conversation in `input` every
 * time. The system blocks go to `instructions`; the neutral model has no system messages, so no
 * input item ever has the `system` role.
 *
 * - User messages: one `message` item with `input_text` / `input_image` parts.
 * - Assistant messages: their parts in order — this provider's and model's encrypted `reasoning`
 *   items verbatim (others dropped, and items without `encrypted_content` cannot be replayed
 *   statelessly), `output_text` messages, `function_call` items.
 * - Tool messages: a `function_call_output` per result; its `output` is the text, or a content
 *   array once the result holds an image. `isError` has no wire field: the text states the failure.
 *
 * Everything besides the neutral request comes from the request's snapshot, [wire]: images inline
 * only when it says so, `parallel_tool_calls:false` with tools, `include`, the reasoning setting
 * and the output cap.
 */
internal class ResponsesWire(
    private val provider: ProviderId,
    private val model: ModelId,
    private val wire: WireProfile,
) {
    private val vision = wire.images == ImageMode.INLINE

    /** [request] as sent; its own snapshot is [wire]. */
    fun body(
        request: ChatRequest,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            put("model", model.value)
            if (request.system.isNotEmpty()) put("instructions", WireSupport.systemText(request.system))
            put("input", input(request.messages, images))
            if (request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.map(::tool)))
                put("tool_choice", "auto")
                if (wire.disableParallelToolCalls) put("parallel_tool_calls", false)
            }
            put("store", false)
            put("stream", true)
            if (wire.includeEncryptedReasoning) putJsonArray("include") { add(ENCRYPTED_REASONING) }
            wire.reasoningEffort?.let { WireSupport.put(this, it) }
            wire.maxTokensField?.let { field -> request.maxOutputTokens?.let { put(field, it) } }
        }

    private fun input(
        messages: List<Message>,
        images: ImageData,
    ): JsonArray =
        buildJsonArray {
            for (message in messages) {
                when (message.role) {
                    Role.USER -> add(user(message.parts, images))
                    Role.ASSISTANT -> assistant(message).forEach(::add)
                    Role.TOOL -> message.parts.filterIsInstance<Part.ToolResult>().forEach { add(output(it, images)) }
                }
            }
        }

    private fun user(
        parts: List<Part>,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", JsonArray(parts.map { content(it, images) }))
        }

    private fun content(
        part: Part,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            if (part is Part.Image && vision) {
                put("type", "input_image")
                put("image_url", WireSupport.dataUrl(part.mime, images[part.blob]))
                put("detail", "auto")
            } else {
                put("type", "input_text")
                put("text", if (part is Part.Text) part.text else IMAGE_OMITTED)
            }
        }

    private fun assistant(message: Message): List<JsonObject> =
        message.parts.mapNotNull { part ->
            when (part) {
                is Part.Reasoning -> {
                    replayable(part)
                }

                is Part.Text -> {
                    outputText(part.text)
                }

                is Part.ToolCall -> {
                    buildJsonObject {
                        put("type", "function_call")
                        put("call_id", part.id.value)
                        put("name", part.name)
                        put("arguments", part.argsJson)
                    }
                }

                // Message invariants keep images and results out of assistant messages.
                is Part.Image, is Part.ToolResult -> {
                    null
                }
            }
        }

    private fun outputText(text: String): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "output_text")
                    put("text", text)
                }
            }
        }

    /** The stored reasoning item, verbatim, when this provider and model can take it back. */
    private fun replayable(part: Part.Reasoning): JsonObject? {
        val item = part.opaque as? JsonObject
        val own = part.kind == ReasoningKind.RESPONSES_ENCRYPTED && part.provider == provider && part.model == model
        return item?.takeIf { own && it.str("encrypted_content") != null }
    }

    private fun output(
        result: Part.ToolResult,
        images: ImageData,
    ): JsonObject =
        buildJsonObject {
            put("type", "function_call_output")
            put("call_id", result.id.value)
            val output: JsonElement =
                if (vision && result.parts.any { it is Part.Image }) {
                    JsonArray(result.parts.map { content(it, images) })
                } else {
                    JsonPrimitive(
                        result.parts.joinToString(TEXT_SEPARATOR) { if (it is Part.Text) it.text else IMAGE_OMITTED },
                    )
                }
            put("output", output)
        }

    private fun tool(def: ToolDef): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("name", def.name)
            put("description", def.description)
            put("parameters", def.parameters)
            // Tool schemas are not written for strict mode (optional fields, free-form objects).
            put("strict", false)
        }

    companion object {
        /** The `include` value asking for replayable encrypted reasoning. */
        const val ENCRYPTED_REASONING: String = "reasoning.encrypted_content"
    }
}

/**
 * ChatGPT plan sign-in constraints (model-layer §3.2, from OpenAI's "Preview limitations"):
 * the request must be `store:false` and `stream:true`; only the top-level fields in [ALLOWED]
 * survive (everything else, e.g. `max_output_tokens`, `temperature`, `metadata`, `background`,
 * `conversation`, is stripped); `system` input items become `developer` items; tools other than
 * `function` / `custom` are refused before sending.
 */
internal object PlanMode {
    /** Top-level request fields the plan channel accepts. */
    val ALLOWED: Set<String> =
        setOf(
            "model",
            "instructions",
            "input",
            "tools",
            "tool_choice",
            "parallel_tool_calls",
            "store",
            "stream",
            "include",
            "reasoning",
            "text",
            "prompt_cache_key",
        )

    /** Tool types the plan channel runs. */
    val TOOL_TYPES: Set<String> = setOf("function", "custom")

    /** [body] cut down to what the plan channel accepts; throws [ModelError.InvalidRequest] for unsupported tools. */
    fun enforce(body: JsonObject): JsonObject {
        (body["tools"] as? JsonArray)?.forEach { tool ->
            val type = (tool as? JsonObject)?.str("type")
            if (type !in TOOL_TYPES) {
                throw ModelError.InvalidRequest("tools", "ChatGPT plan sign-in does not run '$type' tools")
            }
        }
        val input = (body["input"] as? JsonArray)?.let { items -> JsonArray(items.map(::withoutSystemRole)) }
        val kept = body.filterKeys { it in ALLOWED }.toMutableMap()
        input?.let { kept["input"] = it }
        kept["store"] = JsonPrimitive(false)
        kept["stream"] = JsonPrimitive(true)
        return JsonObject(kept)
    }

    private fun withoutSystemRole(item: JsonElement): JsonElement {
        val obj = item as? JsonObject ?: return item
        return if (obj.str("role") == "system") JsonObject(obj + ("role" to JsonPrimitive("developer"))) else obj
    }
}
