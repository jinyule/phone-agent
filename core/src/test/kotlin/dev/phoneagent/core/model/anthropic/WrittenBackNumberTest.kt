package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * A number in a block the reply keeps verbatim (a thinking start here) is checked as written and as
 * kotlinx.serialization writes it back into the session log and the requests rebuilt from it:
 * `1.23456e5` becomes `123456.0`, so a key `123456` fails the reply. A number no encoder can write
 * back (`1e400`) fails it too, in a block or in the metadata: no log could record the reply.
 */
class WrittenBackNumberTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    private fun enqueue(
        transport: String,
        thinking: String,
        metadata: String = "",
    ) {
        if (transport == WHOLE) {
            val body =
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
                    """"content":[$thinking,{"type":"text","text":"ok"}],"stop_reason":"end_turn"$metadata}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build(),
            )
        } else {
            server.enqueueSse(
                Kit.sse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5",""" +
                        """"content":[]$metadata}}""",
                    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":$thinking}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"ok"}}""",
                    "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                ),
            )
        }
    }

    private fun thinking(budget: String) =
        """{"type":"thinking","thinking":"hm","signature":"sig-1","budget":$budget}"""

    @ParameterizedTest
    @ValueSource(strings = [SSE, WHOLE])
    fun `a thinking start whose number is the key once written back fails the reply`(transport: String) =
        runTest {
            enqueue(transport, thinking("1.23456e5"))
            val error =
                assertThrows<ModelError.BadResponse> {
                    Kit.model(server, credentials = Keys(SECRET)).complete(Kit.request())
                }
            assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message)
            assertTrue(error.discardsStreamed)
        }

    @ParameterizedTest
    @ValueSource(strings = [SSE, WHOLE])
    fun `a number no encoder can write back fails the reply, in a block or in the metadata`(transport: String) =
        runTest {
            enqueue(transport, thinking(UNWRITABLE))
            val inBlock = assertThrows<ModelError.BadResponse> { Kit.model(server).complete(Kit.request()) }
            assertEquals("bad response: $UNWRITABLE_DETAIL", inBlock.message)
            enqueue(transport, thinking("1"), metadata = ""","context_management":{"cleared":$UNWRITABLE}""")
            val inMetadata = assertThrows<ModelError.BadResponse> { Kit.model(server).complete(Kit.request()) }
            assertEquals("bad response: $UNWRITABLE_DETAIL", inMetadata.message)
        }

    private companion object {
        const val SSE = "SSE"
        const val WHOLE = "whole JSON"
        const val SECRET = "123456"

        /** A number beyond the double range: kotlinx.serialization refuses to write it back. */
        const val UNWRITABLE = "1e400"
        const val UNWRITABLE_DETAIL = "the reply holds a number no JSON encoder can write back"
    }
}
