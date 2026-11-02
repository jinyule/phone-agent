package dev.phoneagent.core.model

import kotlinx.serialization.Serializable

/**
 * Every failure a [ChatModel] reports (model-layer §2). Handled by `requestError` interceptors.
 * Messages are English diagnostics; the UI maps [kind] to localized text.
 *
 * Never a `CancellationException`: cancellation is not a model error.
 */
public sealed class ModelError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Stable classification, recorded in the session log. */
    public abstract val kind: ModelErrorKind

    /** Whether sending the same request again may succeed. */
    public val retryable: Boolean get() = kind.retryable

    /**
     * Delay the server asked for before a retry (`Retry-After`), in milliseconds, or `null` when it
     * named none; never negative (construction rejects it). Only [RateLimited], [Overloaded] and
     * [Server] carry one. Adapters turn a value they cannot parse, or a negative one, into `null`:
     * a bad header is not left to fail construction.
     */
    public open val retryAfterMs: Long? get() = null

    /** The API key is invalid or lacks permission. */
    public class Auth(
        detail: String,
    ) : ModelError("authentication failed: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.AUTH
    }

    /** The ChatGPT sign-in can no longer be refreshed; the user must sign in again. */
    public class SignInExpired : ModelError("sign-in expired") {
        override val kind: ModelErrorKind get() = ModelErrorKind.SIGN_IN_EXPIRED
    }

    /** The account or plan may not use this channel (e.g. missing `chatgpt.tokens.use.direct`). */
    public class PlanNotEligible(
        detail: String,
    ) : ModelError("plan not eligible: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.PLAN_NOT_ELIGIBLE
    }

    /** Usage quota exhausted (billing or plan limit); retrying will not help until it resets. */
    public class QuotaExceeded(
        detail: String,
    ) : ModelError("quota exceeded: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.QUOTA_EXCEEDED
    }

    /** Too many requests; wait [retryAfterMs] when the server said so (`null` = not stated). */
    public class RateLimited(
        /** Server-requested delay, or `null`. */
        override val retryAfterMs: Long?,
    ) : ModelError("rate limited (retry after ${retryAfterMs ?: "?"} ms)") {
        init {
            requireDelay(retryAfterMs)
        }

        override val kind: ModelErrorKind get() = ModelErrorKind.RATE_LIMITED
    }

    /** The provider is temporarily overloaded (e.g. HTTP 529). */
    public class Overloaded(
        /** Server-requested delay, or `null`. */
        override val retryAfterMs: Long?,
    ) : ModelError("provider overloaded") {
        init {
            requireDelay(retryAfterMs)
        }

        override val kind: ModelErrorKind get() = ModelErrorKind.OVERLOADED
    }

    /** The request does not fit the context window; compact and retry. */
    public class ContextOverflow(
        detail: String,
    ) : ModelError("context overflow: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.CONTEXT_OVERFLOW
    }

    /** The provider rejected the request as malformed (HTTP 400 and similar). */
    public class InvalidRequest(
        /** Offending parameter when the provider names it, else `null`. */
        public val param: String?,
        /** The provider's message, truncated. */
        public val detail: String,
    ) : ModelError("invalid request${param?.let { " ($it)" }.orEmpty()}: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.INVALID_REQUEST
    }

    /** The model refused (`stop_reason: refusal`, content filter). */
    public class Refusal(
        /** Provider category (e.g. `stop_details.category`), or `null`. */
        public val category: String?,
    ) : ModelError("model refused${category?.let { " ($it)" }.orEmpty()}") {
        override val kind: ModelErrorKind get() = ModelErrorKind.REFUSAL
    }

    /** Reasoning blocks no longer match the history prefix; retry once without them (model-layer §5). */
    public class PrefixBindingMismatch(
        detail: String,
    ) : ModelError("reasoning prefix mismatch: $detail") {
        override val kind: ModelErrorKind get() = ModelErrorKind.PREFIX_BINDING_MISMATCH
    }

    /** Transport failure: DNS, connect, TLS, timeout, or the stream was cut before completion. */
    public class Network(
        detail: String,
        cause: Throwable? = null,
    ) : ModelError("network: $detail", cause) {
        override val kind: ModelErrorKind get() = ModelErrorKind.NETWORK
    }

    /** The provider failed with HTTP [status] (5xx). Unmapped 4xx statuses are [InvalidRequest]. */
    public class Server(
        /** HTTP status. */
        public val status: Int,
        detail: String,
        /** Server-requested delay, or `null`. */
        override val retryAfterMs: Long? = null,
    ) : ModelError("server error $status: $detail") {
        init {
            requireDelay(retryAfterMs)
        }

        override val kind: ModelErrorKind get() = ModelErrorKind.SERVER
    }

    /** The reply violated the protocol (unparseable JSON, missing fields). */
    public class BadResponse(
        detail: String,
        cause: Throwable? = null,
    ) : ModelError("bad response: $detail", cause) {
        override val kind: ModelErrorKind get() = ModelErrorKind.BAD_RESPONSE
    }
}

private fun requireDelay(retryAfterMs: Long?) {
    require(retryAfterMs == null || retryAfterMs >= 0) { "retryAfterMs must be >= 0, got $retryAfterMs" }
}

/** Stable codes of [ModelError] subclasses, one each; [retryable] is the default retry policy. */
@Serializable
public enum class ModelErrorKind(
    /** Whether a plain retry of the same request may succeed. */
    public val retryable: Boolean,
) {
    /** [ModelError.Auth]. */
    AUTH(false),

    /** [ModelError.SignInExpired]. */
    SIGN_IN_EXPIRED(false),

    /** [ModelError.PlanNotEligible]. */
    PLAN_NOT_ELIGIBLE(false),

    /** [ModelError.QuotaExceeded]. */
    QUOTA_EXCEEDED(false),

    /** [ModelError.RateLimited]. */
    RATE_LIMITED(true),

    /** [ModelError.Overloaded]. */
    OVERLOADED(true),

    /** [ModelError.ContextOverflow]: needs compaction first, not a plain retry. */
    CONTEXT_OVERFLOW(false),

    /** [ModelError.InvalidRequest]. */
    INVALID_REQUEST(false),

    /** [ModelError.Refusal]. */
    REFUSAL(false),

    /** [ModelError.PrefixBindingMismatch]: needs the request changed first. */
    PREFIX_BINDING_MISMATCH(false),

    /** [ModelError.Network]. */
    NETWORK(true),

    /** [ModelError.Server]. */
    SERVER(true),

    /** [ModelError.BadResponse]. */
    BAD_RESPONSE(true),
}
