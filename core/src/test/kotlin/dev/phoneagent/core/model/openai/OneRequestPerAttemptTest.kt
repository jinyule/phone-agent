package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Every `complete()` attempt is exactly one HTTP request, so the caller's retry budget and log
 * (`requestError`) see every send: OkHttp's own retries and redirects are off. Each case queues a
 * success behind the failure; a second request would consume it.
 */
class OneRequestPerAttemptTest {
    @StartStop
    private val server = MockWebServer()

    private val success =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )

    /** A model on the production HTTP client. */
    private fun model(): ChatModel =
        Kit
            .adapters(Keys(), HttpTimeouts().client())
            .model(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

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

    @Test
    fun `307 is not followed`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(307)
                    .addHeader("Location", server.url("/v2/chat/completions"))
                    .build(),
            )
            server.enqueueSse(success)
            val error = assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            assertTrue(error.message!!.contains("/v2/chat/completions"), "the target is named: ${error.message}")
            assertEquals(1, server.requestCount)
        }
}
