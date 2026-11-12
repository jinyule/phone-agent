package dev.phoneagent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One model request (phase1 §3.1). Built as `request/header` + `deriveMessages(log)` for the main
 * conversation, or recorded whole in `side/request`; frozen once built. Everything here is
 * recorded in the session log, and an adapter's output depends only on this value (plus the
 * secret it resolves), never on live profile or capability settings.
 *
 * @property system static system prompt blocks (cache segment 1); stable within a session.
 * @property tools tool definitions; within a session the list only grows at the end.
 * @property messages the conversation; append-only across steps (sent messages are never edited).
 * @property wire the non-secret wire settings the adapter applies (see [WireProfile]).
 * @property cache where explicit cache breakpoints go (Anthropic); other protocols rely on prefix stability.
 * @property contextEdits who trims old context: the client (via log Replace events) or the server.
 * @property maxOutputTokens output cap, or `null` for none; sent in [WireProfile.maxTokensField].
 * @property reasoning requested reasoning level after clamping to the model ([clampTo]), or `null`
 *   for none; its wire form is [WireProfile.reasoningEffort].
 */
@Serializable
public data class ChatRequest(
    val system: List<PromptBlock>,
    val tools: List<ToolDef>,
    val messages: List<Message>,
    val wire: WireProfile,
    val cache: CacheLayout = CacheLayout.DEFAULT,
    val contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningLevel? = null,
) {
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be > 0, got $maxOutputTokens" }
        val duplicate =
            tools
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicate.isEmpty()) { "Duplicate tool names in request: $duplicate" }
    }
}

/** A named segment of the system prompt (e.g. `identity`, `rules`, `capabilities`). */
@Serializable
public data class PromptBlock(
    /** Stable segment id, for golden files and diffs. */
    val id: String,
    /** The text. */
    val text: String,
)

/**
 * Explicit cache breakpoints (Anthropic `cache_control`, at most [MAX_BREAKPOINTS] in total).
 * Semantic positions instead of indices, so the layout stays the same from step to step.
 *
 * @property tools a breakpoint after the last tool definition.
 * @property system a breakpoint after the last system block.
 * @property recentMessages breakpoints at the end of this many most recent messages.
 */
@Serializable
public data class CacheLayout(
    val tools: Boolean,
    val system: Boolean,
    val recentMessages: Int,
) {
    init {
        val total = (if (tools) 1 else 0) + (if (system) 1 else 0) + recentMessages
        require(recentMessages >= 0 && total <= MAX_BREAKPOINTS) {
            "CacheLayout uses $total breakpoints; allowed 0..$MAX_BREAKPOINTS"
        }
    }

    /** Defaults and limits. */
    public companion object {
        /** Anthropic's limit on `cache_control` blocks per request. */
        public const val MAX_BREAKPOINTS: Int = 4

        /** Tools end, system end and the latest message (model-layer §3.3). */
        public val DEFAULT: CacheLayout = CacheLayout(tools = true, system = true, recentMessages = 1)

        /** No explicit breakpoints. */
        public val NONE: CacheLayout = CacheLayout(tools = false, system = false, recentMessages = 0)
    }
}

/** Who shrinks old context (model-layer §5). Chosen per protocol by the caller. */
@Serializable
public sealed interface ContextEditPolicy {
    /** The agent trims history itself with `Replace` events; the adapter sends nothing. */
    @Serializable
    @SerialName("client")
    public data object ClientSide : ContextEditPolicy

    /**
     * Server-side context editing and compaction (Anthropic only; other adapters reject it), as
     * sent: `clear_tool_uses_20250919` and `compact_20260112` edits (model-layer §5). Validated
     * against the API's limits; which edits a profile turns on is its settings' decision
     * (`AnthropicSettings.serverContext`).
     *
     * @property clearToolUsesAboveTokens clear old tool results once input exceeds this; `null` disables.
     * @property keepToolUses how many recent tool uses survive clearing.
     * @property compactAboveTokens server compaction trigger, >= [MIN_COMPACT_ABOVE_TOKENS]; `null` disables.
     * @property clearAtLeastTokens clearing happens only when it frees at least this many tokens
     *   (`clear_at_least`), > 0; `null` sends none. Only with [clearToolUsesAboveTokens].
     * @property compactInstructions the summarization prompt (`instructions`), which replaces the
     *   server's default prompt whole; model-visible. Not blank; `null` keeps the default. Only with
     *   [compactAboveTokens].
     */
    @Serializable
    @SerialName("server")
    public data class ServerSide(
        val clearToolUsesAboveTokens: Int?,
        val keepToolUses: Int,
        val compactAboveTokens: Int?,
        val clearAtLeastTokens: Int? = null,
        val compactInstructions: String? = null,
    ) : ContextEditPolicy {
        init {
            require(clearToolUsesAboveTokens == null || clearToolUsesAboveTokens > 0) { "clear trigger must be > 0" }
            require(keepToolUses >= 0) { "keepToolUses must be >= 0" }
            require(compactAboveTokens == null || compactAboveTokens >= MIN_COMPACT_ABOVE_TOKENS) {
                "compaction trigger must be >= $MIN_COMPACT_ABOVE_TOKENS, got $compactAboveTokens"
            }
            require(clearAtLeastTokens == null || clearAtLeastTokens > 0) { "clearAtLeastTokens must be > 0" }
            require(clearAtLeastTokens == null || clearToolUsesAboveTokens != null) {
                "clearAtLeastTokens needs clearToolUsesAboveTokens"
            }
            require(compactInstructions == null || compactInstructions.isNotBlank()) {
                "compactInstructions must not be blank"
            }
            require(compactInstructions == null || compactAboveTokens != null) {
                "compactInstructions needs compactAboveTokens"
            }
        }

        /** Limits of the API. */
        public companion object {
            /**
             * The lowest compaction trigger the API takes (`trigger.value` "must be at least 50,000
             * tokens", Claude API docs, compaction at a token threshold; its default is 150,000).
             */
            public const val MIN_COMPACT_ABOVE_TOKENS: Int = 50_000
        }
    }
}

/**
 * Protocol-neutral reasoning depth. Adapters map it to the wire value (`reasoning.effort`,
 * `output_config.effort`) and clamp to what the model supports (`ModelCapabilities.reasoningLevels`).
 */
@Serializable
public enum class ReasoningLevel {
    /** Least reasoning the model allows. */
    MINIMAL,

    /** Low. */
    LOW,

    /** Medium. */
    MEDIUM,

    /** High. */
    HIGH,

    /** Extra high. */
    XHIGH,

    /** Maximum. */
    MAX,
}
