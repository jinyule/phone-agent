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
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    private val server = MockWebServer()

    private fun chat(): ChatModel =
        Kit
            .adapters()
            .model(Kit.profile(server.base(), preset = "deepseek"), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    private fun responses(): ChatModel =
        Kit.adapters().model(
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

    /** Every split of the leaked-call answers into pieces of [size] characters. */
    @ParameterizedTest(name = "pieces of {0}")
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 64])
    fun `the text deltas add up to the text a recovered call leaves`(size: Int) =
        runTest {
            val answers =
                listOf(
                    "Calling now.\n\n<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {}}\n</tool_call>\n",
                    "Done.  \n</｜DSML｜function_calls><｜DSML｜invoke name=\"get_weather\"></｜DSML｜invoke>",
                )
            for (answer in answers) {
                val chunks =
                    answer.chunked(size).map {
                        """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(it)}}}]}"""
                    }
                val end = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
                server.enqueueSse(Kit.sse(*chunks.toTypedArray(), end))
                val deltas = Deltas()
                val response = chat().complete(Kit.request(), deltas)
                assertEquals(1, response.toolCalls.size, "the call was recovered")
                assertEquals(text(response), deltas.text, "pieces of $size: ${JsonPrimitive(answer)}")
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
}
