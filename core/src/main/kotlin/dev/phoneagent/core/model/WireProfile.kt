package dev.phoneagent.core.model

import kotlinx.serialization.Serializable

/**
 * Everything besides the neutral content of a [ChatRequest] that shapes the bytes an adapter
 * sends (model-layer §2): a non-secret snapshot frozen into every `request/header` and
 * `side/request`, so the log alone rebuilds the exact wire request.
 *
 * Adapters read these settings only from `request.wire`, never from the live [ProviderProfile]
 * or [ModelCapabilities]: those may change while a task runs. The profile still supplies the
 * endpoint and the credential reference; an adapter refuses a request whose [credential] kind is
 * not its profile's, and one whose [mappingVersion] it does not implement, with
 * `ModelError.InvalidRequest` before sending anything. Secrets never appear here: [credential]
 * only says which kind of authentication is sent.
 *
 * Fields named after wire fields use the adapter's own spelling (for example
 * `max_completion_tokens`); `null` means nothing is sent for it. Which values a provider needs is
 * decided by the adapter's rules (WP03) when the header is written; [ProviderProfile.wire] fills
 * the part that comes from the profile.
 *
 * @property mappingVersion version of the adapter's mapping rules (neutral request plus this
 *   snapshot to wire bytes), >= 1. An adapter bumps it whenever the same inputs would produce
 *   different bytes, so a recorded request is replayed only by rules that produce its bytes.
 * @property credential which kind of authentication the request carries.
 * @property headers extra non-secret request headers, as sent; never credential-like names.
 * @property images how image parts are sent.
 * @property reasoningField Chat Completions response field carrying reasoning (e.g.
 *   `reasoning_content`), or `null` when there is none.
 * @property echoReasoning send [reasoningField] content back in later requests.
 * @property reasoningEffort the mapped reasoning setting as sent (field and value), or `null`
 *   when no reasoning setting is sent; `ChatRequest.reasoning` is the neutral level it came from.
 * @property maxTokensField the field carrying `ChatRequest.maxOutputTokens` (`max_tokens`,
 *   `max_completion_tokens`, `max_output_tokens`), or `null` when the cap is not sent.
 * @property parallelToolCalls send `parallel_tool_calls`.
 * @property streamOptions send `stream_options` (usage in the stream).
 * @property includeEncryptedReasoning Responses: ask for `include: ["reasoning.encrypted_content"]`.
 * @property recoverLeakedToolCalls turn complete tool-call markup leaked into the reply text back
 *   into tool calls (only for providers known to leak them).
 * @property options further adapter settings that change the request, by lowercase name; for
 *   settings added before they get a field of their own. Never secret.
 */
@Serializable
public data class WireProfile(
    val mappingVersion: Int,
    val credential: CredentialKind,
    val headers: Map<String, String> = emptyMap(),
    val images: ImageMode = ImageMode.INLINE,
    val reasoningField: String? = null,
    val echoReasoning: Boolean = false,
    val reasoningEffort: WireSetting? = null,
    val maxTokensField: String? = null,
    val parallelToolCalls: Boolean = false,
    val streamOptions: Boolean = false,
    val includeEncryptedReasoning: Boolean = false,
    val recoverLeakedToolCalls: Boolean = false,
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(mappingVersion >= 1) { "mappingVersion must be >= 1, got $mappingVersion" }
        headerProblem(headers)?.let { throw IllegalArgumentException("wire headers: $it") }
        require(reasoningField == null || reasoningField.isNotBlank()) { "reasoningField must not be blank" }
        require(!echoReasoning || reasoningField != null) { "echoReasoning needs reasoningField" }
        require(maxTokensField == null || maxTokensField.isNotBlank()) { "maxTokensField must not be blank" }
        require(options.keys.all { OPTION_NAME.matches(it) }) { "option names must match ${OPTION_NAME.pattern}" }
    }

    /** Header values are left out: they are not secret, but they are not needed in diagnostics either. */
    override fun toString(): String =
        "WireProfile(mappingVersion=$mappingVersion, credential=$credential, headers=${headers.keys}, " +
            "images=$images, reasoningField=$reasoningField, echoReasoning=$echoReasoning, " +
            "reasoningEffort=$reasoningEffort, maxTokensField=$maxTokensField, parallelToolCalls=$parallelToolCalls, " +
            "streamOptions=$streamOptions, includeEncryptedReasoning=$includeEncryptedReasoning, " +
            "recoverLeakedToolCalls=$recoverLeakedToolCalls, options=$options)"
}

/** A setting sent as [value] in the wire field [field] (e.g. `reasoning_effort` = `high`). */
@Serializable
public data class WireSetting(
    /** Wire field name, dotted for nested objects (`reasoning.effort`). */
    val field: String,
    /** The value sent. */
    val value: String,
) {
    init {
        require(field.isNotBlank() && value.isNotBlank()) { "wire setting needs a field and a value" }
    }
}

/** Kind of authentication a request carries; the secret itself is resolved by the adapter. */
@Serializable
public enum class CredentialKind {
    /** [Credential.ApiKey]. */
    API_KEY,

    /** [Credential.ChatGptPlan]. */
    CHATGPT_PLAN,

    /** [Credential.None]. */
    NONE,
}

/** How image parts are sent. */
@Serializable
public enum class ImageMode {
    /** As images. */
    INLINE,

    /**
     * Each image replaced by a fixed text placeholder (the model has no vision); the text is part
     * of the mapping rules ([WireProfile.mappingVersion]).
     */
    PLACEHOLDER,
}

/** The kind of [this] credential. */
public val Credential.kind: CredentialKind
    get() =
        when (this) {
            is Credential.ApiKey -> CredentialKind.API_KEY
            is Credential.ChatGptPlan -> CredentialKind.CHATGPT_PLAN
            Credential.None -> CredentialKind.NONE
        }

/**
 * The part of the wire snapshot that comes from this profile (credential kind, headers,
 * reasoning field and echo), for adapter rules of [mappingVersion]; adapters set the rest.
 * The endpoint and the secret reference are not part of it.
 */
public fun ProviderProfile.wire(mappingVersion: Int): WireProfile =
    WireProfile(
        mappingVersion = mappingVersion,
        credential = credential.kind,
        headers = headers,
        reasoningField = reasoningField,
        echoReasoning = echoReasoning,
    )

/**
 * The level to request from a model whose selectable levels are [supported]: this level when
 * supported, else the highest supported level below it, else the lowest supported one. `null`
 * [supported] (unknown) keeps this level; an empty list means none can be sent (`null`).
 * `request/header.reasoning` records the result.
 */
public fun ReasoningLevel.clampTo(supported: List<ReasoningLevel>?): ReasoningLevel? =
    when {
        supported == null -> this
        this in supported -> this
        else -> supported.filter { it < this }.maxOrNull() ?: supported.minOrNull()
    }

/** Why [headers] may not be sent as extra headers, or `null` when they may. Never echoes values. */
internal fun headerProblem(headers: Map<String, String>): String? {
    val invalid = headers.keys.filterNot { HEADER_NAME.matches(it) }
    val credentialLike = headers.keys.filter { name -> CREDENTIAL_WORDS.any { it in name.lowercase() } }
    return when {
        invalid.isNotEmpty() -> "invalid header names $invalid"
        credentialLike.isNotEmpty() -> "credentials go in `credential`, not headers $credentialLike"
        else -> null
    }
}

private val HEADER_NAME = Regex("[A-Za-z0-9-]+")
private val CREDENTIAL_WORDS = listOf("key", "token", "secret", "auth", "cookie")
private val OPTION_NAME = Regex("[a-z][a-z0-9_.]*")
