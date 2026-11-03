package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Provider text in errors (model-layer §2). A message field read from JSON, a stream error
 * event's included, and a refusal's category are masked once decoded, and withheld
 * ([WITHHELD]) when their own text hides a key behind a further layer of escapes. Text that was not read as a
 * field (an error body that is not JSON or not a JSON object, the whole of one without a message,
 * a redirect target) is masked as written, or not quoted at all ([WITHHELD]) when a
 * secret hides behind its escapes. Each case runs with the key written JSON-escaped, with a `u`
 * escape and with `\/`, and as sent where the text need not be JSON.
 */
class ProviderTextQuoteTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    private fun model() = Kit.model(server, credentials = Keys(SECRET))

    private fun assertQuoted(
        error: ModelError,
        expected: String,
        form: String,
    ) {
        assertEquals(expected, error.message, form)
        val printed = error.stackTraceToString()
        FORMS.forEach { leak -> assertFalse(leak in printed, "$form: the error shows the key: $printed") }
    }

    @ParameterizedTest
    @MethodSource("forms")
    fun `an error page that is not JSON is masked, or withheld when an escape hides the key`(form: String) =
        runTest {
            server.enqueueError(BAD_GATEWAY, "<html>upstream rejected $form</html>")
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            val shown = if (form in ESCAPED) WITHHELD else "<html>upstream rejected ***</html>"
            assertQuoted(error, "server error 502: $shown", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `an error body that is JSON but not an object is quoted the same way`(form: String) =
        runTest {
            server.enqueueError(BAD_GATEWAY, """["upstream rejected $form"]""")
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            val shown = if (form in ESCAPED) WITHHELD else """["upstream rejected ***"]"""
            assertQuoted(error, "server error 502: $shown", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `an error object without a message is quoted whole the same way`(form: String) =
        runTest {
            server.enqueueError(INTERNAL, """{"type":"error","error":{"type":"api_error","note":"$form"}}""")
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            val shown = if (form in ESCAPED) WITHHELD else MASKED_OBJECT
            assertQuoted(error, "server error 500: $shown", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `a message field is masked once decoded`(form: String) =
        runTest {
            server.enqueueError(
                INTERNAL,
                """{"type":"error","error":{"type":"api_error","message":"rejected $form"}}""",
            )
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, "server error 500: rejected ***", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `a stream error event's message is masked once decoded`(form: String) =
        runTest {
            val data = """{"type":"error","error":{"type":"api_error","message":"rejected $form"}}"""
            server.enqueueSse(Kit.sse("error" to data))
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, "server error 500: rejected ***", form)
        }

    /** The event was parsed, not masked as written first: its re-encoding holds the key JSON-escaped. */
    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `a stream error event without a message is withheld`(form: String) =
        runTest {
            server.enqueueSse(Kit.sse("error" to """{"type":"error","error":{"type":"api_error","note":"$form"}}"""))
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, "server error 500: $WITHHELD", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `a refusal category is masked once decoded`(form: String) =
        runTest {
            server.enqueueSse(refusal("\"$form\""))
            val error = assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }
            assertQuoted(error, "model refused (***)", form)
        }

    /**
     * A field whose own text writes the key with escapes (a further layer the JSON decoding leaves):
     * masked as written, then withheld when its lenient decoding shows a key the mask missed.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("layered")
    fun `a decoded field whose text hides the key behind its own escapes is withheld`(
        where: String,
        form: String,
    ) = runTest {
        val text = JsonPrimitive("rejected $form").toString()
        when (where) {
            HTTP_MESSAGE -> {
                server.enqueueError(INTERNAL, """{"type":"error","error":{"type":"api_error","message":$text}}""")
            }

            HTTP_STRING -> {
                server.enqueueError(INTERNAL, """{"error":$text}""")
            }

            STREAM_MESSAGE -> {
                server.enqueueSse(
                    Kit.sse("error" to """{"type":"error","error":{"type":"api_error","message":$text}}"""),
                )
            }

            else -> {
                server.enqueueSse(refusal(text))
            }
        }
        val error = assertThrows<ModelError> { model().complete(Kit.request()) }
        val shown = if (form in ESCAPED) WITHHELD else "rejected ***"
        val expected = if (where == REFUSAL) "model refused ($shown)" else "server error 500: $shown"
        assertQuoted(error, expected, "$where, $form")
    }

    @Test
    fun `a credential shape behind a further escape in a message field is withheld`() =
        runTest {
            // Assembled at run time so secret scanners do not take the placeholder for a real key.
            val shaped = BS + "u0073k-proj-" + "abcdefghijklmnop"
            val text = JsonPrimitive("Invalid API key: $shaped").toString()
            server.enqueueError(
                UNAUTHORIZED,
                """{"type":"error","error":{"type":"authentication_error","message":$text}}""",
            )
            val error = assertThrows<ModelError.Auth> { model().complete(Kit.request()) }
            assertEquals("authentication failed: $WITHHELD", error.message)
        }

    /**
     * A key with a provider's shape, written with an escape inside it: masking its shape before the
     * body is decoded would cover it only up to the escape and leave the rest readable. The body is
     * parsed first and masked once decoded, so no piece of the key is left, whichever body it is,
     * whether the request sent that key or the key is only shaped like one.
     */
    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("shapedBodies")
    fun `a shaped key with an escape inside leaves no piece in any error body`(
        body: String,
        writing: String,
        sent: String,
    ) = runTest {
        val written =
            if (writing == SLASH) SHAPED.replace("/", "$BS/") else SHAPED.replaceRange(U_AT, U_AT + 1, U_ESCAPED)
        val text =
            when (body) {
                JSON_MESSAGE -> """{"type":"error","error":{"type":"authentication_error","message":"$written"}}"""
                NOT_JSON -> "<html>bad key $written</html>"
                else -> """{"type":"error","error":{"type":"authentication_error","note":"$written"}}"""
            }
        server.enqueueError(UNAUTHORIZED, text)
        val error =
            assertThrows<ModelError.Auth> {
                val key = if (sent == SENT) SHAPED else SECRET
                Kit.model(server, credentials = Keys(key)).complete(Kit.request())
            }
        val printed = error.stackTraceToString()
        val pieces = SHAPED.windowed(PIECE).filter { it in printed }
        assertTrue(pieces.isEmpty(), "$body, $writing, $sent: ${pieces.size} pieces of the key in the error: $printed")
    }

    /** A header value is raw provider text: a key in it, written any way, withholds the target. */
    @ParameterizedTest
    @MethodSource("forms")
    fun `a redirect target that holds the key is withheld`(form: String) =
        runTest {
            server.enqueueError(TEMPORARY_REDIRECT, "", "Location" to "https://proxy.example.com/v1?note=$form")
            val error = assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            val detail = "HTTP 307 redirect to $WITHHELD: check the base URL and use the final address"
            assertQuoted(error, "invalid request: $detail", form)
        }

    private companion object {
        const val HTTP_MESSAGE = "HTTP message"
        const val HTTP_STRING = "HTTP error string"
        const val STREAM_MESSAGE = "stream error message"
        const val REFUSAL = "refusal category"
        const val JSON_MESSAGE = "JSON message"
        const val NOT_JSON = "not JSON"
        const val NO_MESSAGE = "JSON without a message"
        const val SLASH = "slash escaped"
        const val U_LETTER = "a letter u-escaped"
        const val SENT = "the request's key"
        const val SHAPE_ONLY = "only its shape"

        const val UNAUTHORIZED = 401
        const val BAD_GATEWAY = 502
        const val INTERNAL = 500
        const val TEMPORARY_REDIRECT = 307

        /** The fixed text for withheld provider text: the OpenAI adapters' (WP03), so both read alike. */
        const val WITHHELD = "(not shown: it holds a credential)"

        /** The error object without a message, masked and written back. */
        const val MASKED_OBJECT = """{"type":"error","error":{"type":"api_error","note":"***"}}"""

        /** Backslash, built here: tool inputs decode escapes written out. */
        val BS = Char(BACKSLASH).toString()
        const val BACKSLASH = 92

        /** The shortest piece of a key that counts as part of it. */
        const val PIECE = 8

        /**
         * A key shaped like a provider's, with a slash in it so that `\/` can write it. Assembled at
         * run time so secret scanners do not take the placeholder for a real key.
         */
        const val SHAPED = "s" + "k-ant-api03-" + "abcdefghij/klmnopqrstuvwxyz0123456789"

        /** Where the `u` escape goes (a letter in the middle), and the escape itself. */
        const val U_AT = 16
        val U_ESCAPED = BS + "u00" + SHAPED[U_AT].code.toString(HEX_RADIX)
        const val HEX_RADIX = 16

        /** A key with a quote and a slash, so that each escaping writes it differently. */
        const val SECRET = "pa\"ss/word-0001"

        /** [SECRET] JSON-escaped, with a `u` escape for its first letter, and with `\/`. */
        val JSON_FORMS =
            listOf(
                "pa$BS\"ss/word-0001",
                "${BS}u0070a$BS\"ss/word-0001",
                "pa$BS\"ss$BS/word-0001",
            )

        /** The forms masking as written misses. */
        val ESCAPED = JSON_FORMS.drop(1)

        val FORMS = listOf(SECRET) + JSON_FORMS

        @JvmStatic
        fun forms(): List<String> = FORMS

        @JvmStatic
        fun jsonForms(): List<String> = JSON_FORMS

        @JvmStatic
        fun shapedBodies(): List<Arguments> =
            listOf(JSON_MESSAGE, NOT_JSON, NO_MESSAGE).flatMap { body ->
                listOf(SLASH, U_LETTER).flatMap { writing ->
                    listOf(SENT, SHAPE_ONLY).map { sent -> Arguments.of(body, writing, sent) }
                }
            }

        @JvmStatic
        fun layered(): List<Arguments> =
            listOf(HTTP_MESSAGE, HTTP_STRING, STREAM_MESSAGE, REFUSAL).flatMap { where ->
                JSON_FORMS.map { form -> Arguments.of(where, form) }
            }

        /** A refusal whose `stop_details.category` is the JSON value [category]. */
        fun refusal(category: String): String =
            Kit.sse(
                "message_start" to
                    """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}""",
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"refusal",""" +
                    """"stop_details":{"type":"refusal","category":$category}}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )
    }
}
