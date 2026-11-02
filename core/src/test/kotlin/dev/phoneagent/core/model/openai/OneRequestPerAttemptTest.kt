package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.literalUrl
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Every `complete()` attempt is exactly one HTTP request, so the caller's retry budget and log
 * (`requestError`) see every send: OkHttp's own retries and redirects are off. Each case queues a
 * success behind the failure; a second request would consume it.
 */
class OneRequestPerAttemptTest {
    @StartStop
    private val server = Kit.server()

    private val success =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )

    /** A model on the production HTTP client. */
    private fun model(): ChatModel =
        Kit
            .adapters(Keys(), HttpTimeouts().client())
            .unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    @Test
    fun `408 is not resent`() =
        runTest {
            server.enqueueError(408, """{"error":{"message":"request timeout"}}""")
            server.enqueueSse(success)
            assertThrows<ModelError.Network> { model().complete(Kit.request()) }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `503 with retry-after 0 is not resent`() =
        runTest {
            server.enqueueError(503, """{"error":{"message":"busy"}}""", "Retry-After" to "0")
            server.enqueueSse(success)
            val error = assertThrows<ModelError.Overloaded> { model().complete(Kit.request()) }
            assertEquals(0L, error.retryAfterMs, "the wait still reaches the caller")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a reused connection that drops after the request is not resent`() =
        runTest {
            val model = model()
            server.enqueueSse(success)
            model.complete(Kit.request())
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueueSse(success)
            assertThrows<ModelError.Network> { model.complete(Kit.request()) }
            assertEquals(2, server.requestCount, "one request per call")
        }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 307, 308])
    fun `a redirect is not followed and is not retryable`(status: Int) =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(status)
                    .addHeader("Location", server.literalUrl("/v2/chat/completions"))
                    .build(),
            )
            server.enqueueSse(success)
            val error = assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            assertNull(error.param)
            assertTrue(error.detail.contains("/v2/chat/completions"), "the target is named: ${error.detail}")
            assertTrue(error.detail.contains("base URL"), "the fix is named: ${error.detail}")
            assertFalse(error.retryable, "the caller must not resend to the same address")
            assertEquals(1, server.requestCount)
        }
}
