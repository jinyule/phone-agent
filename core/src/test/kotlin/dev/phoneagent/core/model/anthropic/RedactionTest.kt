package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Keys a server echoes never reach a [ModelError]: not its message, cause chain or stack trace.
 * The key used here has no credential shape, so only the request-secret layer can catch it.
 */
class RedactionTest {
    @StartStop
    private val server = MockWebServer()

    private val key = "plain-key-without-a-known-shape-123"

    private fun model() = Kit.model(server, credentials = Keys(key))

    private fun assertClean(error: Throwable) {
        val printed = error.stackTraceToString()
        assertFalse(printed.contains(key), "the key leaked: $printed")
        assertTrue(printed.contains(Redactor.MASK), "the echo was masked, not dropped: $printed")
    }

    @Test
    fun `a 401 that echoes the key`() =
        runTest {
            server.enqueueError(
                401,
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key: $key"}}""",
            )
            assertClean(assertThrows<ModelError.Auth> { model().complete(Kit.request()) })
        }

    @Test
    fun `a key hidden behind JSON escapes`() =
        runTest {
            val escaped = key.replace("-", "\\u002d")
            server.enqueueError(
                400,
                """{"type":"error","error":{"type":"invalid_request_error","message":"bad key $escaped"}}""",
            )
            assertClean(assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) })
        }

    @Test
    fun `a non-JSON error page that echoes the key`() =
        runTest {
            server.enqueueError(502, "<html>upstream rejected $key</html>")
            assertClean(assertThrows<ModelError.Server> { model().complete(Kit.request()) })
        }

    @Test
    fun `a stream error event that echoes the key`() =
        runTest {
            val stream =
                Kit.sse(
                    "message_start" to """{"type":"message_start","message":{"id":"m","model":"x"}}""",
                    "error" to """{"type":"error","error":{"type":"api_error","message":"failed for $key"}}""",
                )
            server.enqueueSse(stream)
            assertClean(assertThrows<ModelError.Server> { model().complete(Kit.request()) })
        }

    @Test
    fun `unparseable stream data that quotes the key`() =
        runTest {
            server.enqueueSse(Kit.sse("message_start" to """{"type":"message_start","message":{"id":"$key"""))
            assertClean(assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) })
        }

    @Test
    fun `a protocol violation that quotes the key`() =
        runTest {
            val stream =
                Kit.sse(
                    "message_start" to """{"type":"message_start","message":{"id":"m","model":"x"}}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"$key"}}""",
                )
            server.enqueueSse(stream)
            assertClean(assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) })
        }
}
