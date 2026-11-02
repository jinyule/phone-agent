package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.model.wire
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import java.util.Locale

/**
 * The wire snapshot ([WireProfile]) of Anthropic Messages requests (model-layer §2, §3.3). Whoever
 * writes `request/header` or `side/request` calls [snapshot] and puts the result in
 * `ChatRequest.wire`; the adapter reads its settings from there only, so a logged request replays
 * byte for byte while the profile or the settings change.
 *
 * Mapping version [MAPPING_VERSION] reads these fields: `credential`, `headers`, `images`,
 * `reasoningEffort` (`output_config.effort`), `maxTokensField` (always `max_tokens`) and the
 * `anthropic.*` [WireProfile.options] below. The Chat Completions and Responses fields must keep
 * their defaults; anything else is refused with [ModelError.InvalidRequest] before sending.
 */
public object AnthropicWire {
    /** Version of the mapping rules this adapter implements. */
    public const val MAPPING_VERSION: Int = 1

    /** `anthropic-version` sent when the snapshot names none (API version with no sampling parameters). */
    public const val API_VERSION: String = "2026-10-21"

    /** `max_tokens` when `ChatRequest.maxOutputTokens` is `null`: the field is required (model-layer §3.3). */
    public const val DEFAULT_MAX_TOKENS: Int = 64_000

    /** Option names in [WireProfile.options]. */
    internal const val OPT_VERSION = "anthropic.version"
    internal const val OPT_AUTH = "anthropic.auth"
    internal const val OPT_THINKING = "anthropic.thinking"
    internal const val OPT_DISPLAY = "anthropic.thinking_display"
    internal const val OPT_PREFIX_MISMATCH = "anthropic.prefix_mismatch"
    internal const val OPT_FALLBACKS = "anthropic.fallbacks"
    internal const val OPT_EAGER = "anthropic.eager_input_streaming"
    internal const val OPT_STRICT = "anthropic.strict_tools"

    internal const val EFFORT_FIELD = "output_config.effort"
    internal const val MAX_TOKENS_FIELD = "max_tokens"

    /**
     * The snapshot for a request to [model] behind [profile]. [reasoning] is the level already
     * clamped to the model (`ReasoningLevel.clampTo`), the same value the request records in
     * `ChatRequest.reasoning`; `null` sends no effort (the model's default). Images are sent inline
     * when [capabilities] say the model sees them, else as a fixed text placeholder.
     */
    public fun snapshot(
        profile: ProviderProfile,
        model: ModelId,
        capabilities: ModelCapabilities,
        reasoning: ReasoningLevel?,
        settings: AnthropicSettings = AnthropicSettings.defaultsFor(profile, model),
    ): WireProfile {
        require(profile.protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
            "Provider ${profile.id}: ${profile.protocol} is not Anthropic Messages"
        }
        return profile.wire(MAPPING_VERSION).copy(
            images = if (capabilities.vision) ImageMode.INLINE else ImageMode.PLACEHOLDER,
            reasoningEffort = reasoning?.let { WireSetting(EFFORT_FIELD, effortOf(it)) },
            maxTokensField = MAX_TOKENS_FIELD,
            options = settings.options(),
        )
    }

    /** The `output_config.effort` value for [level]; Anthropic has no level below `low`. */
    internal fun effortOf(level: ReasoningLevel): String =
        when (level) {
            ReasoningLevel.MINIMAL, ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
            ReasoningLevel.XHIGH -> "xhigh"
            ReasoningLevel.MAX -> "max"
        }
}

/**
 * What an Anthropic profile sends besides the conversation; saved with the profile and turned
 * into snapshot options by [AnthropicWire.snapshot]. Validated on construction.
 *
 * @property apiVersion the `anthropic-version` header (`YYYY-MM-DD`).
 * @property auth how the API key is sent.
 * @property adaptiveThinking send `thinking: {type: "adaptive"}`; off sends no `thinking` at all
 *   (gateways and models without thinking).
 * @property thinkingDisplay `thinking.display`, or `null` for the model's default (`omitted` on
 *   Claude Opus 5.5); `summarized` gives readable progress (model-layer §3.3). Needs [adaptiveThinking].
 * @property prefixMismatch `thinking.block_binding.prefix_mismatch_behavior` (beta
 *   `thinking-binding-controls-2026-08-01`), or `null` to send none. Needs [adaptiveThinking].
 * @property serverFallback server-side refusal fallback: `fallbacks: "default"` (beta
 *   `server-side-fallback-2026-07-01`; Claude API only, not for Claude Haiku 5.5).
 * @property eagerInputStreaming `eager_input_streaming: true` on every tool: arguments stream as
 *   they are generated, unvalidated by the server.
 * @property strictTools `strict: true` on every tool; schemas must set `additionalProperties: false`
 *   on every object.
 */
