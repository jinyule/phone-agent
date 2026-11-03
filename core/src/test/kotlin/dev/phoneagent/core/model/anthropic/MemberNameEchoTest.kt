package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * `thinking`, `redacted_thinking` and `compaction` blocks are kept and sent back verbatim, members
 * this adapter does not know included: a member named by a request secret, at the top of the
 * block or nested in it, rejects the reply as its value would, streamed or in a whole JSON reply,
 * before anything of the block is shown. So does a delta member named by one.
 */
class MemberNameEchoTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    private suspend fun rejected(
        what: String,
        shown: List<Delta> = emptyList(),
    ) {
        val deltas = Deltas()
        val error =
            assertThrows<ModelError.BadResponse> {
                Kit.model(server, credentials = Keys(SECRET)).complete(Kit.request(), deltas)
            }
        assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message, what)
        assertTrue(CredentialEcho.discardsStreamed(error), "$what: the streamed output is not kept")
        assertTrue(SECRET !in error.stackTraceToString(), "$what: the error shows the key")
        assertEquals(shown, deltas.all, "$what: shown before the rejection")
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("kept")
    fun `a kept block with a member named by the key is refused before anything is shown`(
        type: String,
        transport: String,
        block: String,
    ) = runTest {
        if (transport == WHOLE) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(whole(block))
                    .build(),
            )
        } else {
            server.enqueueSse(stream(block))
        }
        rejected("$type, $transport")
    }

    @Test
    fun `a member name nested in a kept block is checked too`() =
        runTest {
            server.enqueueSse(stream("""{"type":"compaction","content":"摘要","extra":{"list":[{"$SECRET":1}]}}"""))
            rejected("nested")
        }

    @Test
    fun `a delta member named by the key is refused like its value would be`() =
        runTest {
            val thinking =
                Kit.sse(
                    MESSAGE_START,
                    start(THINKING),
                    "content_block_delta" to
                        """{"type":"content_block_delta","index":0,""" +
                        """"delta":{"type":"thinking_delta","thinking":"再想","$SECRET":"x"}}""",
                    STOP,
                    *END,
                )
            server.enqueueSse(thinking)
            rejected("delta", listOf(Delta.Reasoning("想一想")))
        }

    private companion object {
        /** A placeholder secret without a credential shape. */
        const val SECRET = "plain-member-name-key-0001"
        const val WHOLE = "whole JSON"

        const val THINKING = """{"type":"thinking","thinking":"想一想","signature":"c2lnbmF0dXJl"}"""

        /** Each kept block type with an extra member named by [SECRET]. */
        val BLOCKS =
            listOf(
                "thinking" to """{"type":"thinking","thinking":"想一想","signature":"c2lnbmF0dXJl","$SECRET":"x"}""",
                "redacted_thinking" to """{"type":"redacted_thinking","data":"ZGF0YQ==","$SECRET":"x"}""",
                "compaction" to """{"type":"compaction","content":"摘要","$SECRET":"x"}""",
            )

        @JvmStatic
        fun kept(): List<Arguments> =
            BLOCKS.flatMap { (type, block) ->
                listOf(Arguments.of(type, "SSE", block), Arguments.of(type, WHOLE, block))
            }

        val MESSAGE_START =
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""
        val STOP = "content_block_stop" to """{"type":"content_block_stop","index":0}"""
        val END =
            arrayOf(
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )

        fun start(block: String) =
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":$block}"""

        /** [block] as the only block of a streamed reply. */
        fun stream(block: String): String = Kit.sse(MESSAGE_START, start(block), STOP, *END)

        /** [block] as the only block of a whole JSON reply. */
        fun whole(block: String): String =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
                """"content":[$block],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
    }
}
