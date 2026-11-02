package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The deltas add up to the reply (model-layer §3.1, [DeltaLedger]): the preview never shows text
 * the final reply takes back, wherever the stream splits, and the reasoning deltas follow one
 * channel, the one the reply's summaries use. Where a provider's final output contradicts its own
 * deltas, the reply says so instead of the difference going unnoticed.
 */
class DeltaConsistencyTest {
    @StartStop
    private val server = Kit.server()

    private fun chat(): ChatModel {
        val profile = Kit.profile(server.base())
        val wire = Kit.declaring(profile, setOf(LeakedCallFormat.DSML, LeakedCallFormat.TOOL_CALL_TAG))
        return Kit.adapters().model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), wire)
    }

    private fun responses(): ChatModel =
        Kit.adapters().unprobed(
            Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES),
            Kit.MODEL,
            Kit.capabilities(),
            Kit.blobs(),
        )

    private fun text(response: ChatResponse) = response.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun summaries(response: ChatResponse) =
        response.parts
            .filterIsInstance<Part.Reasoning>()
            .mapNotNull { it.summary }
            .joinToString("\n\n")

    /**
     * Every split, into pieces of [size] characters, of answers with recovery on: wholly leaked
     * calls (recovered, so no text) and markup with words around it or markup-like text (kept).
     */
    @ParameterizedTest(name = "pieces of {0}")
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 64])
    fun `the text deltas add up to the final text, recovered or kept`(size: Int) =
        runTest {
            val tag = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {}}\n</tool_call>"
            val dsml =
                "<｜DSML｜function_calls><｜DSML｜invoke name=\"get_weather\"></｜DSML｜invoke></｜DSML｜function_calls>"
            val answers =
                listOf(
                    "\n\n$tag\n" to 1,
                    "  \n$dsml" to 1,
                    "Calling now.\n\n$tag\n" to 0,
                    "$dsml\nDone.  " to 0,
                    "  <b>bold</b> and <tool_call> \n" to 0,
                    "a < b, and $tag" to 0,
                )
            for ((answer, calls) in answers) {
                val chunks =
                    answer.chunked(size).map {
                        """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(it)}}}]}"""
                    }
                val end = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
                server.enqueueSse(Kit.sse(*chunks.toTypedArray(), end))
                val deltas = Deltas()
                val response = chat().complete(Kit.request(), deltas)
                val label = "pieces of $size: ${JsonPrimitive(answer)}"
                assertEquals(calls, response.toolCalls.size, label)
                assertEquals(if (calls == 0) answer else "", text(response), label)
                assertEquals(text(response), deltas.text, label)
                assertNull(response.providerMetadata?.get(PREVIEW_DIVERGED), label)
            }
        }

    private fun reasoningStream(vararg deltas: String): String {
        val item =
            """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"summary"}],""" +
                """"content":[{"type":"reasoning_text","text":"private"}]}"""
        val events =
            listOf("""{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}""") +
                deltas.toList() +
                listOf(
                    """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                    """{"type":"response.completed","response":{"status":"completed","output":[$item,""" +
                        """{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}}""",
                )
        return events.joinToString("") { "data: $it\n\n" }
    }

    private val raw =
        """{"type":"response.reasoning_text.delta","item_id":"rs_1","content_index":0,"delta":"private"}"""
    private val summary =
        """{"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":0,"delta":"summary"}"""

    @ParameterizedTest(name = "summary first: {0}")
    @ValueSource(booleans = [true, false])
    fun `raw reasoning and summaries are separate channels`(summaryFirst: Boolean) =
        runTest {
            val order = if (summaryFirst) arrayOf(summary, raw) else arrayOf(raw, summary)
            server.enqueueSse(reasoningStream(*order))
            val deltas = Deltas()
            val response = responses().complete(Kit.request(), deltas)
            assertEquals(if (summaryFirst) "summary" else "private", summaries(response))
            assertEquals(summaries(response), deltas.reasoning, "the deltas follow the channel the summary uses")
            assertNull(response.providerMetadata?.get(PREVIEW_DIVERGED))
        }

    @ParameterizedTest(name = "contradicts: {0}")
    @ValueSource(booleans = [true, false])
    fun `a provider whose output contradicts its deltas is flagged`(contradict: Boolean) =
        runTest {
            val final = if (contradict) "Goodbye" else "Hello"
            server.enqueueSse(
                listOf(
                    """{"type":"response.output_text.delta","item_id":"m_1","delta":"Hello"}""",
                    """{"type":"response.completed","response":{"status":"completed","output":""" +
                        """[{"type":"message","content":[{"type":"output_text","text":"$final"}]}]}}""",
                ).joinToString("") { "data: $it\n\n" },
            )
            val deltas = Deltas()
            val response = responses().complete(Kit.request(), deltas)
            assertEquals(final, text(response), "the final output is the reply")
            val flagged = response.providerMetadata?.get(PREVIEW_DIVERGED)
            assertEquals(if (contradict) JsonArray(listOf(JsonPrimitive("text"))) else null, flagged)
        }

    @Test
    fun `a call shown on the stream that the final output drops is flagged`() =
        runTest {
            val item = """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"tap","arguments":""}"""
            server.enqueueSse(
                listOf(
                    """{"type":"response.output_item.added","output_index":0,"item":$item}""",
                    """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{}"}""",
                    """{"type":"response.completed","response":{"status":"completed","output":""" +
                        """[{"type":"message","content":[{"type":"output_text","text":"Done"}]}]}}""",
                ).joinToString("") { "data: $it\n\n" },
            )
            val deltas = Deltas()
            val response = responses().complete(Kit.request(), deltas)
            assertEquals(emptyList<Part.ToolCall>(), response.toolCalls, "the final output is the reply")
            assertEquals(listOf("call_1"), deltas.started.map { it.id.value }, "the preview showed it")
            assertEquals(JsonArray(listOf(JsonPrimitive("calls"))), response.providerMetadata?.get(PREVIEW_DIVERGED))
        }

    @Test
    fun `a final output with a call before the ones the stream showed is flagged`() =
        runTest {
            fun call(n: Int) =
                """{"type":"function_call","id":"fc_$n","call_id":"call_$n","name":"tap","arguments":"{}"}"""
            server.enqueueSse(
                listOf(
                    """{"type":"response.output_item.added","output_index":1,"item":${call(2)}}""",
                    """{"type":"response.completed","response":{"status":"completed","output":[${call(
                        1,
                    )},${call(2)}]}}""",
                ).joinToString("") { "data: $it\n\n" },
            )
            val deltas = Deltas()
            val response = responses().complete(Kit.request(), deltas)
            assertEquals(
                listOf("call_1", "call_2"),
                response.toolCalls.map { it.id.value },
                "the final output is the reply",
            )
            assertEquals(
                listOf("call_2", "call_1"),
                deltas.started.map { it.id.value },
                "the preview showed them reversed",
            )
            assertEquals(JsonArray(listOf(JsonPrimitive("calls"))), response.providerMetadata?.get(PREVIEW_DIVERGED))
        }
}
