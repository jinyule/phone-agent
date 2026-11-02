package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Every `complete()` is exactly one HTTP request, so the caller's retry budget and log
 * (`requestError`) see every send: OkHttp's own retries and redirects are off, and the adapter
 * never resends (a stale thinking signature included). Each case queues a success behind the
 * failure; a second request would consume it.
 */
class OneRequestPerAttemptTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun `408 is not resent`() =
        runTest {
            server.enqueueError(408, """{"type":"error","error":{"type":"timeout_error","message":"timeout"}}""")
            server.enqueueSse(Kit.TEXT_REPLY)
            val error = assertThrows<ModelError.Server> { Kit.model(server).complete(Kit.request()) }
            assertEquals(408, error.status, "the status as sent")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `503 with retry-after 0 is not resent`() =
        runTest {
            server.enqueueError(
                503,
                """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""",
                "Retry-After" to "0",
            )
            server.enqueueSse(Kit.TEXT_REPLY)
            val error = assertThrows<ModelError.Overloaded> { Kit.model(server).complete(Kit.request()) }
            assertEquals(0L, error.retryAfterMs, "the wait still reaches the caller")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a reused connection that drops after the request is not resent`() =
        runTest {
            val model = Kit.model(server)
            server.enqueueSse(Kit.TEXT_REPLY)
            model.complete(Kit.request())
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            server.enqueueSse(Kit.TEXT_REPLY)
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
                    .addHeader("Location", server.url("/v2/messages"))
                    .build(),
            )
            server.enqueueSse(Kit.TEXT_REPLY)
            val error = assertThrows<ModelError.InvalidRequest> { Kit.model(server).complete(Kit.request()) }
            assertTrue(error.message!!.contains("/v2/messages"), "the target is named: ${error.message}")
            assertFalse(error.retryable, "a redirect is not retried either")
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a stale thinking signature goes to the caller instead of a resend without thinking`() =
        runTest {
            server.enqueueError(
                400,
                """{"type":"error","error":{"type":"invalid_request_error","message":"messages.1.content.0: """ +
                    """Invalid `signature` in `thinking` block. The block is bound to a different conversation."}}""",
            )
            server.enqueueSse(Kit.TEXT_REPLY)
            assertThrows<ModelError.PrefixBindingMismatch> { Kit.model(server).complete(Kit.request()) }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a rejected key is not asked for again`() =
        runTest {
            val keys = Keys()
            server.enqueueError(
                401,
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
            )
            server.enqueueSse(Kit.TEXT_REPLY)
            assertThrows<ModelError.Auth> { Kit.model(server, credentials = keys).complete(Kit.request()) }
            assertEquals(1, server.requestCount)
            assertEquals(listOf(Kit.KEY to false), keys.asked)
        }
}
