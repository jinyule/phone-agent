package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tool calls written into the answer text are turned into calls only where the preset says the
 * provider leaks them, and only when the markup is unmistakably a call of a declared tool
 * (model-layer §3.1). Anything else keeps the text exactly as the model wrote it.
 */
class LeakedCallsTest {
    @StartStop
    private val server = MockWebServer()

    private val leaked =
        "Let me look.\n<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}\n</tool_call>"

    private fun answer(
        text: String,
        nativeCall: Boolean = false,
    ): String {
        val native =
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_native","function":""" +
                """{"name":"get_weather","arguments":"{\"city\":\"Rome\"}"}}]}}]}"""
        val chunks =
            listOfNotNull(
                """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(text)}}}]}""",
                native.takeIf { nativeCall },
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            )
        return Kit.sse(*chunks.toTypedArray())
    }

    private suspend fun reply(
        text: String,
        preset: String? = "qwen",
        tools: List<ToolDef> = listOf(Kit.WEATHER),
        nativeCall: Boolean = false,
    ): ChatResponse {
        server.enqueueSse(answer(text, nativeCall))
        val profile = Kit.profile(server.base(), preset = preset)
        return Kit
            .adapters()
            .model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs())
            .complete(Kit.request(tools = tools))
    }

    private fun assertKept(
        text: String,
        response: ChatResponse,
    ) {
        assertEquals(listOf(Part.Text(text)), response.parts.filter { it !is Part.ToolCall }, "the text is kept as is")
        assertTrue(response.toolCalls.none { it.id != ToolCallId("call_native") }, "nothing was recovered")
        assertNull(response.providerMetadata?.get("recovered_tool_calls"))
    }

    @Test
    fun `a complete leaked call of a declared tool is recovered where the preset enables it`() =
        runTest {
            val response = reply(leaked)
            assertEquals("Let me look.", (response.parts.first() as Part.Text).text)
            val call = response.toolCalls.single()
            assertEquals("get_weather" to "{\"city\":\"Paris\"}", call.name to call.argsJson)
            assertEquals(StopReason.TOOL_USE, response.stopReason)
            val recorded = response.providerMetadata?.get("recovered_tool_calls")
            assertEquals(JsonArray(listOf(JsonPrimitive(call.id.value))), recorded, "the log names recovered calls")
        }

    @Test
    fun `presets that do not leak keep the text`() =
        runTest {
            assertKept(leaked, reply(leaked, preset = null))
            assertKept(leaked, reply(leaked, preset = "openrouter"))
        }

    @Test
    fun `requests without tools keep the text`() =
        runTest {
            assertKept(leaked, reply(leaked, tools = emptyList()))
        }

    @Test
    fun `calls of tools the request did not declare keep the text`() =
        runTest {
            val unknown = leaked.replace("get_weather", "delete_all_files")
            assertKept(unknown, reply(unknown))
        }

    @Test
    fun `examples in code fences or followed by more prose keep the text`() =
        runTest {
            val markup = "<tool_call>{\"name\":\"get_weather\",\"arguments\":{}}</tool_call>"
            val fenced = "Call it like this:\n```\n$markup\n```"
            assertKept(fenced, reply(fenced))
            val inline = "Write `$markup` to call it."
            assertKept(inline, reply(inline))
            val explained = "$leaked\nThat is the format; I will not call it now."
            assertKept(explained, reply(explained))
        }

    @Test
    fun `incomplete call shapes keep the text`() =
        runTest {
            val noArgs = "Checking.\n<tool_call>{\"name\": \"get_weather\"}</tool_call>"
            assertKept(noArgs, reply(noArgs))
            val brokenArgs = "Checking.\n<tool_call>{\"name\": \"get_weather\", \"arguments\": \"{city\"}</tool_call>"
            assertKept(brokenArgs, reply(brokenArgs))
            val unclosed = "Checking.\n<｜DSML｜invoke name=\"get_weather\">"
            assertKept(unclosed, reply(unclosed, preset = "deepseek"))
        }

    @Test
    fun `a reply with a native call keeps the leaked text`() =
        runTest {
            val response = reply(leaked, nativeCall = true)
            assertKept(leaked, response)
            assertEquals(listOf(ToolCallId("call_native")), response.toolCalls.map { it.id })
        }

    @Test
    fun `a trial call that only appears in the text does not pass test and save`() =
        runTest {
            val probe = Kit.adapters().probe()
            server.enqueueError(404, "{}")
            val trial =
                "<tool_call>{\"name\": \"confirm_connection\", \"arguments\": {\"token\": \"pa-1\"}}</tool_call>"
            server.enqueueSse(answer(trial))
            val profile = Kit.profile(server.base(), preset = "qwen")
            val result = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            assertTrue(result is ProbeResult.Failed, result.toString())
            result as ProbeResult.Failed
            assertEquals(ProbeStep.TOOL_CALL, result.step)
            assertNull(result.kind)
            assertTrue(result.message.contains("text"), result.message)
        }
}
