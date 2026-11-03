package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Anthropic error bodies and statuses map to [ModelError] kinds (model-layer §2, §3.3). */
class AnthropicErrorsTest {
    private fun map(
        status: Int,
        body: String,
        headers: Headers = Headers.EMPTY,
    ): ModelError = AnthropicErrors.fromHttp(status, headers, body, Kit.NOW_MS, Redactor.SHAPES_ONLY)

    private fun body(
        type: String,
        message: String,
    ) = """{"type":"error","error":{"type":"$type","message":"$message"},"request_id":"req_011Test"}"""

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    fun `documented errors map to their kind`(
        status: Int,
        type: String,
        message: String,
        kind: ModelErrorKind,
    ) {
        assertEquals(kind, map(status, body(type, message)).kind)
    }

    @Test
    fun `a stale thinking signature is a prefix binding mismatch`() {
        val message =
            "messages.3.content.0: Invalid `signature` in `thinking` block. The block is bound to a different " +
                "conversation. Remove the block, or set " +
                "`thinking.block_binding.prefix_mismatch_behavior` to \\\"drop_block\\\"."
        val error = map(400, body("invalid_request_error", message))
        assertTrue(error is ModelError.PrefixBindingMismatch, error.toString())
        assertFalse(error.retryable, "the request must change first (model-layer §5)")
        val tampered =
            map(400, body("invalid_request_error", "messages.1.content.0: Invalid `signature` in `thinking` block"))
        assertTrue(tampered is ModelError.PrefixBindingMismatch, tampered.toString())
    }

    @Test
    fun `other 400s name the field the message starts with`() {
        val error =
            map(
                400,
                body("invalid_request_error", "tool_choice: type \\\"any\\\" is not supported for this model."),
            ) as ModelError.InvalidRequest
        assertEquals("tool_choice", error.param)
        val nested = map(400, body("invalid_request_error", "messages.1.content.0.tool_use.input: Field required"))
        assertEquals("messages.1.content.0.tool_use.input", (nested as ModelError.InvalidRequest).param)
        val plain = map(400, body("invalid_request_error", "Your credit balance is too low"))
        assertNull((plain as ModelError.InvalidRequest).param)
    }

    @Test
    fun `an input that does not fit is a context overflow, but a 429 never is`() {
        assertEquals(
            ModelErrorKind.CONTEXT_OVERFLOW,
            map(400, body("invalid_request_error", "prompt is too long: 1050000 tokens > 1000000 maximum")).kind,
        )
        assertEquals(
            ModelErrorKind.CONTEXT_OVERFLOW,
            map(
                400,
                body(
                    "invalid_request_error",
                    "input length and `max_tokens` exceed context limit: 990000 + 64000 > 1000000",
                ),
            ).kind,
        )
        val limited =
            map(
                429,
                body("rate_limit_error", "Number of input tokens per minute exceeds your context window limit"),
                headersOf("retry-after", "7"),
            )
        assertEquals(ModelErrorKind.RATE_LIMITED, limited.kind)
        assertEquals(7_000L, limited.retryAfterMs)
        assertEquals(ModelErrorKind.RATE_LIMITED, map(429, body("invalid_request_error", "prompt is too long")).kind)
    }

    @Test
    fun `retry-after is passed on only when it is a finite, non-negative wait`() {
        fun wait(vararg headers: String) =
            map(529, body("overloaded_error", "Overloaded"), headersOf(*headers)).retryAfterMs
        assertEquals(1500L, wait("retry-after-ms", "1500", "retry-after", "9"))
        assertEquals(2_500L, wait("retry-after", "2.5"))
        assertEquals(0L, wait("retry-after", "0"))
        assertNull(wait("retry-after", "-5"))
        assertNull(wait("retry-after", "Infinity"))
        assertNull(wait("retry-after", "1e309"))
        assertNull(wait("retry-after", "NaN"))
        assertNull(wait("retry-after-ms", "NaN"))
        assertNull(wait())
        assertEquals(30_000L, wait("retry-after", httpDate(Kit.NOW_MS + 30_000)))
        assertEquals(0L, wait("retry-after", httpDate(Kit.NOW_MS - 30_000)), "a date in the past means no wait")
        assertEquals(4_000L, map(500, body("api_error", "Internal"), headersOf("retry-after", "4")).retryAfterMs)
        assertEquals(4_000L, map(429, "", headersOf("retry-after", "4")).retryAfterMs)
    }

    @Test
    fun `statuses decide when the body says nothing useful`() {
        assertEquals(ModelErrorKind.AUTH, map(401, "<html>Unauthorized</html>").kind)
        assertEquals(ModelErrorKind.AUTH, map(403, "").kind)
        assertEquals(ModelErrorKind.QUOTA_EXCEEDED, map(402, "{}").kind)
        assertEquals("model", (map(404, "Not Found") as ModelError.InvalidRequest).param)
        assertEquals(ModelErrorKind.NETWORK, map(408, "timeout").kind)
        assertEquals(ModelErrorKind.CONTEXT_OVERFLOW, map(413, "too big").kind)
        assertEquals(ModelErrorKind.OVERLOADED, map(503, "unavailable").kind)
        assertEquals(ModelErrorKind.OVERLOADED, map(529, "[]").kind)
        assertEquals(502, (map(502, "bad gateway") as ModelError.Server).status)
        assertEquals(ModelErrorKind.INVALID_REQUEST, map(422, """{"error":"plain string error"}""").kind)
        assertEquals(ModelErrorKind.INVALID_REQUEST, map(400, """{"message":"flat body"}""").kind)
        assertEquals(ModelErrorKind.BAD_RESPONSE, map(600, "?").kind)
        assertTrue(map(500, "").message!!.contains("empty body"), "an empty body is named as such")
    }

