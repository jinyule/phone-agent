package dev.phoneagent.core.model

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One message in the protocol-neutral conversation (model-layer §2). Adapters translate it to
 * and from each wire format; the session log stores the same [Part]s, so a request can be
 * rebuilt from the log byte for byte.
 *
 * Invariants (checked on construction): [Role.TOOL] messages hold only [Part.ToolResult];
 * [Part.ToolResult] appears only there; [Part.ToolCall] and [Part.Reasoning] only in
 * [Role.ASSISTANT] messages.
 */
@Serializable
public data class Message(
    /** Who speaks. */
    val role: Role,
    /** Content in order. */
    val parts: List<Part>,
) {
    init {
        for (part in parts) {
            val allowed =
                when (part) {
                    is Part.Text, is Part.Image -> role != Role.TOOL
                    is Part.ToolCall, is Part.Reasoning -> role == Role.ASSISTANT
                    is Part.ToolResult -> role == Role.TOOL
                }
            require(allowed) { "${part::class.simpleName} is not allowed in a $role message" }
        }
    }
}

/** Speaker of a [Message]. The system prompt is not a message; see `ChatRequest.system`. */
@Serializable
public enum class Role {
    /** The user, or context the agent adds on the user's side (runtime context, injected notes). */
    USER,

    /** The model. */
    ASSISTANT,

    /** Results of the assistant's tool calls. */
    TOOL,
}

/** Content block of a [Message]. Serialized with a `type` discriminator. */
@Serializable
public sealed interface Part {
    /** Plain text. */
    @Serializable
    @SerialName("text")
    public data class Text(
        /** The text. */
        val text: String,
    ) : Part

    /**
     * An image stored as a blob; adapters send the blob's bytes (base64) every time, so the
     * request bytes stay identical across steps.
     */
    @Serializable
    @SerialName("image")
    public data class Image(
        /** The image bytes. */
        val blob: BlobRef,
        /** `image/jpeg`, `image/png` or `image/webp`. */
        val mime: String,
        /** Pixel width, > 0 (token estimation). */
        val width: Int,
        /** Pixel height, > 0. */
        val height: Int,
    ) : Part {
        init {
            require(mime in IMAGE_MIMES) { "Unsupported image mime '$mime'; expected one of $IMAGE_MIMES" }
            require(width > 0 && height > 0) { "Image size must be positive, got ${width}x$height" }
        }
    }

    /**
     * A tool call as the model emitted it. [argsJson] is the raw argument text, kept verbatim even
     * when it is not valid JSON, so it can be echoed back unchanged.
     */
    @Serializable
    @SerialName("tool_call")
    public data class ToolCall(
        /** The call id the result must answer. */
        val id: ToolCallId,
        /** Tool name. */
        val name: String,
        /** Raw arguments text. */
        val argsJson: String,
    ) : Part

    /** The answer to the [ToolCall] with the same [id]. [parts] are [Text] and [Image] only. */
    @Serializable
    @SerialName("tool_result")
    public data class ToolResult(
        /** The call this answers. */
        val id: ToolCallId,
        /** Result content. */
        val parts: List<Part>,
        /** Whether the provider should be told the call failed. */
        val isError: Boolean,
    ) : Part {
        init {
            require(parts.all { it is Text || it is Image }) { "ToolResult $id may contain only text and images" }
        }
    }

    /**
     * Provider-private content (thinking with signature, encrypted reasoning, server compaction
     * blocks). Stored verbatim and sent back only to the same [provider] and [model]; other
     * adapters drop it.
     */
    @Serializable
    @SerialName("reasoning")
    public data class Reasoning(
        /** Provider that produced it. */
        val provider: ProviderId,
        /** Model that produced it. */
        val model: ModelId,
        /** Wire shape of [opaque]. */
        val kind: ReasoningKind,
        /** The provider's block, verbatim. */
        val opaque: JsonElement,
        /** Readable text for the UI (e.g. summarized thinking), or `null`; never sent back. */
        val summary: String? = null,
    ) : Part

    /** Constants. */
    public companion object {
        /** Image types every adapter must accept. */
        public val IMAGE_MIMES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Wire shape of a [Part.Reasoning] block. */
@Serializable
public enum class ReasoningKind {
    /** Anthropic `thinking` block with `signature`. */
    ANTHROPIC_THINKING,

    /** Anthropic `redacted_thinking` block. */
    ANTHROPIC_REDACTED_THINKING,

    /** Anthropic server-side `compaction` block (must be appended back to history, model-layer §5). */
    ANTHROPIC_COMPACTION,

    /** OpenAI Responses `reasoning` item with `encrypted_content`. */
    RESPONSES_ENCRYPTED,

    /** Chat Completions reasoning field (`reasoning_content` and similar, per `ProviderProfile`). */
    CHAT_REASONING_FIELD,
}
