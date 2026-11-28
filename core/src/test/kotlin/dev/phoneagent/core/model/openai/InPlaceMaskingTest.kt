package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Provider text that names a credential without hiding one behind an escape keeps its words
 * (model-layer §2, "服务商的错误说明"): `Bearer …` masks only the token after it and never makes
 * the text withheld, a key the request sent or a credential shape with nothing to decode is masked
 * in place, and only text whose lenient decoding changes it and then shows a credential is not
 * shown at all. Assertions never print the keys.
 */
class InPlaceMaskingTest {
    @StartStop
    private val server = Kit.server()

    private fun model(): ChatModel =
        Kit.adapters(Keys(KEY)).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    /** The message of the error a call answered as [enqueue] queued it gets, checked to hold no piece of a key. */
    private suspend fun shown(enqueue: () -> Unit): String {
        enqueue()
        // Any type: a failure message must not print an error before it is checked.
        val error = assertThrows<Throwable> { model().send(Kit.request()) }
        val printed = "${error.message} $error ${error.stackTraceToString()}"
        for (key in listOf(KEY, SHAPED)) assertNoPiece(key, printed)
        return error.message.orEmpty()
    }

    private fun assertShows(
        expected: String,
        shown: String,
        what: String,
    ) {
        assertTrue(shown.contains(expected), "$what: $shown")
        assertFalse(shown.contains(Redactor.WITHHELD), "$what: withheld")
    }

    @Test
    fun `an error naming a bearer token keeps its words`() =
        runTest {
            val text = "Invalid bearer token"
            val masked = "Invalid bearer ***"
            assertShows(masked, shown { server.enqueueError(401, message(text)) }, "JSON")
            assertShows(masked, shown { server.enqueueError(502, text) }, "not JSON")
            val chunk = """{"error":{"message":"$text","code":"401"}}"""
            assertShows(masked, shown { server.enqueueSse(Kit.sse(chunk)) }, "a stream's error chunk")
            val missing =
                "You didn't provide an API key. You need to provide your API key in an Authorization header " +
                    "using Bearer auth (i.e. Authorization: Bearer YOUR_KEY)."
            val said = shown { server.enqueueError(401, message(missing)) }
            assertShows("You didn't provide an API key.", said, "a missing key")
            assertShows("Authorization: Bearer ***", said, "a missing key")
        }

    @Test
    fun `a bearer key is masked and the words around it kept`() =
        runTest {
            for (key in listOf(KEY, SHAPED)) {
                val said = shown { server.enqueueError(401, message("Bearer $key rejected")) }
                assertShows("Bearer *** rejected", said, if (key == KEY) "the request's key" else "a key shape")
            }
        }

    @Test
    fun `a key shape with nothing to decode is masked in place`() =
        runTest {
            val text = "upstream key $SHAPED rejected"
            val masked = "upstream key *** rejected"
            assertShows(masked, shown { server.enqueueError(401, message(text)) }, "a message")
            assertShows(masked, shown { server.enqueueError(502, text) }, "not JSON")
            assertShows(
                "Provider returned error: $masked",
                shown { server.enqueueError(400, raw(text)) },
                "metadata.raw",
            )
            val noMessage = """{"error":{"key":"$SHAPED","code":"bad_key"}}"""
            val whole = """{"error":{"key":"***","code":"bad_key"}}"""
            assertShows(whole, shown { server.enqueueError(400, noMessage) }, "no message field")
        }

    @Test
    fun `an escape that hides no credential leaves a bearer token's words`() =
        runTest {
            // Upstream JSON written into metadata.raw keeps its own escapes once the body is decoded.
            val upstream = "{$BS\"error$BS\":$BS\"Invalid bearer token $BS${BS}u2014 check the header$BS\"}"
            assertShows(
                "Invalid bearer *** ${BS}u2014 check the header",
                shown { server.enqueueError(401, raw(upstream, quoted = false)) },
                "metadata.raw",
            )
            assertShows(
                "Invalid bearer *** ${BS}u2014 check the header",
                shown { server.enqueueError(502, "Invalid bearer token ${BS}u2014 check the header") },
                "not JSON",
            )
        }

    @Test
    fun `a bearer key an escape hides is still withheld`() =
        runTest {
            val hidden = "Bearer ${KEY.replace("holder", "$BS${BS}u0068older")} rejected"
            val said = shown { server.enqueueError(401, message(hidden)) }
            assertTrue(said.endsWith(Redactor.WITHHELD), "the request's key behind an escape")
            val shape = "upstream key ${SHAPED.replace("holder", "${BS}u0068older")} rejected"
            assertTrue(shown { server.enqueueError(502, shape) }.endsWith(Redactor.WITHHELD), "a key shape")
        }

    private companion object {
        val BS = Char(92)

        // Assembled at run time so secret scanners do not take the placeholders for real keys.

        /** The request's key. */
        val KEY = listOf("sk", "proj", "placeholder-r7-0001").joinToString("-")

        /** A key-shaped value the request did not send. */
        val SHAPED = listOf("sk", "proj", "placeholder-shape-r7-0002").joinToString("-")

        const val MIN_PIECE = 8

        /** An OpenAI-style error body whose message is [text], written into JSON as it is. */
        fun message(text: String): String = """{"error":{"message":"$text","code":"invalid_api_key"}}"""

        /** An OpenRouter error body whose `metadata.raw` is [text] (already escaped unless [quoted]). */
        fun raw(
            text: String,
            quoted: Boolean = true,
        ): String {
            val value = if (quoted) text.replace("\"", "$BS\"") else text
            return """{"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"$value"}}}"""
        }

        /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [key]; never prints it. */
        fun assertNoPiece(
            key: String,
            text: String,
        ) {
            val leaked = (0..key.length - MIN_PIECE).any { text.contains(key.substring(it, it + MIN_PIECE)) }
            assertFalse(leaked, "the error holds a piece of the key (value not shown)")
        }
    }
}
