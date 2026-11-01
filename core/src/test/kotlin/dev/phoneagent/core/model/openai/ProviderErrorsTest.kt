package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ProviderErrorsTest {
    private val none = headersOf()

    private fun http(
        status: Int,
        body: String,
        plan: Boolean = false,
    ): ModelError = ProviderErrors.fromHttp(HttpFailure(status, none, body), plan, Kit.NOW_MS, Redactor.SHAPES_ONLY)

    private fun openAi(
        message: String,
        code: String? = null,
        type: String? = null,
        param: String? = null,
    ): String {
        fun field(
            name: String,
            value: String?,
        ) = value?.let { ",\"$name\":\"$it\"" }.orEmpty()
        return "{\"error\":{\"message\":\"$message\"${field(
            "code",
            code,
        )}${field("type", type)}${field("param", param)}}}"
    }

    @ParameterizedTest(name = "{0} {1} -> {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "401 | Incorrect API key provided   |                          | AUTH",
            "403 | Your key is not allowed       |                          | AUTH",
            "402 | Payment required              |                          | QUOTA_EXCEEDED",
            "429 | You exceeded your current quota | insufficient_quota     | QUOTA_EXCEEDED",
            "429 | Rate limit reached            | rate_limit_exceeded      | RATE_LIMITED",
            "429 | Too many requests             |                          | RATE_LIMITED",
            "400 | This model's maximum context length is 8192 tokens |   | CONTEXT_OVERFLOW",
            "400 | too long                      | context_length_exceeded  | CONTEXT_OVERFLOW",
            "413 | Request Entity Too Large      |                          | CONTEXT_OVERFLOW",
            "404 | The model `x` does not exist  | model_not_found          | INVALID_REQUEST",
            "404 | Not Found                     |                          | INVALID_REQUEST",
            "400 | This model does not support tools |                      | INVALID_REQUEST",
            "400 | Invalid prompt                | invalid_prompt           | REFUSAL",
            "400 | 输入数据可能包含不适当内容      | data_inspection_failed   | REFUSAL",
            "408 | Request timeout               |                          | NETWORK",
            "409 | Conflict                      |                          | SERVER",
            "500 | boom                          |                          | SERVER",
            "502 | Bad gateway                   |                          | SERVER",
            "503 | Service unavailable           |                          | OVERLOADED",
            "529 | Overloaded                    |                          | OVERLOADED",
            "500 | The server is overloaded      | server_is_overloaded     | OVERLOADED",
            "400 | 您的账户已欠费                 |                          | QUOTA_EXCEEDED",
            "429 | 请求频率超限                   | 1302                     | RATE_LIMITED",
            "429 | RPM exceeded | RateLimitExceeded.EndpointRPMExceeded     | RATE_LIMITED",
            "400 | Model access denied           | Model.AccessDenied       | INVALID_REQUEST",
            "302 | Found                         |                          | BAD_RESPONSE",
        ],
    )
    fun `statuses and codes map to error kinds`(
        status: Int,
        message: String,
        code: String?,
        kind: ModelErrorKind,
    ) {
        assertEquals(kind, http(status, openAi(message, code)).kind)
    }

    @Test
    fun `the provider's words are kept and truncated`() {
        val long = "x".repeat(600)
        val error = http(400, openAi(long, param = "messages"))
        assertInstanceOf(ModelError.InvalidRequest::class.java, error)
        assertEquals("messages", (error as ModelError.InvalidRequest).param)
        assertEquals(500, error.detail.length)
    }

    @Test
    fun `tool and model rejections name the parameter`() {
        assertEquals("tools", (http(400, openAi("Tool use is not supported")) as ModelError.InvalidRequest).param)
        assertEquals("model", (http(400, openAi("unknown model 'x'")) as ModelError.InvalidRequest).param)
        assertEquals("model", (http(404, "") as ModelError.InvalidRequest).param)
    }

    @Test
    fun `plan mode turns 403 and plan codes into PlanNotEligible`() {
        assertEquals(ModelErrorKind.PLAN_NOT_ELIGIBLE, http(403, openAi("Forbidden"), plan = true).kind)
        assertEquals(ModelErrorKind.AUTH, http(403, openAi("Forbidden"), plan = false).kind)
        assertEquals(ModelErrorKind.PLAN_NOT_ELIGIBLE, http(400, openAi("no", "usage_not_included")).kind)
        assertEquals(
            ModelErrorKind.OVERLOADED,
            http(400, openAi("busy", "subscription_sharing_usage_unavailable")).kind,
        )
        assertEquals(ModelErrorKind.AUTH, http(400, openAi("signed out", "subscription_sharing_invalid_user")).kind)
        val unsupported = http(400, openAi("nope", "subscription_sharing_unsupported_capability", param = "tools"))
        assertEquals("tools", (unsupported as ModelError.InvalidRequest).param)
        val route = http(400, openAi("nope", "subscription_sharing_route_not_supported"))
        assertEquals("request", (route as ModelError.InvalidRequest).param)
    }

    @Test
    fun `refusal codes become the refusal category`() {
        val refusal = http(400, openAi("blocked", type = "cyber_policy")) as ModelError.Refusal
        assertEquals("cyber_policy", refusal.category)
    }

    @Test
    fun `error bodies of every shape are read`() {
        assertEquals("plain words", ProviderError.parse("  plain words ", Redactor.SHAPES_ONLY).message)
        assertEquals("text error", ProviderError.parse("{\"error\":\"text error\"}", Redactor.SHAPES_ONLY).message)
        val flat = ProviderError.parse("{\"code\":30001,\"message\":\"balance\",\"data\":null}", Redactor.SHAPES_ONLY)
        assertEquals(ProviderError("balance", "30001", null, null), flat)
        assertEquals(
            "gone",
            ProviderError.parse("[{\"error\":{\"code\":404,\"message\":\"gone\"}}]", Redactor.SHAPES_ONLY).message,
        )
        assertEquals("d", ProviderError.parse("{\"detail\":\"d\"}", Redactor.SHAPES_ONLY).message)
        assertEquals("t", ProviderError.parse("{\"title\":\"t\"}", Redactor.SHAPES_ONLY).message)
        assertEquals("m", ProviderError.parse("{\"msg\":\"m\"}", Redactor.SHAPES_ONLY).message)
        assertEquals("{\"status\":\"x\"}", ProviderError.parse("{\"status\":\"x\"}", Redactor.SHAPES_ONLY).message)
        assertEquals("[1]", ProviderError.parse("[1]", Redactor.SHAPES_ONLY).message)
        val routed =
            ProviderError.parse(
                "{\"error\":{\"message\":\"Provider returned error\",\"code\":400," +
                    "\"metadata\":{\"raw\":\"tool_choice requires tools\",\"provider_name\":\"X\"}}}",
                Redactor.SHAPES_ONLY,
            )
        assertEquals("Provider returned error: tool_choice requires tools", routed.message)
        assertEquals(
            ProviderError("m", null, null, null),
            ProviderError.parse("{\"message\":\"m\",\"code\":null}", Redactor.SHAPES_ONLY),
        )
    }

    @Test
    fun `empty bodies still classify by status`() {
        assertTrue(ProviderError.parse("", Redactor.SHAPES_ONLY).message.startsWith("(empty body"))
        assertEquals(ModelErrorKind.AUTH, http(401, "").kind)
    }

    @Test
    fun `retry-after comes from headers, then from the message`() {
        fun wait(
            message: String,
            vararg headers: String,
        ) = ProviderErrors.retryAfterMs(headersOf(*headers), message, Kit.NOW_MS)
        assertEquals(1500L, wait("", "retry-after-ms", "1500", "retry-after", "9"))
        assertEquals(2500L, wait("", "retry-after", "2.5"))
        assertEquals(30_000L, wait("", "retry-after", httpDate(Kit.NOW_MS + 30_000)))
        assertEquals(0L, wait("", "retry-after", "Thu, 01 Jan 2026 00:00:00 GMT"))
        assertEquals(11_054L, wait("Please try again in 11.054s."))
        assertEquals(20L, wait("retry after 20ms"))
        assertEquals(7000L, wait("Retry after 7 seconds"))
        assertNull(wait("no hint", "retry-after", "soon"))
    }

    @ParameterizedTest(name = "429 \"{0}\" -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "Too many tokens per minute. Slow down.            | RATE_LIMITED",
            "You have hit the token limit for this minute.     | RATE_LIMITED",
            "Request too large: context length is rate limited | RATE_LIMITED",
            "Insufficient balance, please top up               | QUOTA_EXCEEDED",
        ],
    )
    fun `429 is a rate or quota limit whatever the words`(
        message: String,
        kind: ModelErrorKind,
    ) {
        assertEquals(kind, http(429, openAi(message)).kind)
        assertEquals(kind, ProviderErrors.fromStream(ProviderError(message, "429", null, null), plan = false).kind)
    }

    @Test
    fun `invalid waits are skipped for the next source`() {
        fun wait(
            message: String,
            vararg headers: String,
        ) = ProviderErrors.retryAfterMs(headersOf(*headers), message, Kit.NOW_MS)
        for (bad in listOf("Infinity", "-Infinity", "1e309", "NaN", "-5", "1e30")) {
            assertNull(wait("", "retry-after", bad), "retry-after $bad")
            assertNull(wait("", "retry-after-ms", bad), "retry-after-ms $bad")
        }
        assertEquals(3000L, wait("", "retry-after-ms", "Infinity", "retry-after", "3"))
        assertEquals(4000L, wait("try again in 4s", "retry-after-ms", "NaN", "retry-after", "-1"))
        assertNull(wait("try again in 99999999999999999999999s"), "a wait that overflows is no wait")
        assertEquals(86_400_000L, wait("", "retry-after", "86400"), "large valid waits pass through uncapped")
        assertEquals(2_000_000_000_000L, wait("", "retry-after-ms", "2000000000000"))
    }

    private fun httpDate(epochMs: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    @Test
    fun `429 carries the wait`() {
        val headers = headersOf("retry-after", "3")
        val error =
            ProviderErrors.fromHttp(
                HttpFailure(429, headers, openAi("slow down")),
                false,
                Kit.NOW_MS,
                Redactor.SHAPES_ONLY,
            )
        assertEquals(3000L, (error as ModelError.RateLimited).retryAfterMs)
        val overloaded = ProviderErrors.fromHttp(HttpFailure(503, headers, ""), false, Kit.NOW_MS, Redactor.SHAPES_ONLY)
        assertEquals(3000L, (overloaded as ModelError.Overloaded).retryAfterMs)
    }

    @Test
    fun `stream errors use codes, numeric statuses and phrases`() {
        fun stream(
            message: String,
            code: String? = null,
        ) = ProviderErrors.fromStream(ProviderError(message, code, null, null), plan = false)
        val limited = stream("Rate limit reached. Please try again in 2s.", "rate_limit_exceeded")
        assertEquals(2000L, (limited as ModelError.RateLimited).retryAfterMs)
        assertEquals(ModelErrorKind.SERVER, stream("CUDA out of memory", "500").kind)
        assertEquals(ModelErrorKind.RATE_LIMITED, stream("busy", "429").kind)
        assertEquals(ModelErrorKind.CONTEXT_OVERFLOW, stream("prompt is too long").kind)
        assertEquals(ModelErrorKind.SERVER, stream("Provider disconnected", "server_error").kind)
        assertEquals(500, (stream("odd", "42") as ModelError.Server).status)
        val plan = ProviderErrors.fromStream(ProviderError("limit", "usage_limit_reached", null, null), plan = true)
        assertEquals(ModelErrorKind.QUOTA_EXCEEDED, plan.kind)
    }
}
