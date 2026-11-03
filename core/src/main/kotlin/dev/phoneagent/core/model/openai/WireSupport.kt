package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.WireSetting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64

/** Pieces both OpenAI-style wire formats share. Pure, except [ImageData.load]. */
internal object WireSupport {
    /** Shown instead of an image to a model without vision; deterministic, so the prefix stays stable. */
    const val IMAGE_OMITTED: String = "[image omitted: this model cannot see images]"

    /** Separator between text parts merged into one wire string. */
    const val TEXT_SEPARATOR: String = "\n\n"

    /** The system prompt blocks as one string. */
    fun systemText(blocks: List<PromptBlock>): String = blocks.joinToString(TEXT_SEPARATOR) { it.text }

    /** Puts [setting] into [body] at its field; a dotted field (`reasoning.effort`) is a nested object. */
    fun put(
        body: JsonObjectBuilder,
        setting: WireSetting,
    ) {
        val head = setting.field.substringBefore('.')
        if (head == setting.field) {
            body.put(head, setting.value)
        } else {
            body.putJsonObject(head) { put(this, WireSetting(setting.field.substringAfter('.'), setting.value)) }
        }
    }

    /**
     * Rejects requests every OpenAI-style endpoint would refuse, before anything is sent: server-side
     * context editing (Anthropic only) and tool calls without results (or results without calls). A
     * request is answered call by call: after an assistant message with calls, only tool messages
     * may follow until every call has its result.
     */
    fun preflight(request: ChatRequest) {
        if (request.contextEdits is ContextEditPolicy.ServerSide) {
            throw ModelError.InvalidRequest("contextEdits", "server-side context editing is Anthropic-only")
        }
        var open = emptySet<ToolCallId>()
        request.messages.forEachIndexed { index, message ->
            open = advance(index, message, open)
        }
        if (open.isNotEmpty()) throw unanswered(request.messages.size, open)
    }

    private fun advance(
        index: Int,
        message: Message,
        open: Set<ToolCallId>,
    ): Set<ToolCallId> {
        if (message.role != Role.TOOL) {
            if (open.isNotEmpty()) throw unanswered(index, open)
            return message.parts
                .filterIsInstance<Part.ToolCall>()
                .map { it.id }
                .toSet()
        }
        val answered = message.parts.filterIsInstance<Part.ToolResult>().map { it.id }
        val stray = answered.filter { it !in open }
        if (stray.isNotEmpty()) {
            throw ModelError.InvalidRequest("messages", "message $index: results $stray answer no pending tool call")
        }
        return open - answered.toSet()
    }

    private fun unanswered(
        index: Int,
        open: Set<ToolCallId>,
    ) = ModelError.InvalidRequest("messages", "message $index: tool calls $open have no result")

    /** `data:` URL of an image; [base64] is the encoded blob. */
    fun dataUrl(
        mime: String,
        base64: String,
    ): String = "data:$mime;base64,$base64"

    /** Reads a string member, `null` when absent, not a string, or JSON `null`. */
    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Reads an integer member, `null` when absent or not an integer. */
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** Reads an object member. */
    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** Reads an array member. */
    fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
}

/** Base64 of every image a request sends, read once per request. */
internal class ImageData(
    private val base64: Map<BlobRef, String>,
) {
    /** The encoded bytes of [ref]; [load] read every image of the request. */
    operator fun get(ref: BlobRef): String = base64.getValue(ref)

    companion object {
        /** No images (the model cannot see them, or there are none). */
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
