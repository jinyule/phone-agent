package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ProviderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

/**
 * A configured provider (model-layer §2): where to send requests, with which protocol and
 * credential. Validated on construction, so a bad saved profile fails loudly when loaded.
 *
 * @property id stable id, referenced by session logs.
 * @property displayName user-chosen label.
 * @property protocol wire protocol.
 * @property baseUrl `https://host[/path]` without trailing `/`, query, fragment or user info. Plain
 *   `http://` only for local servers such as Ollama: loopback, private, link-local and
 *   unique-local address literals, `localhost` and `*.local` (no DNS lookup decides it).
 * @property credential how to authenticate; secrets are referenced, never stored here.
 * @property headers extra non-secret request headers; names that look like credentials (containing
 *   `key`, `token`, `secret`, `auth` or `cookie`) are refused. [toString] shows names only.
 * @property reasoningField Chat Completions response field carrying reasoning (e.g. `reasoning_content`),
 *   or `null` when the provider has none or it is unknown.
 * @property echoReasoning send [reasoningField] content back in later requests.
 * @property preset id of the built-in preset this profile came from, or `null` for a custom one.
 */
@Serializable
public data class ProviderProfile(
    val id: ProviderId,
    val displayName: String,
    val protocol: ModelProtocol,
    val baseUrl: String,
    val credential: Credential,
    val headers: Map<String, String> = emptyMap(),
    val reasoningField: String? = null,
    val echoReasoning: Boolean = false,
    val preset: String? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Provider $id: displayName must not be blank" }
        baseUrlProblem(baseUrl)?.let { throw IllegalArgumentException("Provider $id: $it") }
        headerProblem(headers)?.let { throw IllegalArgumentException("Provider $id: $it") }
        require(credential !is Credential.ChatGptPlan || protocol == ModelProtocol.OPENAI_RESPONSES) {
            "Provider $id: ChatGPT plan sign-in only works with the Responses protocol"
        }
        require(reasoningField == null || protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
            "Provider $id: reasoningField applies to Chat Completions only"
        }
        require(!echoReasoning || reasoningField != null) { "Provider $id: echoReasoning needs reasoningField" }
    }

    /** Header values are left out: some providers put account identifiers there. */
    override fun toString(): String =
        "ProviderProfile(id=$id, displayName=$displayName, protocol=$protocol, baseUrl=$baseUrl, " +
            "credential=$credential, headers=${headers.keys}, reasoningField=$reasoningField, " +
            "echoReasoning=$echoReasoning, preset=$preset)"
}

/** Why [url] cannot be a base URL, or `null` when it can. Never echoes [url]: it may hold a secret. */
private fun baseUrlProblem(url: String): String? {
    val uri = if (BASE_URL.matches(url)) parsed(url) else null
    return when {
        uri == null || uri.host == null -> "baseUrl must be http(s)://host[/path] without trailing '/'"
        uri.rawUserInfo != null -> "baseUrl must not carry user info; use `credential`"
        uri.scheme == "http" && !isLocalHost(uri.host) -> "plain http is only for local servers"
        else -> null
    }
}

private fun parsed(url: String): URI? =
    try {
        URI(url)
    } catch (ignored: URISyntaxException) {
        // Its message quotes the URL; the caller reports the shape problem instead.
        null
    }

private val BASE_URL = Regex("https?://[^\\s/?#]+(/[^\\s?#]*[^\\s?#/])?")

/** How a provider authenticates (model-layer §4). Secrets live in the credential store. */
@Serializable
public sealed interface Credential {
    /** A user-supplied API key. */
    @Serializable
    @SerialName("api_key")
    public data class ApiKey(
        /** Where the key is stored. */
        val secretRef: SecretRef,
    ) : Credential

    /** "Sign in with ChatGPT" token sharing (model-layer §4.2). */
    @Serializable
    @SerialName("chatgpt_plan")
    public data class ChatGptPlan(
        /** Where the sign-in session (tokens, client id) is stored. */
        val sessionRef: SecretRef,
    ) : Credential

    /** No authentication (local servers). */
    @Serializable
    @SerialName("none")
    public data object None : Credential
}

/**
 * Key of an entry in the credential store. Not secret itself, so it may appear in logs; the
 * secret it names never does (fixtures show `{{credential:1}}`).
 */
@Serializable
@JvmInline
public value class SecretRef(
    /** Opaque, non-blank. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "SecretRef must not be blank" }
    }

    override fun toString(): String = value
}
