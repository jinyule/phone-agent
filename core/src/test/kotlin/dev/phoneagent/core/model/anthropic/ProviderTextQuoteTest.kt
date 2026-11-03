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
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Provider text in errors (model-layer §2). A message field read from JSON, a stream error
 * event's included, and a refusal's category are masked once decoded, and withheld
 * ([WITHHELD]) when their own text hides a key behind a further layer of escapes. JSON with no
 * message field to read (not an object, or an object without one) is checked string by string the
 * same way, then written back masked. Text that was not read as JSON (an error body that is not
 * JSON, a redirect target) is masked as written, or not quoted at all ([WITHHELD]) when a secret
 * hides behind its escapes. Each case runs with the key written JSON-escaped, with a `u` escape and
 * with `\/`, and as sent where the text need not be JSON.
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
    fun `an error body that is JSON but not an object is masked once decoded`(form: String) =
        runTest {
            server.enqueueError(BAD_GATEWAY, """["upstream rejected $form"]""")
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, """server error 502: ["upstream rejected ***"]""", form)
        }

    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `an error object without a message is masked once decoded`(form: String) =
        runTest {
            server.enqueueError(INTERNAL, """{"type":"error","error":{"type":"api_error","note":"$form"}}""")
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, "server error 500: $MASKED_OBJECT", form)
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

    /**
     * The event is not masked as written first: its strings are checked once decoded, and the event
     * written back holds the key in its standard escaping, which masking finds.
     */
    @ParameterizedTest
    @MethodSource("jsonForms")
    fun `a stream error event without a message is masked once decoded`(form: String) =
        runTest {
            server.enqueueSse(Kit.sse("error" to """{"type":"error","error":{"type":"api_error","note":"$form"}}"""))
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertQuoted(error, "server error 500: $MASKED_OBJECT", form)
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
     * whether the request sent that key or the key is only shaped like one. A key only shaped like
     * one is made of the shape's characters, as providers' keys are: masked in place, a shape ends at
     * any other character (model-layer §2, known gaps), so only the request's own key has a slash.
     */
    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("shapedBodies")
    fun `a shaped key with an escape inside leaves no piece in any error body`(
        body: String,
        writing: String,
        sent: String,
    ) = runTest {
        val shaped = if (sent == SENT) SHAPED else UNSENT
        val written =
            if (writing == SLASH) {
                shaped.replace("/", "$BS/")
            } else {
                shaped.replaceRange(U_AT, U_AT + 1, uEscaped(shaped[U_AT]))
            }
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
        val pieces = shaped.windowed(PIECE).filter { it in printed }
        assertTrue(pieces.isEmpty(), "$body, $writing, $sent: ${pieces.size} pieces of the key in the error: $printed")
    }

    /**
     * A key written with one escape more than the JSON takes, in JSON with no message field to read
     * (an error object without one, also as a member name, JSON that is not an object, a stream
     * error event without one): each string in it is checked once decoded, as a message field is
     * (fix-list r7 S1). Checking the
     * whole written back would miss it: writing back escapes each backslash again, and one decoding
     * only takes that layer off.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("doubleEscaped")
    fun `a key behind a second escape in JSON without a message is withheld`(
        body: String,
        key: String,
    ) = runTest {
        val written = BS + BS + "u00" + key[0].code.toString(HEX_RADIX) + key.drop(1)
        val expected =
            when (body) {
                NO_MESSAGE -> {
                    val error = """{"type":"error","error":{"type":"$AUTH","detail":"key $written"}}"""
                    server.enqueueError(UNAUTHORIZED, error)
                    "authentication failed: $WITHHELD"
                }

                MEMBER_NAME -> {
                    val error = """{"type":"error","error":{"type":"$AUTH","key $written":true}}"""
                    server.enqueueError(UNAUTHORIZED, error)
                    "authentication failed: $WITHHELD"
                }

                NOT_OBJECT -> {
                    server.enqueueError(BAD_GATEWAY, """["key $written"]""")
                    "server error 502: $WITHHELD"
                }

                else -> {
                    server.enqueueSse(
                        Kit.sse("error" to """{"type":"error","error":{"type":"api_error","detail":"key $written"}}"""),
                    )
                    "server error 500: $WITHHELD"
                }
            }
        val error = assertThrows<ModelError> { Kit.model(server, credentials = Keys(key)).complete(Kit.request()) }
        val printed = error.stackTraceToString()
        val pieces = key.windowed(PIECE).filter { it in printed }
        assertTrue(pieces.isEmpty(), "$body, $key: ${pieces.size} pieces of the key in the error")
        assertEquals(expected, error.message)
    }

    /**
     * A header value is raw provider text, quoted as an error body is (fix-list r7 S2): the key as
     * sent or in its standard escaping is masked in place and the rest of the target kept, also
     * when the target has another escape in it; a key written with other escapes withholds it.
     */
    @ParameterizedTest
    @MethodSource("forms")
    fun `a redirect target that holds the key is masked, or withheld when other escapes write it`(form: String) =
        runTest {
            server.enqueueError(TEMPORARY_REDIRECT, "", "Location" to "https://proxy.example.com/v1?note=$form")
            val error = assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            val target = if (form in ESCAPED) WITHHELD else "https://proxy.example.com/v1?note=***"
            val detail = "HTTP 307 redirect to $target: check the base URL and use the final address"
            assertQuoted(error, "invalid request: $detail", form)
        }

    @ParameterizedTest
    @MethodSource("exactForms")
    fun `a redirect target with another escape still masks the key in place`(form: String) =
        runTest {
            val other = BS + "u0041"
            server.enqueueError(TEMPORARY_REDIRECT, "", "Location" to "https://proxy.example.com/v1?key=$form&x=$other")
            val error = assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            val target = "https://proxy.example.com/v1?key=***&x=$other"
            val detail = "HTTP 307 redirect to $target: check the base URL and use the final address"
            assertQuoted(error, "invalid request: $detail", form)
        }

    /**
     * Gateway explanations that name the Bearer scheme or show another key's shape keep their words
     * (fix-list r6 S2): the Bearer rule masks the token after the word and never withholds, and text
     * with nothing to decode is masked in place.
     */
    @Test
    fun `a gateway explanation with a Bearer word or another key's shape keeps its words`() =
        runTest {
            // Assembled at run time so secret scanners do not take the placeholder for a real key.
            val upstream = "s" + "k-proj-" + "abcdefghijklmnop1234"
            val missing = "No API key. Use Bearer auth (i.e. Authorization: Bearer YOUR_KEY), or log in."
            val rewritten = """{"error":{"type":"$AUTH","detail":"send $BS"Bearer YOUR_KEY$BS""}}"""
            val masked = """{"error":{"type":"$AUTH","detail":"send $BS"Bearer ***""}}"""
            val cases =
                listOf(
                    error(AUTH, "Invalid bearer token") to "authentication failed: Invalid bearer ***",
                    error("invalid_request_error", missing) to
                        "invalid request: No API key. Use Bearer *** (i.e. Authorization: Bearer ***, or log in.",
                    error(AUTH, "upstream key $upstream rejected") to
                        "authentication failed: upstream key *** rejected",
                    rewritten to "authentication failed: $masked",
                )
            val shown =
                cases.map { (body) ->
                    server.enqueueError(UNAUTHORIZED, body)
                    assertThrows<ModelError> { model().complete(Kit.request()) }.message
                }
            assertAll(cases.zip(shown).map { (case, message) -> { assertEquals(case.second, message, case.first) } })
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
        const val AUTH = "authentication_error"
        const val MEMBER_NAME = "a member name in JSON without a message"
        const val NOT_OBJECT = "JSON that is not an object"
        const val STREAM_NO_MESSAGE = "stream error without a message"

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

        /** A key of the shape's characters only, which this request did not send. */
        const val UNSENT = "s" + "k-proj-" + "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Where the `u` escape goes (a letter in the middle). */
        const val U_AT = 16
        const val HEX_RADIX = 16

        /** [letter] written as a `u` escape. */
        fun uEscaped(letter: Char): String = BS + "u00" + letter.code.toString(HEX_RADIX)

        /** A gateway's key, of no credential shape: only its exact forms can find it. */
        const val GATEWAY = "gwkey-" + "abcdefghijklmnopqrstuvwxyz0123456789"

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

        /** [SECRET] as sent and in its standard escaping: the forms masked before anything is decoded. */
        @JvmStatic
        fun exactForms(): List<String> = listOf(SECRET, JSON_FORMS.first())

        @JvmStatic
        fun shapedBodies(): List<Arguments> =
            listOf(JSON_MESSAGE, NOT_JSON, NO_MESSAGE).flatMap { body ->
                listOf(SLASH, U_LETTER).map { writing -> Arguments.of(body, writing, SENT) } +
                    Arguments.of(body, U_LETTER, SHAPE_ONLY)
            }

        @JvmStatic
        fun doubleEscaped(): List<Arguments> =
            listOf(NO_MESSAGE, MEMBER_NAME, NOT_OBJECT, STREAM_NO_MESSAGE).flatMap { body ->
                listOf(SHAPED, GATEWAY).map { key -> Arguments.of(body, key) }
            }

        @JvmStatic
        fun layered(): List<Arguments> =
            listOf(HTTP_MESSAGE, HTTP_STRING, STREAM_MESSAGE, REFUSAL).flatMap { where ->
                JSON_FORMS.map { form -> Arguments.of(where, form) }
            }

        /** An Anthropic error body of [type] whose message is [message]. */
        fun error(
            type: String,
            message: String,
        ): String = """{"type":"error","error":{"type":"$type","message":${JsonPrimitive(message)}}}"""

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
