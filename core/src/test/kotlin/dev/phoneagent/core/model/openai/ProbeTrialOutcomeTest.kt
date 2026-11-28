package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * "Test and save" verifies tool calling only from a reply that finished as a tool step
 * (`StopReason.TOOL_USE`): a cut-off or otherwise unfinished reply fails the trial even when it
 * holds the right native call and token.
 */
class ProbeTrialOutcomeTest {
    @StartStop
    private val server = Kit.server()

    private val token = "pa-1234"
    private val call =
        """{"type":"function_call","id":"fc_1","call_id":"call_t","name":"confirm_connection",""" +
            """"arguments":"{\"token\":\"$token\"}"}"""

    private suspend fun trial(protocol: ModelProtocol): ProbeResult {
        val profile = Kit.profile(server.base(), protocol = protocol)
        return Kit.adapters().probe().testAndSave(profile, Kit.MODEL, Kit.capabilities(), token)
    }

    private fun responsesReply(
        event: String,
        status: String,
        details: String?,
    ): String {
        val incomplete = details?.let { ""","incomplete_details":$it""" }.orEmpty()
        return "data: {\"type\":\"$event\",\"response\":{\"status\":\"$status\"$incomplete,\"output\":[$call]}}\n\n"
    }

    private fun chatReply(finishReason: String) =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                """{"name":"confirm_connection","arguments":"{\"token\":\"$token\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"$finishReason"}]}""",
        )

    private fun assertUnfinished(result: ProbeResult) {
        assertTrue(result is ProbeResult.Failed, "an unfinished reply verifies nothing")
        result as ProbeResult.Failed
        assertEquals(ProbeStep.TOOL_CALL, result.step)
        assertNull(result.kind)
        assertTrue(result.message.contains("did not finish"), result.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"reason":"something_new"}""", "null", "{}", """{"reason":"max_output_tokens"}"""])
    fun `an incomplete Responses reply does not pass the trial`(details: String) =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(responsesReply("response.incomplete", "incomplete", details))
            assertUnfinished(trial(ModelProtocol.OPENAI_RESPONSES))
        }

    @Test
    fun `a Chat reply cut at the length limit does not pass the trial`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(chatReply("length"))
            assertUnfinished(trial(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
        }

    @Test
    fun `a Chat call ended by an unknown finish_reason does not pass the trial`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(chatReply("something_new"))
            assertUnfinished(trial(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
        }

    @Test
    fun `a native call that finishes the reply passes, on both protocols`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(responsesReply("response.completed", "completed", null))
            assertTrue(trial(ModelProtocol.OPENAI_RESPONSES) is ProbeResult.Passed)
            server.enqueueError(404, "{}")
            server.enqueueSse(chatReply("tool_calls"))
            assertTrue(trial(ModelProtocol.OPENAI_CHAT_COMPLETIONS) is ProbeResult.Passed)
        }
}
