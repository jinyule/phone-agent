package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The reply's parts are the response's output in `output_index` order (model-layer §3.2): items
 * finished on the stream and `response.completed.output` are two views of one list, merged by
 * index, and a contradiction between them is a protocol violation.
 */
class ResponsesOutputOrderTest {
    @StartStop
    private val server = MockWebServer()

    private fun model(): ChatModel =
        Kit.adapters().model(
            Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES),
            Kit.MODEL,
            Kit.capabilities(),
            Kit.blobs(),
        )

    private fun call(
        callId: String,
        args: String = "{}",
        id: String = "fc_$callId",
    ) = """{"type":"function_call","id":"$id","call_id":"$callId","name":"tap","arguments":${JsonPrimitive(args)}}"""

    private val reasoning = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"}"""

    private fun done(
        index: Int,
        item: String,
    ) = "data: {\"type\":\"response.output_item.done\",\"output_index\":$index,\"item\":$item}\n\n"

    private fun completed(vararg output: String) =
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"," +
            "\"output\":[${output.joinToString(",")}]}}\n\n"

    @Test
    fun `items the stream did not finish come from the final output`() =
        runTest {
            server.enqueueSse(done(0, reasoning) + completed(reasoning, call("call_1")))
            val reply = model().complete(Kit.request())
            assertEquals(listOf(ToolCallId("call_1")), reply.toolCalls.map { it.id })
            assertEquals(listOf(true, false), reply.parts.map { it is Part.Reasoning }, "reasoning, then the call")
            assertEquals(StopReason.TOOL_USE, reply.stopReason)
        }

    @Test
    fun `items are ordered by output_index, not by arrival`() =
        runTest {
            server.enqueueSse(done(1, call("call_b")) + done(0, call("call_a")) + completed())
            assertEquals(listOf("call_a", "call_b"), model().complete(Kit.request()).toolCalls.map { it.id.value })
            val both = completed(call("call_a"), call("call_b"))
            server.enqueueSse(done(1, call("call_b")) + done(0, call("call_a")) + both)
            assertEquals(listOf("call_a", "call_b"), model().complete(Kit.request()).toolCalls.map { it.id.value })
        }

    @Test
    fun `a final output that contradicts the streamed items is a bad response`() =
        runTest {
            val contradictions =
                listOf(
                    done(0, call("call_a", "{\"x\":1}")) + completed(call("call_a", "{\"x\":2}")),
                    done(0, call("call_a")) + completed(call("call_a", id = "fc_other")),
                    done(0, call("call_a")) + completed(reasoning),
                    done(0, call("call_a")) + done(0, call("call_b")) + completed(),
                )
            for (stream in contradictions) {
                server.enqueueSse(stream)
                assertThrows<ModelError.BadResponse>(stream) { model().complete(Kit.request()) }
            }
        }
}
