package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Protocol errors give structure, never a value the server sent (model-layer §2): which block,
 * which field, which known name, or the kind and length of what came. The values here are no
 * secret, so masking would leave them; they must not be there at all.
 */
class DiagnosticQuoteTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    private suspend fun refused(vararg events: Pair<String, String>): String {
        server.enqueueSse(Kit.sse(*events))
        val error = assertThrows<ModelError.BadResponse> { Kit.model(server).complete(Kit.request()) }
        return error.stackTraceToString()
    }

    private fun assertWithout(
        value: String,
        printed: String,
        expected: String,
    ) {
        assertFalse(value in printed, "the error quotes $value: $printed")
        assertEquals("bad response: $expected", printed.lineSequence().first().substringAfter(": "), printed)
    }

    @Test
    fun `an unknown delta type is not quoted`() =
        runTest {
            val printed =
                refused(
                    START,
                    block(0, """{"type":"text","text":""}"""),
                    "content_block_delta" to
                        """{"type":"content_block_delta","index":0,"delta":{"type":"$MARKER","text":"x"}}""",
                )
            assertWithout(MARKER, printed, "a delta of an unknown type (a string of 16 characters) for a text block")
        }

    @Test
    fun `an unknown block type is not quoted`() =
        runTest {
            val printed = refused(START, block(0, """{"type":"$MARKER"}"""), stop(0), *END)
            assertWithout(MARKER, printed, "block 0 has an unsupported type: a string of 16 characters")
        }

    @Test
    fun `a repeated tool id is not quoted`() =
        runTest {
            val call = """{"type":"tool_use","id":"toolu_$MARKER","name":"get_weather","input":{}}"""
            val printed = refused(START, block(0, call), stop(0), block(1, call))
            assertWithout(MARKER, printed, "block 1 repeats the tool id of an earlier block")
        }

    @Test
    fun `a usage value that is not a count is not quoted`() =
        runTest {
            val printed = refused(START, *delta("""{"output_tokens":"$MARKER"}"""))
            assertWithout(MARKER, printed, "usage output_tokens is not a count: a string of 16 characters")
        }

    @Test
    fun `a usage count out of range is not quoted`() =
        runTest {
            val printed = refused(START, *delta("""{"output_tokens":$TOO_LARGE}"""))
            assertWithout(TOO_LARGE, printed, "usage output_tokens is out of range")
        }

    @Test
    fun `an input token total out of range is not quoted`() =
        runTest {
            val usage = """{"input_tokens":$INT_MAX,"cache_read_input_tokens":$INT_MAX}"""
            val printed = refused(START, *delta(usage))
            assertWithout((2L * INT_MAX.toLong()).toString(), printed, "the input token total is out of range")
        }

    /** A neutral type's own check failing: the error and its cause name the exception's class only. */
    private fun assertInvariant(printed: String) {
        assertEquals(
            "bad response: reply violates a model invariant (IllegalArgumentException)",
            printed.lineSequence().first().substringAfter(": "),
            printed,
        )
        val causes = printed.lineSequence().filter { it.startsWith("Caused by: ") }.toList()
        assertEquals(listOf("Caused by: java.lang.IllegalArgumentException"), causes, printed)
    }

    @Test
    fun `a tool id that breaks the id invariant names the failure's class only`() =
        runTest {
            val call = """{"type":"tool_use","id":"toolu_$MARKER$CONTROL","name":"get_weather","input":{}}"""
            val printed = refused(START, block(0, call), stop(0), *END)
            assertWithout(MARKER, printed, "reply violates a model invariant (IllegalArgumentException)")
            assertInvariant(printed)
        }

    @Test
    fun `usage that breaks the usage invariant names the failure's class only`() =
        runTest {
            val usage = """{"output_tokens":1,"output_tokens_details":{"thinking_tokens":$TOO_LARGE_THINKING}}"""
            val printed = refused(START, *delta(usage))
            assertInvariant(printed)
        }

    private companion object {
        /** A value no request secret matches: masking leaves it, so only not quoting it keeps it out. */
        const val MARKER = "zz-marker-value1"
        const val TOO_LARGE = "98765432109"
        const val INT_MAX = "2147483647"
        const val TOO_LARGE_THINKING = 5

        /** A control character, written as a JSON escape: `u` plus four hex digits, built here. */
        val CONTROL = Char(BACKSLASH) + "u0001"
        const val BACKSLASH = 92

        val START =
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""
        val END =
            arrayOf(
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )

        fun block(
            index: Int,
            content: String,
        ) = "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":$content}"""

        fun stop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

        /** A text block, then a `message_delta` with [usage], then the end. */
        fun delta(usage: String) =
            arrayOf(
                block(0, """{"type":"text","text":"ok"}"""),
                stop(0),
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":$usage}""",
                "message_stop" to """{"type":"message_stop"}""",
            )
    }
}
