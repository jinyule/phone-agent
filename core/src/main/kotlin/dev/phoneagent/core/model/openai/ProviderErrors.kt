package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import java.util.Locale

/**
 * A provider's error report, from an HTTP error body or an error object inside a stream.
 *
 * @property message the provider's own words (shown to the user verbatim, truncated).
 * @property code `error.code` (string or number), or `null`.
 * @property type `error.type`, or `null`.
 * @property param `error.param`, or `null`.
 */
internal data class ProviderError(
    val message: String,
    val code: String?,
    val type: String?,
    val param: String?,
) {
    /** [message] cut to a size that fits a log line and an error dialog. */
    val detail: String get() = message.take(DETAIL_LIMIT)

    companion object {
        private const val DETAIL_LIMIT = 500

        /** Parses an error body; anything that is not a JSON error object becomes its own message. */
        fun parse(body: String): ProviderError {
            val root =
                try {
                    Json.parseToJsonElement(body)
                } catch (notJson: SerializationException) {
                    // HTML pages, plain-text proxies: the text itself is the best message we have.
                    val text = body.trim().ifEmpty { "(empty body: ${notJson::class.simpleName})" }
                    return ProviderError(text, null, null, null)
                }
            // Gemini's compatibility layer wraps the error object in an array.
            val obj = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject
            return obj?.let(::of) ?: ProviderError(body.trim(), null, null, null)
        }

        /** Reads `{"error": {...}}`, `{"error": "text"}` and flat `{"message" | "detail" | "msg", "code"}` bodies. */
        fun of(root: JsonObject): ProviderError {
            val error = root["error"]
            val inner = error as? JsonObject ?: root
            val message =
                (error as? JsonPrimitive)?.contentOrNull
                    ?: inner.text("message")
                    ?: inner.text("detail")
                    ?: inner.text("title")
                    ?: inner.text("msg")
                    ?: root.toString()
            // OpenRouter puts the upstream's words under metadata.raw; they explain "Provider returned error".
            val raw = (inner["metadata"] as? JsonObject)?.text("raw")
            return ProviderError(
                message = if (raw == null) message else "$message: $raw",
                code = inner.scalar("code"),
                type = inner.text("type"),
                param = inner.text("param"),
            )
        }

        private fun JsonObject.text(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.scalar(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }
}

/**
 * Maps provider failures to [ModelError] (model-layer §2) for both OpenAI-style protocols. Order:
 * well-known codes (`error.code` / `error.type`) first, then the HTTP status, refined by phrases
 * from the message where providers share a status for different causes (403 = bad key, unpaid
 * bill or model not enabled). Codes and phrases come from provider docs and the gateway fixtures
 * under `core/src/test/resources/wire/`.
 */
internal object ProviderErrors {
    /** The error for an HTTP failure. [plan] = ChatGPT plan sign-in (403 means the plan cannot use this). */
    fun fromHttp(
        status: Int,
        headers: Headers,
        body: String,
        plan: Boolean,
        nowMs: Long,
    ): ModelError {
        val error = ProviderError.parse(body)
        val retryAfter = retryAfterMs(headers, error.message, nowMs)
        return byCode(error, retryAfter) ?: fromStatus(status, error, plan, retryAfter)
    }

    /**
     * The error for a failure reported inside a 200 stream (`error` chunk, `response.failed`).
     * There is no HTTP status, so numeric codes in the 4xx/5xx range stand in for one and
     * unrecognized failures count as a server error (500), which the caller may retry.
     */
    fun fromStream(
        error: ProviderError,
        plan: Boolean,
    ): ModelError {
        val retryAfter = waitFromMessage(error.message)
        val status = error.code?.toIntOrNull()?.takeIf { it in Status.CLIENT_ERRORS || it in Status.SERVER_ERRORS }
        val overflow = Phrases.CONTEXT.any { it in error.message.lowercase(Locale.ROOT) }
        return byCode(error, retryAfter)
            ?: status?.let { fromStatus(it, error, plan, retryAfter) }
            ?: if (overflow) {
                ModelError.ContextOverflow(error.detail)
            } else {
                ModelError.Server(Status.INTERNAL_ERROR, error.detail)
            }
    }

    /**
     * Server-requested delay: `retry-after-ms`, then `retry-after` (seconds or an HTTP date), then
     * "try again in 2s" phrases in the message (OpenAI, Azure and Kimi state the wait only there).
     */
    fun retryAfterMs(
        headers: Headers,
        message: String,
        nowMs: Long,
    ): Long? {
        val millis =
            headers["retry-after-ms"]?.trim()?.toDoubleOrNull()?.toLong()
                ?: headers["retry-after"]?.trim()?.toDoubleOrNull()?.let { (it * MS_PER_SECOND).toLong() }
                ?: headers.getDate("retry-after")?.let { it.time - nowMs }
                ?: waitFromMessage(message)
        return millis?.coerceAtLeast(0)
    }

    private fun waitFromMessage(message: String): Long? =
        WAIT_PHRASE.find(message.lowercase(Locale.ROOT))?.let { match ->
            val unit = match.groupValues[2]
            val perUnit = if (unit.startsWith("ms") || unit.startsWith("milli")) 1.0 else MS_PER_SECOND
            (match.groupValues[1].toDouble() * perUnit).toLong()
        }

    private fun byCode(
        error: ProviderError,
        retryAfter: Long?,
    ): ModelError? {
        val keys = listOfNotNull(error.code, error.type).map { it.lowercase(Locale.ROOT) }
        val rule = CODE_RULES.firstOrNull { rule -> keys.any(rule.matches) } ?: return null
        return rule.error(error, retryAfter, keys.first(rule.matches))
    }

    private fun fromStatus(
        status: Int,
        error: ProviderError,
        plan: Boolean,
        retryAfter: Long?,
    ): ModelError =
        when (status) {
            in Status.CLIENT_ERRORS -> clientError(status, error, plan, retryAfter)
            Status.UNAVAILABLE, Status.OVERLOADED -> ModelError.Overloaded(retryAfter)
            in Status.SERVER_ERRORS -> ModelError.Server(status, error.detail)
            else -> ModelError.BadResponse("unexpected HTTP $status: ${error.detail}")
        }

    /** 4xx: the status decides where it is unambiguous, phrases where providers share one. */
    private fun clientError(
        status: Int,
        error: ProviderError,
        plan: Boolean,
        retryAfter: Long?,
    ): ModelError {
        val lower = error.message.lowercase(Locale.ROOT)

        fun says(phrases: List<String>) = phrases.any { it in lower }
        return when {
            status == Status.UNAUTHORIZED -> ModelError.Auth(error.detail)
            status == Status.PAYMENT_REQUIRED || says(Phrases.QUOTA) -> ModelError.QuotaExceeded(error.detail)
            status == Status.FORBIDDEN && plan -> ModelError.PlanNotEligible(error.detail)
            status == Status.REQUEST_TIMEOUT -> ModelError.Network("HTTP 408: ${error.detail}")
            status == Status.CONFLICT -> ModelError.Server(status, error.detail)
            status == Status.TOO_LARGE || says(Phrases.CONTEXT) -> ModelError.ContextOverflow(error.detail)
            status == Status.TOO_MANY_REQUESTS -> ModelError.RateLimited(retryAfter)
            else -> rejected(status, error, ::says)
        }
    }

    /** Other 4xx: the request (tools, model, key) was refused. */
    private fun rejected(
        status: Int,
        error: ProviderError,
        says: (List<String>) -> Boolean,
    ): ModelError =
        when {
            says(Phrases.TOOLS) -> ModelError.InvalidRequest("tools", error.detail)
            says(Phrases.MODEL) || status == Status.NOT_FOUND -> ModelError.InvalidRequest("model", error.detail)
            status == Status.FORBIDDEN -> ModelError.Auth(error.detail)
            else -> ModelError.InvalidRequest(error.param, error.detail)
        }

    private const val MS_PER_SECOND = 1000.0

    /** "try again in 11.054s", "retry after 7 seconds", "try again after 20 seconds". */
    private val WAIT_PHRASE =
        Regex("""(?:try again|retry)\s+(?:in|after)\s+(\d+(?:\.\d+)?)\s*(ms|milliseconds?|s|secs?|seconds?)\b""")

    /** First match wins, so the order is part of the mapping. */
    private val CODE_RULES: List<CodeRule> =
        listOf(
            Codes.rule(Codes.CONTEXT) { e, _, _ -> ModelError.ContextOverflow(e.detail) },
            Codes.rule(Codes.QUOTA) { e, _, _ -> ModelError.QuotaExceeded(e.detail) },
            Codes.rule(Codes.PLAN) { e, _, _ -> ModelError.PlanNotEligible(e.detail) },
            Codes.rule(Codes.REFUSAL) { _, _, key -> ModelError.Refusal(key) },
            Codes.rule(Codes.OVERLOADED) { _, wait, _ -> ModelError.Overloaded(wait) },
            CodeRule(Codes::isRateLimit) { _, wait, _ -> ModelError.RateLimited(wait) },
            Codes.rule(Codes.SIGNED_OUT) { e, _, _ -> ModelError.Auth(e.detail) },
            Codes.rule(Codes.UNSUPPORTED) { e, _, _ -> ModelError.InvalidRequest(e.param ?: "request", e.detail) },
            Codes.rule(Codes.MODEL) { e, _, _ -> ModelError.InvalidRequest("model", e.detail) },
        )
}

/** A code rule: which keys it matches and the error it makes (the matched key is the refusal category). */
private class CodeRule(
    val matches: (String) -> Boolean,
    val error: (ProviderError, Long?, String) -> ModelError,
)

/** HTTP statuses the mapping distinguishes. */
internal object Status {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val PAYMENT_REQUIRED = 402
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val REQUEST_TIMEOUT = 408
    const val CONFLICT = 409
    const val TOO_LARGE = 413
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_ERROR = 500
    const val UNAVAILABLE = 503
    const val OVERLOADED = 529
    val CLIENT_ERRORS = 400..499
    val SERVER_ERRORS = 500..599
}

/** Error codes and types (lowercase). Provider-specific numeric codes are noted with their provider. */
private object Codes {
    fun rule(
        codes: Set<String>,
        error: (ProviderError, Long?, String) -> ModelError,
    ) = CodeRule({ it in codes }, error)

    fun isRateLimit(key: String): Boolean = key in RATE_LIMIT || RATE_LIMIT_PREFIXES.any(key::startsWith)

    val CONTEXT = setOf("context_length_exceeded", "context_window_exceeded")
    val QUOTA =
        setOf(
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "usage_limit_reached",
            "subscription_sharing_usage_limit_exceeded",
            // Kimi; DashScope (Qwen); Volcengine Ark (Doubao); GLM "account in arrears"; SiliconFlow "balance".
            "exceeded_current_quota_error",
            "arrearage",
            "accountoverdueerror",
            "1113",
            "30001",
        )
    val PLAN = setOf("usage_not_included", "subscription_sharing_user_not_eligible")
    val REFUSAL =
        setOf(
            "content_filter",
            "content_policy_violation",
            "invalid_prompt",
            "cyber_policy",
            "bio_policy",
            "misalignment_policy_violation",
            // DashScope (Qwen) inspection; GLM "unsafe or sensitive content".
            "data_inspection_failed",
            "1301",
        )
    val OVERLOADED =
        setOf(
            "server_is_overloaded",
            "overloaded_error",
            "engine_overloaded_error",
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_user_unavailable",
        )
    val RATE_LIMIT =
        setOf(
            "rate_limit_exceeded",
            "slow_down",
            "rate_limit_reached_error",
            "rate_limit_error",
            // GLM concurrency limit.
            "1302",
        )

    /** Ark `RateLimitExceeded.EndpointRPMExceeded`, DashScope `Throttling.RateQuota`. */
    val RATE_LIMIT_PREFIXES = listOf("ratelimitexceeded", "throttling")
    val SIGNED_OUT =
        setOf(
            "invalid_api_key",
            "subscription_sharing_invalid_user",
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context",
        )
    val UNSUPPORTED = setOf("subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported")
    val MODEL =
        setOf(
            "model_not_found",
            "modelnotopen",
            "model.notactivated",
            "model.accessdenied",
            // GLM "model does not exist" / "no permission"; SiliconFlow "model does not exist" / "not available".
            "1211",
            "1220",
            "20012",
            "30011",
        )
}

/** Lowercase phrases for statuses that providers share between causes. */
private object Phrases {
    val CONTEXT =
        listOf(
            "maximum context length",
            "context length",
            "context_length",
            "context window",
            "prompt is too long",
            "input is too long",
            "too many tokens",
            "token limit",
            "range of input length",
            "上下文长度",
            "超出最大长度",
            "超过最大长度",
        )
    val QUOTA =
        listOf(
            "insufficient balance",
            "balance is insufficient",
            "insufficient account balance",
            "requires more credits",
            "overdue balance",
            "欠费",
            "余额不足",
        )
    val TOOLS =
        listOf(
            "does not support tools",
            "does not support tool",
            "doesn't support tools",
            "tool use is not supported",
            "tool calling is not supported",
            "function calling is not supported",
            "does not support function calling",
            "support tool use",
            "tool choice requires",
            "不支持工具",
            "不支持函数调用",
        )
    val MODEL =
        listOf(
            "model_not_found",
            "model not found",
            "not found the model",
            "model does not exist",
            "no such model",
            "unknown model",
            "has not activated",
            "not activated",
            "not available for your account",
            "do not have access to the model",
            "does not have access to model",
            "模型不存在",
            "未开通",
            "无权访问",
        )
}
