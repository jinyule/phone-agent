package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.base
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * A response header HTTP does not allow (here a `Retry-After` with a non-ASCII character and the
 * request's key) never fails the call with OkHttp's validation error, which quotes the value: an
 * error status fails as its own [ModelError] with the header taken as absent, a streamed or whole
 * reply is read as usual, and the response is closed either way. Assertions never print the key.
 */
class RemoteHeaderValidationTest {
    @StartStop
    private val server = MockWebServer()

    private val client: OkHttpClient = AnthropicTimeouts().client()

    private fun model() =
        Kit.adapters(Keys(KEY), client).model(Kit.profile(server.base()), Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())

    private fun enqueue(
        code: Int,
        contentType: String,
        body: String,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", contentType)
            .addHeaderLenient("Retry-After", "é $KEY")
            .body(body)
            .build(),
    )

    /** Fails when [text] holds the key; never prints it. */
    private fun assertClean(
        what: String,
        text: String,
    ) = assertFalse(text.contains(KEY), "$what holds the key (value not shown)")

    private fun assertResponseClosed() =
        assertEquals(
            0,
            client.connectionPool.connectionCount() - client.connectionPool.idleConnectionCount(),
            "no connection stays allocated to an open response",
        )

    /** Runs [block]; anything it throws is checked for the key before anything else looks at it. */
    private suspend fun <T> outcome(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Any type: OkHttp's validation error is an IllegalArgumentException, not a ModelError.
            assertClean("the error", "${failure.message} $failure ${failure.stackTraceToString()}")
            Result.failure(failure)
        }

    @Test
    fun `an error status fails as its own error with the header taken as absent`() =
        runTest {
            enqueue(TOO_MANY_REQUESTS, "application/json", RATE_LIMITED)
            val error = outcome { model().complete(Kit.request()) }.exceptionOrNull()
            assertTrue(error is ModelError.RateLimited, "the 429 fails as rate limited")
            assertNull((error as ModelError.RateLimited).retryAfterMs, "the unusable Retry-After counts as absent")
            assertResponseClosed()
        }

    @Test
    fun `a streamed reply is read as usual`() =
        runTest {
            enqueue(OK, "text/event-stream", Kit.TEXT_REPLY)
            val reply = outcome { model().complete(Kit.request()) }
            assertEquals(listOf(Part.Text("Hi")), reply.getOrNull()?.parts, "the reply comes through")
            assertResponseClosed()
        }

    @Test
    fun `a whole JSON reply is read as usual`() =
        runTest {
            enqueue(OK, "application/json", WHOLE_REPLY)
            val reply = outcome { model().complete(Kit.request()) }
            assertEquals(listOf(Part.Text("Hi")), reply.getOrNull()?.parts, "the reply comes through")
            assertResponseClosed()
        }

    @Test
    fun `a whole reply's own failure is not quoted either`() =
        runTest {
            enqueue(OK, "application/json", "{not json")
            val error = outcome { model().complete(Kit.request()) }.exceptionOrNull()
            assertTrue(error is ModelError.BadResponse, "fails as a bad response")
            assertResponseClosed()
        }

    private companion object {
        /** A placeholder secret without a credential shape. */
        const val KEY = "plain-placeholder-retry-after-0001"
        const val OK = 200
        const val TOO_MANY_REQUESTS = 429
        const val RATE_LIMITED = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""
        const val WHOLE_REPLY =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
                """"content":[{"type":"text","text":"Hi"}],"stop_reason":"end_turn",""" +
                """"usage":{"input_tokens":1,"output_tokens":1}}"""
    }
}
