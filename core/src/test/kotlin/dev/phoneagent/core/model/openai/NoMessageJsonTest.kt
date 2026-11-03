package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * JSON with no message field to read, written back whole (model-layer §2, fix-list r8 B1): JSON
 * that is not an error object, an error object without a message field (the string a member name;
 * a value under `detail` is read as the message). Each string is masked while it is still decoded
 * text, where a newline or a tab before a credential shape is a word boundary: written back, the
 * control character is an escape that ends in a letter, and the shape after it would lose the
 * boundary it starts with. The rest of the JSON is shown, the shape masked in place; the
 * credentials are placeholders this request did not send. The whole written back is masked once
 * more, for what no string holds: the request's key written as a number.
 */
class NoMessageJsonTest {
    @StartStop
    private val server = Kit.server()

    private fun model(key: String = Kit.FAKE_KEY): ChatModel =
        Kit.adapters(Keys(key)).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    /** JSON with no message field holding a string the test gives, answered with [status]. */
    enum class Body(
        val status: Int,
        val json: (String) -> String,
    ) {
        /** An array whose first element is not an error object. */
        NOT_OBJECT(502, { s -> """["$s"]""" }),
        MEMBER_NAME(500, { s -> """{"error":{"$s":"v"}}""" }),
    }

    /** A control character, by the letter of its JSON escape. */
    enum class Control(
        val letter: String,
    ) {
        NEWLINE("n"),
        TAB("t"),
    }

    /** A [credential] the request did not send, as the provider [written] it and as it is [masked]. */
    enum class Shape(
        val credential: String,
        val written: String,
        val masked: String,
    ) {
        SK_KEY(UPSTREAM_KEY, UPSTREAM_KEY, "***"),
        JWT(UPSTREAM_JWT, UPSTREAM_JWT, "***"),
        BEARER(UPSTREAM_TOKEN, "Bearer $UPSTREAM_TOKEN", "Bearer ***"),
    }

    /** The error of a call answered with [body] holding [text]. */
    private suspend fun failure(
        body: Body,
        text: String,
    ): ModelError {
        server.enqueueError(body.status, body.json(text))
        return assertThrows<ModelError> { model().send(Kit.request()) }
    }

    @ParameterizedTest(name = "not shown: {0}, {1}, {2}")
    @MethodSource("cases")
    fun `a credential shape after a control character is not shown`(
        body: Body,
        control: Control,
        shape: Shape,
    ) = runTest {
        val error = failure(body, PREFIX + BS + control.letter + shape.written)
        val printed = "${error.message}\n${error.stackTraceToString()}"
        val shown = shape.credential in printed || shape.credential in JsonEscapes.lenientDecode(printed)
        assertFalse(shown, "$body, $control, $shape: the error shows the credential (not printed here)")
    }

    @ParameterizedTest(name = "masked in place: {0}, {1}, {2}")
    @MethodSource("cases")
    fun `the rest of the JSON is shown with the shape masked in place`(
        body: Body,
        control: Control,
        shape: Shape,
    ) = runTest {
        val escape = BS + control.letter
        val error = failure(body, PREFIX + escape + shape.written)
        assertEquals("server error ${body.status}: " + body.json(PREFIX + escape + shape.masked), error.message)
    }

    /** A stream's error chunk is not masked before it is parsed: only the pass over the whole written back finds it. */
    @Test
    fun `the request's key written as a number is masked in the whole written back`() =
        runTest {
            server.enqueueSse(Kit.sse("""{"error":{"code":$NUMBER_KEY}}"""))
            val error = assertThrows<ModelError> { model(NUMBER_KEY).send(Kit.request()) }
            assertFalse(NUMBER_KEY in error.stackTraceToString(), "the error shows the key (not printed here)")
            assertEquals("""server error 500: {"error":{"code":***}}""", error.message)
        }

    private companion object {
        /** A key of digits only, which a provider may write as a JSON number. */
        const val NUMBER_KEY = "73019428"

        /** Backslash, built here: tool inputs decode escapes written out. */
        val BS = Char(BACKSLASH).toString()
        const val BACKSLASH = 92

        /** What the provider says before the credential. */
        const val PREFIX = "upstream keys:"

        // Assembled at run time so secret scanners do not take the placeholders for real credentials.

        /** An `sk-` key of the shape's characters only. */
        const val UPSTREAM_KEY = "s" + "k-proj-" + "UpstreamPlaceholder0123456789"

        /** A JWT: `{"alg":"none"}`, `{"sub":"placeholder"}`, `signature-placeholder`. */
        const val UPSTREAM_JWT =
            "ey" + "JhbGciOiJub25lIn0" + ".eyJzdWIiOiJwbGFjZWhvbGRlciJ9" + ".c2lnbmF0dXJlLXBsYWNlaG9sZGVy"

        /** The token after `Bearer`, of no credential shape. */
        const val UPSTREAM_TOKEN = "gw-upstream-token-" + "placeholder-0123456789"

        @JvmStatic
        fun cases(): List<Arguments> =
            Body.entries.flatMap { body ->
                Control.entries.flatMap { control -> Shape.entries.map { shape -> Arguments.of(body, control, shape) } }
            }
    }
}
