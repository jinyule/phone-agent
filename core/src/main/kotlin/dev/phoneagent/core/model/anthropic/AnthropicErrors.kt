package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import java.util.Locale

/**
 * An Anthropic error report: `{"type": "error", "error": {"type": …, "message": …}}`, from an HTTP
 * error body or an `error` event inside a stream.
 *
 * @property type `error.type` (`invalid_request_error`, `overloaded_error`, …), or `null`.
 * @property message the provider's own words, already redacted.
 */
internal data class AnthropicError(
    val type: String?,
    val message: String,
) {
    /** [message] cut to a size that fits a log line and an error dialog. */
    val detail: String get() = message.take(DETAIL_LIMIT)

    companion object {
        private const val DETAIL_LIMIT = 500

        /**
         * Parses an error body; anything that is not an Anthropic error object becomes its own
         * message. Credentials in the body are masked with [redactor] before anything is read.
         */
        fun parse(
            body: String,
            redactor: Redactor,
        ): AnthropicError {
            val clean = redactor.text(body)
            val root =
                try {
                    Json.parseToJsonElement(clean)
                } catch (notJson: SerializationException) {
                    // HTML pages and plain-text proxies: the text itself is the best message there is.
                    return AnthropicError(null, clean.trim().ifEmpty { "(empty body: ${notJson::class.simpleName})" })
                }
            return (root as? JsonObject)?.let { of(it, redactor) } ?: AnthropicError(null, clean.trim())
        }

        /**
         * Reads `{"error": {"type", "message"}}`, also the flat `{"type", "message"}` some gateways
         * send. The message is masked again: JSON escapes can hide a secret from a scan of the raw text.
         */
        fun of(
            root: JsonObject,
            redactor: Redactor,
        ): AnthropicError {
            val inner = root["error"] as? JsonObject ?: root
            val message = inner.text("message") ?: (root["error"] as? JsonPrimitive)?.contentOrNull ?: root.toString()
            val type = inner.text("type")?.takeIf { it != "error" }
            return AnthropicError(type, redactor.text(message))
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * Maps Anthropic failures to [ModelError] (model-layer §2, §3.3). `error.type` decides first, then
 * the HTTP status; only an `invalid_request_error` (400) is refined by phrases: a thinking block
 * whose signature no longer matches the history (preserved thinking) is
 * [ModelError.PrefixBindingMismatch], an input that does not fit is [ModelError.ContextOverflow].
 * A 429 is always a rate limit, whatever its message says. Types and phrases come from the Claude
 * API error reference and the fixtures under `core/src/test/resources/wire/anthropic/`.
 */
internal object AnthropicErrors {
    /** The error for an HTTP failure; [redactor] masks the credentials the body may echo. */
    fun fromHttp(
        status: Int,
        headers: Headers,
        body: String,
        nowMs: Long,
        redactor: Redactor,
    ): ModelError {
        if (status in Status.REDIRECTS) {
            // Redirects are not followed (one request per attempt); the address needs fixing.
            val target = headers["Location"]?.let(redactor::text) ?: "an unnamed address"
            return ModelError.BadResponse("HTTP $status redirect to $target: use the final address as the base URL")
        }
        val error = AnthropicError.parse(body, redactor)
        val wait = retryAfterMs(headers, nowMs)
        return when (status) {
            // Before any phrase: "too many tokens per minute" is a rate limit, not an overflow.
            Status.TOO_MANY_REQUESTS -> ModelError.RateLimited(wait)

            else -> byType(error, wait, status) ?: byStatus(status, error, wait)
        }
    }

    /**
     * The error for an `error` event inside a 200 stream, as read with the stream's redactor. There
     * is no status, so an unknown type counts as a server error (500), which the caller may retry.
     */
    fun fromStream(error: AnthropicError): ModelError =
        byType(error, null, null) ?: ModelError.Server(Status.INTERNAL_ERROR, error.detail)

    /**
     * Server-requested delay: `retry-after-ms`, then `retry-after` (seconds or an HTTP date; a date
     * in the past means no wait). A value that is not a finite, non-negative wait within `Long`
     * milliseconds (`Infinity`, `1e309`, `NaN`, `-5`) is skipped for the next source.
     */
    fun retryAfterMs(
        headers: Headers,
        nowMs: Long,
    ): Long? =
        millis(headers["retry-after-ms"], 1.0)
            ?: millis(headers["retry-after"], MS_PER_SECOND)
            ?: headers.getDate("retry-after")?.let { (it.time - nowMs).coerceAtLeast(0) }

    private fun millis(
        value: String?,
        perUnit: Double,
    ): Long? {
        val ms = (value?.trim()?.toDoubleOrNull() ?: return null) * perUnit
        return ms.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }

    /** By `error.type`; [status] is the HTTP status, `null` inside a stream. */
    private fun byType(
        error: AnthropicError,
        wait: Long?,
        status: Int?,
    ): ModelError? =
        when (error.type) {
            "invalid_request_error" -> invalid(error)
            "authentication_error", "permission_error" -> ModelError.Auth(error.detail)
            "billing_error" -> ModelError.QuotaExceeded(error.detail)
            "not_found_error" -> ModelError.InvalidRequest("model", error.detail)
            "request_too_large" -> ModelError.ContextOverflow(error.detail)
            "rate_limit_error" -> ModelError.RateLimited(wait)
            "overloaded_error" -> ModelError.Overloaded(wait)
            "api_error" -> ModelError.Server(status ?: Status.INTERNAL_ERROR, error.detail, wait)
            "timeout_error" -> ModelError.Server(status ?: Status.GATEWAY_TIMEOUT, error.detail, wait)
            else -> null
        }

    private fun byStatus(
        status: Int,
        error: AnthropicError,
        wait: Long?,
    ): ModelError =
        when (status) {
            Status.BAD_REQUEST -> invalid(error)
            Status.UNAUTHORIZED, Status.FORBIDDEN -> ModelError.Auth(error.detail)
            Status.PAYMENT_REQUIRED -> ModelError.QuotaExceeded(error.detail)
            Status.NOT_FOUND -> ModelError.InvalidRequest("model", error.detail)
            Status.REQUEST_TIMEOUT -> ModelError.Network("HTTP 408: ${error.detail}")
            Status.TOO_LARGE -> ModelError.ContextOverflow(error.detail)
            Status.UNAVAILABLE, Status.OVERLOADED -> ModelError.Overloaded(wait)
            in Status.SERVER_ERRORS -> ModelError.Server(status, error.detail, wait)
            in Status.CLIENT_ERRORS -> ModelError.InvalidRequest(paramOf(error.message), error.detail)
            else -> ModelError.BadResponse("unexpected HTTP $status: ${error.detail}")
        }

    /** A 400: a stale thinking signature, an input that does not fit, or another refused parameter. */
    private fun invalid(error: AnthropicError): ModelError {
        val lower = error.message.lowercase(Locale.ROOT)
        return when {
            BINDING_PHRASES.any { it in lower } -> ModelError.PrefixBindingMismatch(error.detail)
            CONTEXT_PHRASES.any { it in lower } -> ModelError.ContextOverflow(error.detail)
            else -> ModelError.InvalidRequest(paramOf(error.message), error.detail)
        }
    }

    /** The field path a validation message starts with (`messages.2.content.0: …`), or `null`. */
    private fun paramOf(message: String): String? = PARAM_PREFIX.find(message)?.groupValues?.get(1)

    private const val MS_PER_SECOND = 1000.0

    /** `messages.1.content.0.tool_use.input: Field required`, `tool_choice: …`. */
    private val PARAM_PREFIX = Regex("""^([a-z_][a-z0-9_]*(?:\.[a-z0-9_]+)*): """)

    /** Preserved thinking: the block's signature does not cover this history (model-layer §5). */
    private val BINDING_PHRASES =
        listOf(
            "bound to a different conversation",
            "invalid `signature` in `thinking` block",
            "invalid signature in thinking block",
        )

    private val CONTEXT_PHRASES =
        listOf(
            "prompt is too long",
            "exceed context limit",
            "exceeds the context window",
            "context window",
            "context length",
        )
}

/** HTTP statuses the mapping distinguishes. */
internal object Status {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val PAYMENT_REQUIRED = 402
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
    const val TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_ERROR = 500
    const val UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
    const val OVERLOADED = 529
    val REDIRECTS = 300..399
    val CLIENT_ERRORS = 400..499
    val SERVER_ERRORS = 500..599
}
