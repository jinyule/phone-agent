package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Keys a server echoes never reach a [ModelError] (not its message, cause chain or stack trace) or
 * the `providerMetadata` of a successful reply.
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
    fun `a successful reply whose metadata echoes the key`() =
        runTest {
            val stream =
                Kit.sse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"msg_$key","model":"m-$key",""" +
                        """"context_management":{"applied_edits":[{"note":"$key"}]}}}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"stop_sequence","stop_sequence":"$key",""" +
                        """"stop_details":{"type":"x","note":"$key"}},"usage":{"output_tokens":1}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                )
            server.enqueueSse(stream)
            val reply = model().complete(Kit.request())
            val printed = reply.providerMetadata.toString()
            assertFalse(printed.contains(key), "the key leaked into providerMetadata (value not shown)")
            assertEquals(5, Regex(Regex.escape(Redactor.MASK)).findAll(printed).count(), "every echo is masked")
            assertEquals("\"stop_sequence\"", reply.providerMetadata!!["stop_reason"].toString(), "the rest is kept")
            assertEquals(listOf<Part>(Part.Text("ok")), reply.parts)
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
