package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.ports.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Entry point of the OpenAI-style adapters (Chat Completions and Responses, model-layer §2–§4).
 * One instance per app: it owns the HTTP client (connection pool) that every model it creates
 * shares. OkHttp stays an implementation detail of `:core`.
 */
public class OpenAiAdapters internal constructor(
    http: OkHttpClient,
    credentials: CredentialResolver,
    clock: Clock,
    io: CoroutineDispatcher,
    newCallId: () -> String,
) {
    /**
     * @param credentials turns profile credentials into secrets at request time.
     * @param timeouts HTTP timeouts; validated on construction.
     * @param clock wall clock for HTTP-date `retry-after` values.
     */
    public constructor(
        credentials: CredentialResolver,
        timeouts: HttpTimeouts = HttpTimeouts(),
        clock: Clock = Clock.System,
    ) : this(timeouts.client(), credentials, clock, Dispatchers.IO, ::randomCallId)

    internal val env: AdapterEnv = AdapterEnv(HttpTransport(http, io), credentials, clock, newCallId)

    /**
     * The model [model] behind [profile]. [blobs] reads the images of the session it serves (blobs
     * are stored per session, so create one model per session or pass a session-aware source).
     * [wire] is the request shape saved with the profile by "test and save" (the preset's
     * defaults until then); the model never changes it. Throws [IllegalArgumentException] for
     * profiles the OpenAI adapters cannot serve: another protocol, or header values that are not
     * valid HTTP.
     */
    public fun model(
        profile: ProviderProfile,
        model: ModelId,
        capabilities: ModelCapabilities,
        blobs: BlobSource,
        wire: WireOptions = WireOptions.defaultsFor(profile),
    ): ChatModel {
        val info = ModelInfo(profile.id, model, profile.protocol, capabilities)
        return when (profile.protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> {
                ChatCompletionsModel(profile, info, wire, blobs, env)
            }

            ModelProtocol.OPENAI_RESPONSES -> {
                ResponsesModel(profile, info, wire, blobs, env)
            }

            ModelProtocol.ANTHROPIC_MESSAGES -> {
                throw IllegalArgumentException("Provider ${profile.id}: Anthropic Messages is not an OpenAI protocol")
            }
        }
    }

    /** "Test and save" for profiles of these protocols (model-layer §4.1). */
    public fun probe(): ProviderProbe = ProviderProbe(this)
}

/**
 * HTTP timeouts of the adapters, in milliseconds. [readMs] is the longest silence allowed between
 * two bytes of a stream: reasoning models can think for minutes without sending anything.
 *
 * @property connectMs TCP and TLS connect, > 0.
 * @property readMs idle time between received bytes, > 0.
 * @property writeMs idle time while sending the request (large screenshots), > 0.
 */
public data class HttpTimeouts(
    val connectMs: Long = 15_000,
    val readMs: Long = 300_000,
    val writeMs: Long = 60_000,
) {
    init {
        require(connectMs > 0 && readMs > 0 && writeMs > 0) { "HTTP timeouts must be > 0 ms: $this" }
    }

    /**
     * The adapters' client. Every `complete()` attempt is one HTTP request: the caller retries
     * (`requestError`) and logs each attempt, so OkHttp's own resends are off — connection-failure
     * retries, redirects (a 307/308 would resend the POST) and the 503 `Retry-After: 0` follow-up
     * ([RetryAfterStash]).
     */
    internal fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(RetryAfterStash)
            .build()
}

/** What every adapter instance shares. */
internal class AdapterEnv(
    val transport: HttpTransport,
    private val credentials: CredentialResolver,
    val clock: Clock,
    val newCallId: () -> String,
) {
    /** The secret for [credential]; refuses values HTTP headers cannot carry (without echoing them). */
    suspend fun secret(
        credential: Credential,
        rejected: Boolean,
    ): String? {
        val secret = credentials.secret(credential, rejected)?.trim()
        if (secret != null && secret.any { it < ' ' || it > '~' }) {
            throw ModelError.Auth("the stored credential contains characters an HTTP header cannot carry")
        }
        return secret?.takeIf { it.isNotEmpty() }
    }

    /** A JSON POST to [url] with the profile's [headers] and a bearer [secret]. */
    fun post(
        url: HttpUrl,
        headers: Headers,
        secret: String?,
        body: JsonObject,
    ): Request = builder(url, headers, secret).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()

    /** A GET to [url] with the profile's [headers] and a bearer [secret]. */
    fun get(
        url: HttpUrl,
        headers: Headers,
        secret: String?,
    ): Request = builder(url, headers, secret).header("Accept", "application/json").get().build()

    private fun builder(
        url: HttpUrl,
        headers: Headers,
        secret: String?,
    ): Request.Builder {
        val builder = Request.Builder().url(url).headers(headers)
        secret?.let { builder.header("Authorization", "Bearer $it") }
        return builder.header("Accept", "text/event-stream")
    }

    /** The [ModelError] for an HTTP failure; [redactor] masks what the body echoes. */
    fun error(
        failure: HttpFailure,
        plan: Boolean,
        redactor: Redactor,
    ): ModelError = ProviderErrors.fromHttp(failure, plan, clock.epochMillis(), redactor)
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** A non-2xx answer: status, headers and (truncated, redacted) body. */
internal class HttpFailure(
    val status: Int,
    val headers: Headers,
    val body: String,
)

/** Result of one HTTP exchange of a model call. */
internal sealed interface Exchange<out T> {
    /** 2xx, read to the end. */
    class Done<T>(
        val value: T,
    ) : Exchange<T>

    /** Non-2xx. */
    class Failed(
        val failure: HttpFailure,
    ) : Exchange<Nothing>
}

/**
 * Sends [request]; reads a 2xx body with [read], or returns the failure with its body masked by
 * [redactor] (which holds every secret the call has sent so far).
 */
internal suspend fun <T> AdapterEnv.exchange(
    request: Request,
    redactor: Redactor,
    read: suspend (HttpReply) -> T,
): Exchange<T> =
    transport.send(request, redactor) { reply ->
        if (reply.successful) {
            Exchange.Done(read(reply))
        } else {
            Exchange.Failed(HttpFailure(reply.status, reply.headers, redactor.text(reply.text())))
        }
    }

/** The profile's extra headers; throws [IllegalArgumentException] when a value is not valid HTTP. */
internal fun ProviderProfile.httpHeaders(): Headers =
    try {
        headers.toHeaders()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Provider $id: a header value is not valid HTTP", e)
    }

private fun randomCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "")
