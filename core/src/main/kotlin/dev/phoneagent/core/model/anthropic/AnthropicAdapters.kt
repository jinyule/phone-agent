package dev.phoneagent.core.model.anthropic

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
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Entry point of the Anthropic Messages adapter (model-layer §3.3). One instance per app: it owns
 * the HTTP client (connection pool) every model it creates shares. The wire format is implemented
 * on OkHttp directly, not with the official Java SDK (ADR 2026-11-01-anthropic-adapter).
 */
public class AnthropicAdapters internal constructor(
    http: OkHttpClient,
    credentials: CredentialResolver,
    clock: Clock,
    io: CoroutineDispatcher,
) {
    /**
     * @param credentials turns profile credentials into secrets at request time.
     * @param timeouts HTTP timeouts; validated on construction.
     * @param clock wall clock for HTTP-date `retry-after` values.
     */
    public constructor(
        credentials: CredentialResolver,
        timeouts: AnthropicTimeouts = AnthropicTimeouts(),
        clock: Clock = Clock.System,
    ) : this(timeouts.client(), credentials, clock, Dispatchers.IO)

    internal val env: AnthropicEnv = AnthropicEnv(HttpTransport(http, io), credentials, clock)

    /**
     * The model [model] behind [profile]. [blobs] reads the images of the session it serves.
     * Requests are shaped by their own `wire` snapshot ([AnthropicWire.snapshot]), never by
     * [capabilities] or later changes to [profile]. Throws [IllegalArgumentException] for a profile
     * of another protocol.
     */
    public fun model(
        profile: ProviderProfile,
        model: ModelId,
        capabilities: ModelCapabilities,
        blobs: BlobSource,
    ): ChatModel {
        require(profile.protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
            "Provider ${profile.id}: ${profile.protocol} is not Anthropic Messages"
        }
        return MessagesModel(profile, ModelInfo(profile.id, model, profile.protocol, capabilities), blobs, env)
    }
}

/**
 * HTTP timeouts of the adapter, in milliseconds. [readMs] is the longest silence allowed between
 * two bytes of a stream: thinking can run for minutes before the first text.
 *
 * @property connectMs TCP and TLS connect, > 0.
 * @property readMs idle time between received bytes, > 0.
 * @property writeMs idle time while sending the request (large screenshots), > 0.
 * @property idleConnectionKeepAlive how long a connection stays in the pool between requests for
 *   reuse, from zero (the default: no connection is reused) to five minutes. A server can close an
 *   idle connection without saying so, and the next request on it then fails (OkHttp's resend is
 *   off, model-layer §3.1); raise it only for a server whose own idle timeout is known to be
 *   longer.
 */
public data class AnthropicTimeouts(
    val connectMs: Long = 15_000,
    val readMs: Long = 300_000,
    val writeMs: Long = 60_000,
    val idleConnectionKeepAlive: Duration = Duration.ZERO,
) {
    init {
        require(connectMs > 0 && readMs > 0 && writeMs > 0) { "HTTP timeouts must be > 0 ms: $this" }
        require(idleConnectionKeepAlive >= Duration.ZERO && idleConnectionKeepAlive <= MAX_KEEP_ALIVE) {
            "idleConnectionKeepAlive must be between 0 and $MAX_KEEP_ALIVE: $idleConnectionKeepAlive"
        }
    }

    /**
     * The adapter's client. Every `complete()` is one HTTP request: the caller retries
     * (`requestError`) and logs each attempt, so OkHttp's own resends are off — connection-failure
     * retries, redirects (a 307/308 would resend the POST) and the 503 `Retry-After: 0` follow-up
     * ([RetryAfterStash]). Its pool keeps idle connections for [idleConnectionKeepAlive].
     */
    internal fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeMs, TimeUnit.MILLISECONDS)
            .connectionPool(pool())
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor(RetryAfterStash)
            .build()

    /** OkHttp needs a positive keep-alive, so "none" is a pool that keeps no idle connection. */
    private fun pool(): ConnectionPool =
        if (idleConnectionKeepAlive == Duration.ZERO) {
            ConnectionPool(0, 1, TimeUnit.NANOSECONDS)
        } else {
            ConnectionPool(MAX_IDLE_CONNECTIONS, idleConnectionKeepAlive.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        }
}

/** The longest [AnthropicTimeouts.idleConnectionKeepAlive]. */
private val MAX_KEEP_ALIVE = 5.minutes

/** Idle connections a pool that keeps any may hold: OkHttp's default. */
private const val MAX_IDLE_CONNECTIONS = 5

/** What every model of one [AnthropicAdapters] shares. */
internal class AnthropicEnv(
    val transport: HttpTransport,
    private val credentials: CredentialResolver,
    val clock: Clock,
) {
    /** The secret for [credential]; refuses values HTTP headers cannot carry (without echoing them). */
    suspend fun secret(credential: Credential): String? {
        val secret = credentials.secret(credential, rejected = false)?.trim()
        if (secret != null && secret.any { it < ' ' || it > '~' }) {
            throw ModelError.Auth("the stored credential contains characters an HTTP header cannot carry")
        }
        return secret?.takeIf { it.isNotEmpty() }
    }

    /** The POST of [body] to [url] with the headers [rules] and [betas] call for. */
    fun post(
        url: HttpUrl,
        rules: WireRules,
        betas: List<String>,
        secret: String?,
        body: JsonObject,
    ): Request {
        val builder =
            Request
                .Builder()
                .url(url)
                .headers(rules.headers)
                .header("anthropic-version", rules.apiVersion)
                .header("Accept", "text/event-stream")
        if (betas.isNotEmpty()) builder.header("anthropic-beta", betas.joinToString(","))
        secret?.let {
            if (rules.bearer) {
                builder.header(
                    "Authorization",
                    "Bearer $it",
                )
            } else {
                builder.header("x-api-key", it)
            }
        }
        return builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }
}

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