    @Test
    fun `redirects are configuration errors that name their target, masked and cut`() {
        val error = map(307, "", headersOf("Location", "https://proxy.example.com/v1/messages"))
        assertTrue(error is ModelError.InvalidRequest, error.toString())
        assertNull((error as ModelError.InvalidRequest).param)
        assertFalse(error.retryable, "the same address redirects again")
        assertTrue(error.detail.contains("https://proxy.example.com/v1/messages"), error.detail)
        assertTrue(error.detail.contains("check the base URL"), error.detail)
        val keyed = map(307, "", headersOf("Location", "https://proxy.example.com/v1/messages?key=sk-abcdefghijklmnop"))
        val detail = (keyed as ModelError.InvalidRequest).detail
        val target = "https://proxy.example.com/v1/messages?key=***"
        assertEquals("HTTP 307 redirect to $target: check the base URL and use the final address", detail)
        assertTrue(map(308, "").message!!.contains("an unnamed address"))
        val long = map(301, "", headersOf("Location", "https://proxy.example.com/" + "a".repeat(1000)))
        assertTrue(long.message!!.length < 500, "the target is cut: ${long.message!!.length}")
    }

    @Test
    fun `a redirect target is masked before it is cut, so a key across the cut leaves no piece`() {
        val key = "plain-placeholder-location-0001"
        val prefix = "https://proxy.example.com/"
        val location = prefix + "a".repeat(KEY_AT - prefix.length) + key
        val error =
            AnthropicErrors.fromHttp(307, headersOf("Location", location), "", Kit.NOW_MS, Redactor(listOf(key)))
        val detail = (error as ModelError.InvalidRequest).detail
        val pieces = key.windowed(PIECE).filter { it in detail }
        assertTrue(pieces.isEmpty(), "pieces of the key in the target: ${pieces.size} (not shown)")
        assertTrue(detail.contains(prefix + "a".repeat(KEY_AT - prefix.length)), "the target up to the key is kept")
    }

    @Test
    fun `stream error events map by type, unknown types are server errors`() {
        fun stream(type: String?) = AnthropicErrors.fromStream(AnthropicError(type, "boom"))
        assertEquals(ModelErrorKind.OVERLOADED, stream("overloaded_error").kind)
        assertEquals(ModelErrorKind.RATE_LIMITED, stream("rate_limit_error").kind)
        assertEquals(ModelErrorKind.SERVER, stream("api_error").kind)
        assertEquals(504, (stream("timeout_error") as ModelError.Server).status)
        assertEquals(500, (stream(null) as ModelError.Server).status)
        assertEquals(ModelErrorKind.INVALID_REQUEST, stream("invalid_request_error").kind)
    }

    @Test
    fun `details are cut to fit a dialog`() {
        val error = map(400, body("invalid_request_error", "x".repeat(2_000))) as ModelError.InvalidRequest
        assertEquals(500, error.detail.length)
    }

    private fun httpDate(ms: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    companion object {
        /** Where the key starts in the redirect target: before the 300-character cut, ending past it. */
        private const val KEY_AT = 290

        /** The shortest piece of a key that counts as part of it. */
        private const val PIECE = 6

        @JvmStatic
        fun cases(): List<Array<Any>> =
            listOf(
                arrayOf(400, "invalid_request_error", "max_tokens: Field required", ModelErrorKind.INVALID_REQUEST),
                arrayOf(401, "authentication_error", "invalid x-api-key", ModelErrorKind.AUTH),
                arrayOf(402, "billing_error", "Your credit balance is too low", ModelErrorKind.QUOTA_EXCEEDED),
                arrayOf(403, "permission_error", "Your API key does not have permission", ModelErrorKind.AUTH),
                arrayOf(404, "not_found_error", "model: claude-opus-9", ModelErrorKind.INVALID_REQUEST),
                arrayOf(
                    413,
                    "request_too_large",
                    "Request exceeds the maximum allowed number of bytes",
                    ModelErrorKind.CONTEXT_OVERFLOW,
                ),
                arrayOf(
                    429,
                    "rate_limit_error",
                    "Number of request tokens has exceeded your rate limit",
                    ModelErrorKind.RATE_LIMITED,
                ),
                arrayOf(500, "api_error", "Internal server error", ModelErrorKind.SERVER),
                arrayOf(504, "timeout_error", "Request timed out", ModelErrorKind.SERVER),
                arrayOf(529, "overloaded_error", "Overloaded", ModelErrorKind.OVERLOADED),
                arrayOf(418, "teapot_error", "unknown type", ModelErrorKind.INVALID_REQUEST),
            )
    }
}
