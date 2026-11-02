package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * A Responses reply ends in a valid terminal state (model-layer §3.2): `response.completed`, or
 * `response.incomplete` with a classified reason; never a missing or unfinished response object.
 * Incomplete replies never look like a finished turn or a tool step, and on the ChatGPT plan only
 * `response.completed` succeeds.
 */
class ResponsesTerminalTest {
    @StartStop
    private val server = Kit.server()

    private fun model(credential: Credential = Kit.KEY): ChatModel =
        Kit.adapters().unprobed(
            Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES, credential = credential),
            Kit.MODEL,
            Kit.capabilities(),
            Kit.blobs(),
        )

    private val call = """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"tap","arguments":"{}"}"""
    private val text = """{"type":"message","content":[{"type":"output_text","text":"Hi"}]}"""

    private fun json(body: String) {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )
    }

    private fun incomplete(
        reason: String?,
        output: String,
    ): String {
        val details = reason?.let { ""","incomplete_details":{"reason":"$it"}""" }.orEmpty()
        return "data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"$details," +
            "\"output\":[$output]}}\n\n"
    }

    @ParameterizedTest
    @ValueSource(strings = ["in_progress", "queued", "failed", "cancelled", ""])
    fun `a whole JSON reply must be completed or incomplete`(status: String) =
        runTest {
            val field = if (status.isEmpty()) "" else """"status":"$status","""
            json("""{"id":"r",$field"output":[$text]}""")
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        }

    @Test
    fun `whole JSON replies in a terminal state are read`() =
        runTest {
            json("""{"id":"r","status":"completed","output":[$text]}""")
            assertEquals(StopReason.END_TURN, model().complete(Kit.request()).stopReason)
            json("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[$text]}""")
            assertEquals(StopReason.MAX_TOKENS, model().complete(Kit.request()).stopReason)
        }

    @ParameterizedTest
    @ValueSource(strings = ["response.completed", "response.incomplete"])
    fun `a terminal event without its response object is a bad response`(type: String) =
        runTest {
            server.enqueueSse(
                "data: {\"type\":\"response.output_item.done\",\"item\":$text}\n\ndata: {\"type\":\"$type\"}\n\n",
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse("event: $type\ndata: {}\n\n")
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        }

    @ParameterizedTest(name = "{0} with calls={1}")
    @CsvSource(
        "max_output_tokens, true, MAX_TOKENS",
        "max_output_tokens, false, MAX_TOKENS",
        "something_new, true, OTHER",
        "something_new, false, OTHER",
        ", true, OTHER",
        ", false, OTHER",
    )
    fun `incomplete replies are classified by reason, never as a tool step or a finished turn`(
        reason: String?,
        withCall: Boolean,
        stop: StopReason,
    ) = runTest {
        server.enqueueSse(incomplete(reason, if (withCall) "$text,$call" else text))
        assertEquals(stop, model().complete(Kit.request()).stopReason)
    }

    @Test
    fun `a filtered incomplete reply is a refusal`() =
        runTest {
            server.enqueueSse(incomplete("content_filter", "$text,$call"))
            assertEquals(
                "content_filter",
                assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }.category,
            )
        }

    @Test
    fun `on the ChatGPT plan only response completed succeeds`() =
        runTest {
            server.enqueueSse(incomplete("max_output_tokens", text))
            assertThrows<ModelError.BadResponse> { model(Kit.PLAN).complete(Kit.request()) }
            server.enqueueSse(incomplete(null, text))
            assertThrows<ModelError.BadResponse> { model(Kit.PLAN).complete(Kit.request()) }
            server.enqueueSse(incomplete("content_filter", text))
            assertThrows<ModelError.Refusal> { model(Kit.PLAN).complete(Kit.request()) }
            server.enqueueSse(
                "data: {\"type\":\"response.completed\",\"response\":" +
                    "{\"status\":\"completed\",\"output\":[$text]}}\n\n",
            )
            assertEquals(StopReason.END_TURN, model(Kit.PLAN).complete(Kit.request()).stopReason)
        }

    private fun terminal(
        type: String,
        response: String,
    ) = "data: {\"type\":\"$type\",\"response\":{$response}}\n\n"

    @ParameterizedTest(name = "{0} with status {1}")
    @CsvSource(
        "response.completed, in_progress",
        "response.completed, incomplete",
        "response.completed, failed",
        "response.incomplete, completed",
        "response.incomplete, queued",
    )
    fun `the terminal event and the response status must agree`(
        type: String,
        status: String,
    ) = runTest {
        server.enqueueSse(terminal(type, """"status":"$status","output":[$text]"""))
        assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        server.enqueueSse(terminal(type, """"status":7,"output":[$text]"""))
        assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
    }

    @Test
    fun `a terminal event without a status takes the event's state`() =
        runTest {
            server.enqueueSse(terminal("response.completed", """"output":[$text]"""))
            assertEquals(StopReason.END_TURN, model().complete(Kit.request()).stopReason)
            server.enqueueSse(terminal("response.incomplete", """"status":null,"output":[$text]"""))
            assertEquals(StopReason.OTHER, model().complete(Kit.request()).stopReason)
        }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "\"text\"", "[1]", "[null]", "[{\"content\":[]}]", "[{\"type\":7}]"])
    fun `a final output that is not a list of items is a bad response, streamed or whole`(output: String) =
        runTest {
            server.enqueueSse(terminal("response.completed", """"status":"completed","output":$output"""))
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$text}\n\n" +
                    terminal("response.completed", """"status":"completed","output":$output"""),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            json("""{"status":"completed","output":$output}""")
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        }

    @ParameterizedTest(name = "final fields after status: [{0}]")
    @ValueSource(strings = ["", ""","output":null"""])
    fun `a final output that is absent or null leaves the streamed items`(output: String) =
        runTest {
            val done = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":$text}\n\n"
            server.enqueueSse(done + terminal("response.completed", """"status":"completed"$output"""))
            val deltas = Deltas()
            val reply = model().complete(Kit.request(), deltas)
            assertEquals(StopReason.END_TURN, reply.stopReason)
            assertEquals(listOf(Part.Text("Hi")), reply.parts, "the streamed item is the reply")
            assertEquals("Hi", deltas.text, "and the preview shows it")
        }
}
