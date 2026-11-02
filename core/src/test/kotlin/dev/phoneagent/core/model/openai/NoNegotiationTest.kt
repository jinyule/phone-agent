package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Models never negotiate at run time (model-layer §3.1/§3.2): one `complete()` is one request
 * (a plan token refresh aside), a 400 is the caller's error with the provider's structured
 * `param`, and nothing a reply says changes what the same model sends next.
 */
class NoNegotiationTest {
    @StartStop
    private val server = Kit.server()

    private val chatReply =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )
    private val responsesReply =
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":" +
            "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}]}}\n\n"

    private fun chat(): ChatModel =
        Kit.adapters().unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    private fun responses(): ChatModel =
        Kit.adapters().unprobed(
            Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES),
            Kit.MODEL,
            Kit.capabilities(),
            Kit.blobs(),
        )

    @Test
    fun `an unrelated 400 that echoes the request is one request with its own classification`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"This model's maximum context length is 8192 tokens. Request: """ +
                    """{\"parallel_tool_calls\":false,\"stream_options\":{\"include_usage\":true}}"}}""",
            )
            server.enqueueSse(chatReply)
            assertThrows<ModelError.ContextOverflow> { chat().complete(Kit.request()) }
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a 400 naming an optional field fails once and leaves the next request unchanged`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Unrecognized request argument supplied: parallel_tool_calls",""" +
                    """"param":"parallel_tool_calls","code":"unsupported_parameter"}}""",
            )
            server.enqueueSse(chatReply)
            server.enqueueSse(chatReply)
            val model = chat()
            val error = assertThrows<ModelError.InvalidRequest> { model.complete(Kit.request()) }
            assertEquals("parallel_tool_calls", error.param)
            assertTrue(error.message!!.contains("parallel_tool_calls"), "the message names the field for the UI")
            val first = server.next().json()
            model.complete(Kit.request())
            assertEquals(first, server.next().json(), "the same model sends the same wire")
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `an encrypted_content 400 does not turn include off`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Invalid 'input[1].encrypted_content': cannot be decrypted.",""" +
                    """"param":"input[1].encrypted_content","code":"invalid_encrypted_content"}}""",
            )
            server.enqueueSse(responsesReply)
            server.enqueueSse(responsesReply)
            val model = responses()
            val error = assertThrows<ModelError.InvalidRequest> { model.complete(Kit.request()) }
            assertEquals("input[1].encrypted_content", error.param)
            assertEquals(1, server.requestCount)
            val first = server.next().json()
            model.complete(Kit.request())
            val second = server.next().json()
            assertTrue("include" in first && first == second, "include stays on and the wire is unchanged")
        }

    @Test
    fun `a 400 about include is the caller's error too`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Encrypted content is not supported with this model.",""" +
                    """"param":"include","code":"unsupported_parameter"}}""",
            )
            server.enqueueSse(responsesReply)
            assertEquals(
                "include",
                assertThrows<ModelError.InvalidRequest> { responses().complete(Kit.request()) }.param,
            )
            assertEquals(1, server.requestCount)
        }
}
