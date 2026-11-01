package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ProviderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A configured provider (model-layer §2): where to send requests, with which protocol and
 * credential. Validated on construction, so a bad saved profile fails loudly when loaded.
 *
 * @property id stable id, referenced by session logs.
 * @property displayName user-chosen label.
 * @property protocol wire protocol.
 * @property baseUrl `https://…` (or `http://` for local servers such as Ollama), without trailing `/`.
 * @property credential how to authenticate; secrets are referenced, never stored here.
 * @property headers extra request headers; must not carry credentials.
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
        require(BASE_URL.matches(baseUrl)) {
            "Provider $id: baseUrl must be http(s)://host[/path] without trailing '/'"
        }
        val credentialHeaders = headers.keys.filter { it.lowercase() in CREDENTIAL_HEADERS }
        require(credentialHeaders.isEmpty()) {
            "Provider $id: credentials go in `credential`, not headers $credentialHeaders"
        }
        require(headers.keys.all { HEADER_NAME.matches(it) }) { "Provider $id: invalid header name in ${headers.keys}" }
        require(credential !is Credential.ChatGptPlan || protocol == ModelProtocol.OPENAI_RESPONSES) {
            "Provider $id: ChatGPT plan sign-in only works with the Responses protocol"
        }
        require(reasoningField == null || protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
            "Provider $id: reasoningField applies to Chat Completions only"
        }
        require(!echoReasoning || reasoningField != null) { "Provider $id: echoReasoning needs reasoningField" }
    }
}

private val BASE_URL = Regex("https?://[^\\s/?#]+(/[^\\s?#]*[^\\s?#/])?")
private val HEADER_NAME = Regex("[A-Za-z0-9-]+")
private val CREDENTIAL_HEADERS = setOf("authorization", "x-api-key", "api-key", "proxy-authorization", "cookie")

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
