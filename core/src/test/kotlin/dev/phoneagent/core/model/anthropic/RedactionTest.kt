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
 * Keys a server echoes never reach a [ModelError] (not its message, cause chain or stack trace),
 * where they are masked, or the `providerMetadata` of a successful reply, where a field masking
 * would change is left out.
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

    /**
     * The body is read up to a limit (64 KiB). A key that crosses it must not leave its first
     * characters behind: masking happens before the cut, and the cut never splits a secret.
     */
    @Test
    fun `an error body whose key crosses the read limit leaves no part of it`() =
        runTest {
            server.enqueueError(502, " ".repeat(BODY_LIMIT - KEY_HEAD) + key)
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertNoPiece(key, error.stackTraceToString())
        }

    @Test
    fun `a shaped key cut at the read limit too short for its shape leaves no part of it`() =
        runTest {
            server.enqueueError(502, " ".repeat(BODY_LIMIT - KEY_HEAD) + Kit.FAKE_KEY)
            val error =
                assertThrows<ModelError.Server> {
                    Kit.model(server, credentials = Keys(Kit.FAKE_KEY)).complete(Kit.request())
                }
            assertNoPiece(Kit.FAKE_KEY, error.stackTraceToString())
        }

    /** Reading past the limit to see a key whole must not show the start of a key the read cut. */
    @Test
    fun `a multibyte error body whose read ends inside the key leaves no part of it`() =
        runTest {
            server.enqueueError(502, "中".repeat(BODY_LIMIT / UTF8_CJK + KEY_HEAD) + key)
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertNoPiece(key, error.stackTraceToString())
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
    fun `unparseable stream data that holds the key is described, not quoted`() =
        runTest {
            server.enqueueSse(Kit.sse("message_start" to """{"type":"message_start","message":{"id":"$key"""))
            val error = assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            assertNoPiece(key, error.stackTraceToString())
            assertEquals(
                "bad response: event is not JSON: Unexpected JSON token at offset 76: " +
                    "Expected quotation mark '\"', but had 'EOF' instead at path: \$",
                error.message,
            )
        }

    private fun assertNoPiece(
        secret: String,
        printed: String,
    ) {
        val pieces = secret.windowed(PIECE).filter { it in printed }
        assertTrue(pieces.isEmpty(), "part of the key leaked: $pieces")
    }

    /** The JSON parser quotes the input around the error at a fixed width, which can cut the key. */
    @Test
    fun `unparseable stream data whose quoted input cuts the key`() =
        runTest {
            val data = """{"type":"message_start","message":{"id":"${"x".repeat(250)}",$key}}"""
            server.enqueueSse(Kit.sse("message_start" to data))
            val error = assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            assertNoPiece(key, error.stackTraceToString())
        }

    @Test
    fun `a metadata field that echoes the key is left out whole, not half masked`() =
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
            val metadata = reply.providerMetadata!!
            assertFalse(metadata.toString().contains(Redactor.MASK), "no half-masked value: $metadata")
            assertEquals(setOf("requested_model", "stop_reason"), metadata.keys, "every echoing field is left out")
            assertEquals("\"stop_sequence\"", metadata["stop_reason"].toString(), "the rest is kept")
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
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                )
            server.enqueueSse(stream)
            assertClean(assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) })
        }

    private companion object {
        /** What an error body is read up to (HttpReply). */
        const val BODY_LIMIT = 64 * 1024

        /** Characters of the key that fall before the read limit. */
        const val KEY_HEAD = 10

        /** Bytes of one CJK character in UTF-8. */
        const val UTF8_CJK = 3

        /** Shortest piece of a key that counts as a leak. */
        const val PIECE = 6
    }
}
