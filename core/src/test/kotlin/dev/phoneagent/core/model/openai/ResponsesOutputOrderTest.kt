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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The reply's parts are the response's output in `output_index` order (model-layer §3.2): items
 * finished on the stream and `response.completed.output` are two views of one list, merged by
 * index. The final output wins and a value wins over JSON `null`; the two views are compared only
 * on what means something (message content types and texts, call ids, names and arguments,
 * reasoning ids and encrypted content), and a contradiction there is a protocol violation.
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
                    done(0, call("call_a")) + completed(reasoning),
                    done(0, call("call_a")) + done(0, call("call_b")) + completed(),
                )
            for (stream in contradictions) {
                server.enqueueSse(stream)
                assertThrows<ModelError.BadResponse>(stream) { model().complete(Kit.request()) }
            }
        }

    private fun message(
        content: String?,
        id: String = "msg_1",
    ) = """{"type":"message","id":"$id","role":"assistant","content":$content}"""

    private val refusal = """[{"type":"refusal","refusal":"I can't help with that."}]"""

    private fun text(value: String) = """[{"type":"output_text","text":"$value","annotations":[]}]"""

    @Test
    fun `an empty streamed message does not erase a final refusal`() =
        runTest {
            server.enqueueSse(done(0, message("[]")) + completed(message(refusal)))
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(done(0, message("null")) + completed(message(refusal)))
            assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }
            server.enqueueSse(done(0, message(refusal)) + completed(message("null")))
            assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }
        }

    @Test
    fun `a value wins over null, whichever view has it`() =
        runTest {
            val nullArgs = """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"tap","arguments":null}"""
            val args = call("call_1", "{\"x\":1}", "fc_1")
            for ((streamed, final) in listOf(nullArgs to args, args to nullArgs)) {
                server.enqueueSse(done(0, streamed) + completed(final))
                assertEquals(
                    "{\"x\":1}",
                    model()
                        .complete(Kit.request())
                        .toolCalls
                        .single()
                        .argsJson,
                )
            }
            val hi = message(text("hi"))
            for ((streamed, final) in listOf(message("null") to hi, hi to message("null"))) {
                server.enqueueSse(done(0, streamed) + completed(final))
                assertEquals(listOf(Part.Text("hi")), model().complete(Kit.request()).parts)
            }
        }

    @Test
    fun `ids the adapter does not use may differ`() =
        runTest {
            server.enqueueSse(done(0, call("call_a")) + completed(call("call_a", id = "fc_other")))
            assertEquals(listOf("call_a"), model().complete(Kit.request()).toolCalls.map { it.id.value })
            server.enqueueSse(done(0, message(text("hi"))) + completed(message(text("hi"), id = "msg_2")))
            assertEquals(listOf(Part.Text("hi")), model().complete(Kit.request()).parts)
        }

    @Test
    fun `reasoning must agree on id and encrypted content, and keeps every other field`() =
        runTest {
            val streamed = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"}"""
            val final =
                """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"s"}],""" +
                    """"encrypted_content":null,"status":"completed"}"""
            server.enqueueSse(done(0, streamed) + completed(final))
            val part = model().complete(Kit.request()).parts.single() as Part.Reasoning
            val opaque = part.opaque as JsonObject
            assertEquals("s", part.summary, "the final summary")
            assertEquals(JsonPrimitive("enc"), opaque["encrypted_content"], "the streamed encrypted content")
            assertEquals(JsonPrimitive("completed"), opaque["status"])
            for (other in listOf(streamed.replace("rs_1", "rs_2"), streamed.replace("\"enc\"", "\"other\""))) {
                server.enqueueSse(done(0, streamed) + completed(other))
                assertThrows<ModelError.BadResponse>(other) { model().complete(Kit.request()) }
            }
        }

    @Test
    fun `messages must agree on content types and texts`() =
        runTest {
            for (other in listOf(text("bye"), refusal, "[]")) {
                server.enqueueSse(done(0, message(text("hi"))) + completed(message(other)))
                assertThrows<ModelError.BadResponse>(other) { model().complete(Kit.request()) }
            }
        }
}
