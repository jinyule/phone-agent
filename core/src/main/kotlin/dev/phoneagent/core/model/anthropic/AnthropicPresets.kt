package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.PromptCaching
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.ThinkingBinding
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The built-in Anthropic preset (model-layer §3.3): the Claude API with an API key. Model facts are
 * starting values from the Claude API model overview; the server's `/v1/models` list and "test and
 * save" decide for a real account.
 */
public object AnthropicPresets {
    /** Preset id, stored in `ProviderProfile.preset`. */
    public const val ID: String = "anthropic"

    /** The Claude API; requests go to `{baseUrl}/v1/messages`. */
    public const val OFFICIAL_BASE_URL: String = "https://api.anthropic.com"

    /** Default main model (and vision model). */
    public val MAIN_MODEL: ModelId = ModelId("claude-opus-5-5")

    /** Optional main model. */
    public val SONNET_MODEL: ModelId = ModelId("claude-sonnet-5-5")

    /** Default light model (intent and outcome judgments). */
    public val LIGHT_MODEL: ModelId = ModelId("claude-haiku-5-5")

    private val OFFICIAL_URL = OFFICIAL_BASE_URL.toHttpUrl()
    private const val CONTEXT_TOKENS = 1_000_000
    private const val MAX_OUTPUT_TOKENS = 128_000

    /** `output_config.effort` levels of the 5.5 models (`low` … `max`). */
    private val LEVELS =
        listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX)

    /** Models with a server-side refusal fallback (`fallbacks: "default"`); Claude Haiku 5.5 has none. */
    private val SERVER_FALLBACK = setOf(MAIN_MODEL, SONNET_MODEL)

    /** A profile for the Claude API; validated like any profile. */
    public fun profile(
        id: ProviderId,
        credential: Credential,
        displayName: String = "Anthropic",
    ): ProviderProfile =
        ProviderProfile(
            id = id,
            displayName = displayName,
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            baseUrl = OFFICIAL_BASE_URL,
            credential = credential,
            preset = ID,
        )

    /**
     * Known capabilities of [model], or `null` for a model this preset does not know (a gateway's
     * own name, an older Claude): then "test and save" has to find them out.
     */
    public fun capabilities(model: ModelId): ModelCapabilities? =
        if (model in setOf(MAIN_MODEL, SONNET_MODEL, LIGHT_MODEL)) {
            ModelCapabilities(
                tools = true,
                vision = true,
                contextTokens = CONTEXT_TOKENS,
                maxOutputTokens = MAX_OUTPUT_TOKENS,
                reasoningLevels = LEVELS,
                promptCaching = PromptCaching.EXPLICIT,
                thinkingBinding = ThinkingBinding.PREFIX_BOUND,
            )
        } else {
            null
        }

    /**
     * The reasoning level to request for [role], set explicitly because Claude Opus 5.5 defaults
     * to `medium` (model-layer §3.3): GUI tasks start at `high`, small judgments at `low`. Vision
     * runs on the main model by default and gets its level.
     */
    public fun defaultReasoning(role: ModelRole): ReasoningLevel =
        when (role) {
            ModelRole.MAIN, ModelRole.VISION -> ReasoningLevel.HIGH
            ModelRole.LIGHT -> ReasoningLevel.LOW
        }

    /**
     * Whether [baseUrl] is the Claude API itself: normalized, exactly [OFFICIAL_BASE_URL] (HTTPS,
     * that host in any letter case, port 443 written out or not, the root path and nothing else),
     * not a gateway, a proxy path or a cloud platform. Snapshots ([AnthropicSettings.effectiveFor])
     * and the check before sending use this one definition.
     */
    public fun isOfficial(baseUrl: String): Boolean = baseUrl.toHttpUrlOrNull() == OFFICIAL_URL

    /** Whether [model] has a server-side refusal fallback on the Claude API. */
    public fun hasServerFallback(model: ModelId): Boolean = model in SERVER_FALLBACK
}
