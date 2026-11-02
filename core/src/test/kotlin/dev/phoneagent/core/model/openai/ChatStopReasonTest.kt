package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * A Chat Completions `finish_reason` and the calls a reply carries decide its stop reason. Calls
 * of a reply that ended normally (`stop`, `tool_calls`, `function_call`, `end_turn`, `eos`, or no
 * reason) are a tool step; a reply cut at the limit stays `MAX_TOKENS`; a reply that ended for a
 * reason the adapter does not know is `OTHER`, so its calls never run; a filtered one is a refusal.
 */
class ChatStopReasonTest {
    @StartStop
    private val server = MockWebServer()

    private fun model(): ChatModel =
        Kit.adapters().unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    private fun reply(
        finishReason: String?,
        withCall: Boolean,
    ): String {
        val delta =
            if (withCall) {
                """{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":"{}"}}]}"""
            } else {
                """{"content":"Hi"}"""
            }
        return Kit.sse(
            """{"choices":[{"index":0,"delta":$delta}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":${JsonPrimitive(finishReason)}}]}""",
        )
    }

    private suspend fun stopOf(
        finishReason: String?,
        withCall: Boolean,
    ): StopReason {
        server.enqueueSse(reply(finishReason, withCall))
        val response = model().complete(Kit.request())
        // The adapter keeps the calls whatever the reason; the stop reason alone says whether they run.
        assertEquals(withCall, response.parts.any { it is Part.ToolCall }, "parts: ${response.parts}")
        return response.stopReason
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["stop", "tool_calls", "function_call", "end_turn", "eos"])
    fun `calls of a reply that ended normally are a tool step`(finishReason: String?) =
        runTest { assertEquals(StopReason.TOOL_USE, stopOf(finishReason, withCall = true)) }

    @ParameterizedTest
    @ValueSource(strings = ["length", "max_tokens"])
    fun `calls of a reply cut at the limit stay cut`(finishReason: String) =
        runTest { assertEquals(StopReason.MAX_TOKENS, stopOf(finishReason, withCall = true)) }

    @ParameterizedTest
    @ValueSource(strings = ["something_new", "pause", "Stop"])
    fun `calls of a reply that ended for an unknown reason do not make a tool step`(finishReason: String) =
        runTest {
            assertEquals(StopReason.OTHER, stopOf(finishReason, withCall = true))
            assertEquals(StopReason.OTHER, stopOf(finishReason, withCall = false))
        }

    @ParameterizedTest
    @ValueSource(strings = ["content_filter", "sensitive"])
    fun `a filtered reply is a refusal even with calls`(finishReason: String) =
        runTest {
            server.enqueueSse(reply(finishReason, withCall = true))
            assertEquals(finishReason, assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }.category)
        }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["stop", "end_turn", "eos"])
    fun `without calls a normal end is the end of the turn`(finishReason: String?) =
        runTest { assertEquals(StopReason.END_TURN, stopOf(finishReason, withCall = false)) }

    @ParameterizedTest
    @ValueSource(strings = ["tool_calls", "function_call"])
    fun `a reply that announced calls but carries none is not the end of the turn`(finishReason: String) =
        runTest { assertEquals(StopReason.OTHER, stopOf(finishReason, withCall = false)) }
}