public data class AnthropicSettings(
    val apiVersion: String = AnthropicWire.API_VERSION,
    val auth: AnthropicAuth = AnthropicAuth.X_API_KEY,
    val adaptiveThinking: Boolean = true,
    val thinkingDisplay: ThinkingDisplay? = ThinkingDisplay.SUMMARIZED,
    val prefixMismatch: PrefixMismatch? = null,
    val serverFallback: Boolean = false,
    val eagerInputStreaming: Boolean = true,
    val strictTools: Boolean = false,
) {
    init {
        require(VERSION_SHAPE.matches(apiVersion)) { "apiVersion must look like 2026-10-21, got '$apiVersion'" }
        require(adaptiveThinking || (thinkingDisplay == null && prefixMismatch == null)) {
            "thinkingDisplay and prefixMismatch need adaptiveThinking"
        }
    }

    /** The snapshot options these settings send (every setting is written, so the log shows it). */
    internal fun options(): Map<String, String> =
        buildMap {
            put(AnthropicWire.OPT_VERSION, apiVersion)
            put(AnthropicWire.OPT_AUTH, auth.wire)
            if (adaptiveThinking) put(AnthropicWire.OPT_THINKING, ADAPTIVE)
            thinkingDisplay?.let { put(AnthropicWire.OPT_DISPLAY, it.wire) }
            prefixMismatch?.let { put(AnthropicWire.OPT_PREFIX_MISMATCH, it.wire) }
            if (serverFallback) put(AnthropicWire.OPT_FALLBACKS, FALLBACKS_DEFAULT)
            if (eagerInputStreaming) put(AnthropicWire.OPT_EAGER, TRUE)
            if (strictTools) put(AnthropicWire.OPT_STRICT, TRUE)
        }

    /** Defaults. */
    public companion object {
        internal const val ADAPTIVE = "adaptive"
        internal const val FALLBACKS_DEFAULT = "default"
        internal const val TRUE = "true"

        /**
         * Recommended settings (model-layer §3.3, §5): adaptive thinking with summarized display
         * and eager tool input everywhere; on the official endpoint also `drop_block` for stale
         * thinking signatures and, for models that have one, the server-side fallback. Other
         * endpoints get no Claude-API-only betas: a gateway may reject them.
         */
        public fun defaultsFor(
            profile: ProviderProfile,
            model: ModelId,
        ): AnthropicSettings {
            val official = AnthropicPresets.isOfficial(profile.baseUrl)
            return AnthropicSettings(
                prefixMismatch = if (official) PrefixMismatch.DROP_BLOCK else null,
                serverFallback = official && AnthropicPresets.hasServerFallback(model),
            )
        }
    }
}

/** How the API key is sent. */
public enum class AnthropicAuth(
    internal val wire: String,
) {
    /** `x-api-key: <key>`: what the Claude API and most gateways read. */
    X_API_KEY("x-api-key"),

    /** `Authorization: Bearer <key>`: what the 2026-10-21 SDKs send; for gateways that want it. */
    BEARER("bearer"),
}

/** `thinking.display`. */
public enum class ThinkingDisplay(
    internal val wire: String,
) {
    /** Summarized thinking text, for progress display. */
    SUMMARIZED("summarized"),

    /** No thinking text (signatures still come back). */
    OMITTED("omitted"),
}

/** `thinking.block_binding.prefix_mismatch_behavior`. */
public enum class PrefixMismatch(
    internal val wire: String,
) {
    /** Drop thinking blocks whose signature does not cover the history; reported in `input_transformations`. */
    DROP_BLOCK("drop_block"),

    /** Reject the request with 400 ([ModelError.PrefixBindingMismatch]); for checks that history is append-only. */
    ERROR("error"),
}

/**
 * The settings one request carries, read from and validated against `request.wire`
 * ([WireProfile] KDoc): refusals are [ModelError.InvalidRequest] naming the field, before anything
 * is sent.
 */
