package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A key an error body writes behind JSON escapes (model-layer §2, "服务端写的说明"). The body is
 * parsed before anything but the exact forms of the request's secrets is masked, so masking a
 * key's shape cannot stop at an escape and leave the rest of it readable once decoded; and text
 * the server wrote (a message field, OpenRouter's `metadata.raw`, a body that is not JSON or has
 * no message field) is not shown at all when its lenient decoding holds a credential, which also
 * catches an escape that JSON decoding left as text. The keys have the `sk-` shape; assertions
 * never print them.
 */
class EscapedErrorKeyTest {
    @StartStop
    private val server = Kit.server()

    /** A spelling of [key] in the raw body that JSON decoding turns into the key. */
    enum class Escaped(
        val spell: (String) -> String,
    ) {
        /** The slash written `\/`. */
        SLASH({ it.replace("/", "$BS/") }),

        /** A letter in the middle written as a `u` escape. */
        UNICODE({ it.replace("holder", "${BS}u0068older") }),
    }

    /** A spelling of [key] in the raw body that JSON decoding leaves as an escape, as text. */
    enum class Literal(
        val spell: (String) -> String,
    ) {
        SLASH({ it.replace("/", "$BS$BS/") }),
        UNICODE({ it.replace("holder", "$BS${BS}u0068older") }),

        /** The first letter, so the decoded text does not start with the shape's `sk-`. */
        FIRST({ "$BS${BS}u0073" + it.drop(1) }),
    }

    /** An error body with the spelled key [s] in it; [shown] is what the error says of it when [s] is `***`. */
    enum class Body(
        val status: Int,
        val text: (String) -> String,
        val shown: String,
    ) {
        JSON_MESSAGE(
            401,
            { s -> """{"error":{"message":"Incorrect API key provided: $s.","code":"invalid_api_key"}}""" },
            "Incorrect API key provided: ***.",
        ),
        NOT_JSON(502, { s ->
            "<html><body>Proxy saw key $s</body></html>"
        }, "<html><body>Proxy saw key ***</body></html>"),
        NO_MESSAGE(400, { s ->
            """{"error":{"key":"$s","code":"bad_key"}}"""
        }, """{"error":{"key":"***","code":"bad_key"}}"""),

        /** The key as the parameter a 400 names. */
        PARAM(400, { s -> """{"error":{"message":"bad value","param":"$s"}}""" }, "invalid request (***): bad value"),

        /** JSON that is not an error object. */
        JSON_STRING(502, { s -> "\"bad key $s\"" }, "\"bad key ***\""),
        METADATA_RAW(
            400,
            { s -> """{"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"bad key $s"}}}""" },
            "Provider returned error: bad key ***",
        ),
    }

    private fun model(sent: String): ChatModel =
        Kit.adapters(Keys(sent)).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    /** The error of a call whose answer [enqueue] queued, after checking it holds no piece of [key]. */
    private suspend fun failure(
        key: String,
        sent: String,
        enqueue: () -> Unit,
    ): ModelError {
        enqueue()
        // Any type: a failure message must not print an error before it is checked.
        val error = assertThrows<Throwable> { model(sent).send(Kit.request()) }
        assertNoPiece(key, "${error.message} $error ${error.stackTraceToString()}")
        return error as ModelError
    }

    @ParameterizedTest(name = "{0} body, the request's key escaped")
    @EnumSource(Body::class)
    fun `the request's key escaped in an error body leaves no piece once decoded`(body: Body) =
        runTest {
            for (escaped in Escaped.entries) {
                failure(KEY, KEY) { server.enqueueError(body.status, body.text(escaped.spell(KEY))) }
            }
        }

    @ParameterizedTest(name = "{0} body, a key the request did not send")
    @EnumSource(Body::class)
    fun `a key-shaped value escaped in an error body is masked by its shape once decoded`(body: Body) =
        runTest {
            failure(SHAPED, KEY) { server.enqueueError(body.status, body.text(Escaped.UNICODE.spell(SHAPED))) }
        }

    @ParameterizedTest(name = "an escape left as text: {0}")
    @EnumSource(Literal::class)
    fun `an escape JSON decoding leaves as text in a field hides no key`(literal: Literal) =
        runTest {
            // Text that is not JSON is decoded once only: its escapes are the only ones (model-layer §2).
            for (body in Body.entries - Body.NOT_JSON) {
                val error = failure(KEY, KEY) { server.enqueueError(body.status, body.text(literal.spell(KEY))) }
                assertTrue(error.message.orEmpty().contains(Redactor.WITHHELD), "$body: withheld whole")
            }
            failure(SHAPED, KEY) { server.enqueueError(400, Body.JSON_MESSAGE.text(literal.spell(SHAPED))) }
        }

    @ParameterizedTest(name = "an escape left as text in a stream error: {0}")
    @EnumSource(Literal::class)
    fun `an escape left as text in a stream's error chunk hides no key`(literal: Literal) =
        runTest {
            val error =
                failure(KEY, KEY) {
                    server.enqueueSse(Kit.sse("""{"error":{"message":"bad key ${literal.spell(KEY)}","code":"500"}}"""))
                }
            assertTrue(error.message.orEmpty().contains(Redactor.WITHHELD), "withheld whole")
        }

    @ParameterizedTest(name = "{0} body, the key as sent")
    @EnumSource(Body::class)
    fun `the request's key as sent is masked in place and the rest is kept`(body: Body) =
        runTest {
            val error = failure(KEY, KEY) { server.enqueueError(body.status, body.text(KEY)) }
            assertTrue(error.message.orEmpty().contains(body.shown), "$body: the diagnosis stays")
            val streamed =
                failure(KEY, KEY) {
                    server.enqueueSse(Kit.sse("""{"error":{"message":"bad key $KEY","code":"500"}}"""))
                }
            assertTrue(streamed.message.orEmpty().contains("bad key ***"), "a stream's error chunk")
        }

    private companion object {
        val BS = Char(92)

        // Assembled at run time so secret scanners do not take the placeholders for real keys.

        /** The request's key: `sk-` shaped, a slash in it. */
        val KEY = listOf("sk", "proj", "placeholder/r6s5-0001").joinToString("-")

        /** A key-shaped value the request did not send. */
        val SHAPED = listOf("sk", "proj", "placeholder-shape-0002").joinToString("-")

        const val MIN_PIECE = 8

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
