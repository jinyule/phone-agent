package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import kotlinx.serialization.Serializable

/**
 * A model behind one protocol adapter (phase1 §3.1, model-layer §2).
 *
 * Failure contract: every provider or transport failure is thrown as a [ModelError]; nothing else
 * escapes except `CancellationException`, which is always propagated. Retrying is the caller's
 * decision (`requestError` interceptors), never the adapter's, so each attempt is logged.
 */
public interface ChatModel {
    /** Which model this is and what it can do. */
    public val info: ModelInfo

    /**
     * The settings of a request to this model at [reasoning]: the effective level, clamped to
     * `info.capabilities.reasoningLevels` with [clampTo], the wire snapshot with that level
     * already mapped ([WireProfile.reasoningEffort]) by this adapter's rules, and who trims old
     * context. Clamping and mapping are one step, here; callers never compute either themselves.
     *
     * Callers: the agent for the main conversation (the result is recorded in `request/header`)
     * and capability packages for side requests (recorded in `side/request`). Both put
     * [RequestSettings.reasoning] into `ChatRequest.reasoning`, [RequestSettings.wire] into
     * `ChatRequest.wire` and [RequestSettings.contextEdits] into `ChatRequest.contextEdits`.
     * Replay takes all three from the log and never calls this.
     *
     * Stable for the life of this instance: the same [reasoning] always gives an equal result,
     * whatever live settings or [complete] do meanwhile, and collections in the snapshot are
     * copies that nothing changes once given out, [complete] included. The app's
     * `ModelRouter` freezes the profile, the capabilities and the mapping version into the
     * instance when it binds the model to a task. Pure: no I/O, never throws.
     */
    public fun prepare(reasoning: ReasoningLevel?): RequestSettings

    /**
     * Sends [request] and returns the complete reply. [onDelta] receives streaming fragments in
     * order, on the calling coroutine, before this returns; it must not block or throw. The text
     * fragments are a prefix of the returned reply's text; reasoning and tool-call previews may
     * not appear in it, so displays re-render from the returned [ChatResponse] ([Delta]).
     * Cancelling the caller aborts the HTTP exchange. A request whose `wire.mappingVersion` or
     * `wire.credential` is not what [prepare] gives fails with [ModelError.InvalidRequest] before
     * anything is sent. The core test sources hold `ChatModelContract`, which adapter tests inherit.
     */
    public suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit = {},
    ): ChatResponse
}

/**
 * What [ChatModel.prepare] gives for one request.
 *
 * @property reasoning the effective level for `ChatRequest.reasoning`, or `null` when none is sent.
 * @property wire the frozen wire settings for `ChatRequest.wire`, with [reasoning] mapped.
 * @property contextEdits who trims old context, for `ChatRequest.contextEdits`: the server, when
 *   the model's settings turn on server-side context management (Anthropic Messages only), else
 *   the client.
 */
public data class RequestSettings(
    val reasoning: ReasoningLevel?,
    val wire: WireProfile,
    val contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
)

/**
 * Identity and capabilities of a [ChatModel].
 *
 * @property provider the profile it was created from.
 * @property model the wire model name.
 * @property protocol the adapter's protocol.
 * @property capabilities what the model supports.
 */
@Serializable
public data class ModelInfo(
    val provider: ProviderId,
    val model: ModelId,
    val protocol: ModelProtocol,
    val capabilities: ModelCapabilities,
)

/**
 * What a model supports (model-layer §2). Values come from presets and are confirmed by
 * "test and save" probes ([verified]).
 *
 * @property tools supports tool calling (required for [ModelRole.MAIN]).
 * @property vision accepts images.
 * @property contextTokens context window in tokens, > 0.
 * @property maxOutputTokens largest output cap, or `null` when unknown.
 * @property reasoningLevels selectable reasoning levels; `null` = unknown, empty = none selectable.
 * @property promptCaching how prompt caching works.
 * @property thinkingBinding whether reasoning blocks are bound to the exact history prefix.
 * @property verified capabilities a probe has confirmed on this account.
 */
@Serializable
public data class ModelCapabilities(
    val tools: Boolean,
    val vision: Boolean,
    val contextTokens: Int,
    val maxOutputTokens: Int? = null,
    val reasoningLevels: List<ReasoningLevel>? = null,
    val promptCaching: PromptCaching = PromptCaching.IMPLICIT,
    val thinkingBinding: ThinkingBinding = ThinkingBinding.NONE,
    val verified: Set<VerifiedCapability> = emptySet(),
) {
    init {
        require(contextTokens > 0) { "contextTokens must be > 0" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be > 0" }
    }
}

/** Prompt caching style. */
@Serializable
public enum class PromptCaching {
    /** Server caches stable prefixes automatically. */
    IMPLICIT,

    /** Caching needs explicit breakpoints (`cache_control`). */
    EXPLICIT,

    /** No caching. */
    NONE,
}

/** Whether history edits invalidate reasoning blocks. */
@Serializable
public enum class ThinkingBinding {
    /** No binding. */
    NONE,

    /** Signatures cover the whole prefix (Anthropic preserved thinking): never rewrite history. */
    PREFIX_BOUND,
}

/** Capabilities a "test and save" probe can confirm. */
@Serializable
public enum class VerifiedCapability {
    /** A real tool call came back. */
    TOOL_CALL,

    /** An image was understood. */
    VISION,

    /** Points named on a screenshot landed on the right elements (screen grounding). */
    GROUNDING,

    /** Reasoning content round-tripped. */
    REASONING_ECHO,
}

/** Wire protocol of an adapter (D7). */
@Serializable
public enum class ModelProtocol {
    /** OpenAI Chat Completions and compatible gateways. */
    OPENAI_CHAT_COMPLETIONS,

    /** OpenAI Responses. */
    OPENAI_RESPONSES,

    /** Anthropic Messages. */
    ANTHROPIC_MESSAGES,
}

/** What a model is used for (phase1 §3.1). Vision and light models run as side requests. */
@Serializable
public enum class ModelRole {
    /** Drives the task; must support tools. */
    MAIN,

    /** Describes screens or grounds targets; defaults to the main model. */
    VISION,

    /** Small judgments (intent, outcome); never decides to give up, confirm or hand off. */
    LIGHT,
}