internal data class WireRules(
    val apiVersion: String,
    val bearer: Boolean,
    val adaptiveThinking: Boolean,
    val display: String?,
    val prefixMismatch: String?,
    val fallbacks: Boolean,
    val eager: Boolean,
    val strict: Boolean,
    val effort: String?,
    val inlineImages: Boolean,
    val headers: Headers,
) {
    companion object {
        /** The rules of [request], for a profile whose credential is of kind [credential]. */
        fun of(
            request: ChatRequest,
            credential: CredentialKind,
        ): WireRules {
            val wire = request.wire
            if (wire.mappingVersion != AnthropicWire.MAPPING_VERSION) {
                invalid(
                    "wire.mappingVersion",
                    "mapping version ${wire.mappingVersion} is not the one this adapter implements " +
                        "(${AnthropicWire.MAPPING_VERSION})",
                )
            }
            if (wire.credential != credential) {
                invalid("wire.credential", "request sends ${wire.credential} but the profile has $credential")
            }
            checkForeignFields(wire)
            if (wire.maxTokensField != AnthropicWire.MAX_TOKENS_FIELD) {
                invalid("wire.maxTokensField", "Anthropic Messages needs max_tokens, got ${wire.maxTokensField}")
            }
            val options = Options(wire.options)
            val adaptive = options.flag(AnthropicWire.OPT_THINKING, AnthropicSettings.ADAPTIVE)
            val rules =
                WireRules(
                    apiVersion = options.version(),
                    bearer = options.choice(AnthropicWire.OPT_AUTH, AUTH_VALUES) == AnthropicAuth.BEARER.wire,
                    adaptiveThinking = adaptive,
                    display = options.choice(AnthropicWire.OPT_DISPLAY, DISPLAY_VALUES),
                    prefixMismatch = options.choice(AnthropicWire.OPT_PREFIX_MISMATCH, MISMATCH_VALUES),
                    fallbacks = options.flag(AnthropicWire.OPT_FALLBACKS, AnthropicSettings.FALLBACKS_DEFAULT),
                    eager = options.flag(AnthropicWire.OPT_EAGER, AnthropicSettings.TRUE),
                    strict = options.flag(AnthropicWire.OPT_STRICT, AnthropicSettings.TRUE),
                    effort = effort(request),
                    inlineImages = wire.images == ImageMode.INLINE,
                    headers = headers(wire.headers),
                )
            options.unknown()?.let { invalid("wire.options", "unknown options $it") }
            if (!adaptive && (rules.display != null || rules.prefixMismatch != null)) {
                invalid("wire.options", "thinking display and prefix mismatch need ${AnthropicWire.OPT_THINKING}")
            }
            return rules
        }

        /** Fields of the other protocols must keep their defaults: they would mean nothing here. */
        private fun checkForeignFields(wire: WireProfile) {
            val foreign =
                listOfNotNull(
                    "reasoningField".takeIf { wire.reasoningField != null },
                    "echoReasoning".takeIf { wire.echoReasoning },
                    "parallelToolCalls".takeIf { wire.parallelToolCalls },
                    "streamOptions".takeIf { wire.streamOptions },
                    "includeEncryptedReasoning".takeIf { wire.includeEncryptedReasoning },
                    "recoverLeakedToolCalls".takeIf { wire.recoverLeakedToolCalls },
                )
            if (foreign.isNotEmpty()) invalid("wire", "$foreign do not apply to Anthropic Messages")
        }

        /** The effort to send: exactly the mapping of `request.reasoning`, or nothing when it is `null`. */
        private fun effort(request: ChatRequest): String? {
            val sent = request.wire.reasoningEffort
            val expected = request.reasoning?.let(AnthropicWire::effortOf)
            if (sent?.field != expected?.let { AnthropicWire.EFFORT_FIELD } || sent?.value != expected) {
                invalid(
                    "wire.reasoningEffort",
                    "reasoning ${request.reasoning} maps to ${expected?.let {
                        "${AnthropicWire.EFFORT_FIELD}=$it"
                    }}, " +
                        "the snapshot has $sent",
                )
            }
            return expected
        }

        /** Extra headers as HTTP headers; the adapter's own headers cannot be overridden. Values are never echoed. */
        private fun headers(extra: Map<String, String>): Headers {
            val reserved = extra.keys.filter { it.lowercase(Locale.ROOT) in RESERVED_HEADERS }
            if (reserved.isNotEmpty()) invalid("wire.headers", "headers $reserved are set by the adapter")
            return try {
                extra.toHeaders()
            } catch (badValue: IllegalArgumentException) {
                // Its message quotes the value; say which kind of problem it is instead.
                invalid("wire.headers", "a header value is not valid HTTP (${badValue::class.simpleName})")
            }
        }

        private val AUTH_VALUES = AnthropicAuth.entries.map { it.wire }
        private val DISPLAY_VALUES = ThinkingDisplay.entries.map { it.wire }
        private val MISMATCH_VALUES = PrefixMismatch.entries.map { it.wire }
        private val RESERVED_HEADERS = setOf("anthropic-version", "anthropic-beta", "content-type", "accept")
    }

    /** Reads `anthropic.*` options and remembers which ones were read. */
    private class Options(
        private val all: Map<String, String>,
    ) {
        private val read = mutableSetOf<String>()

        private fun value(name: String): String? = all[name].also { read += name }

        fun version(): String {
            val version = value(AnthropicWire.OPT_VERSION) ?: return AnthropicWire.API_VERSION
            if (!VERSION_SHAPE.matches(version)) invalid("wire.options", "${AnthropicWire.OPT_VERSION} '$version'")
            return version
        }

        /** Whether option [name] is set to its one accepted value [on] (or `false`). */
        fun flag(
            name: String,
            on: String,
        ): Boolean =
            when (val value = value(name)) {
                null, FALSE -> false
                on -> true
                else -> invalid("wire.options", "$name must be '$on' or '$FALSE', got '$value'")
            }

        fun choice(
            name: String,
            values: List<String>,
        ): String? {
            val value = value(name) ?: return null
            if (value !in values) invalid("wire.options", "$name must be one of $values, got '$value'")
            return value
        }

        fun unknown(): Set<String>? = (all.keys - read).takeIf { it.isNotEmpty() }

        companion object {
            const val FALSE = "false"
        }
    }
}

/** The shape of an `anthropic-version` value. */
private val VERSION_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2}")

/** Throws [ModelError.InvalidRequest]: the request cannot be sent as it is. */
internal fun invalid(
    param: String,
    detail: String,
): Nothing = throw ModelError.InvalidRequest(param, detail)
