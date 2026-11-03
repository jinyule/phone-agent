package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Provider text in errors (model-layer §2). A message field read from JSON, a stream error
 * event's included, and a refusal's category are masked once decoded. Text that was not read as a
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
            val stop = """{"type":"refusal","category":"$form"}"""
            server.enqueueSse(
                Kit.sse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}""",
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":$stop}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
            )
            val error = assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }
            assertQuoted(error, "model refused (***)", form)
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
    }
}
