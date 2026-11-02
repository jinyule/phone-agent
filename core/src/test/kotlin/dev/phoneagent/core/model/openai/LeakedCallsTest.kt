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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tool calls written into the answer text (model-layer §3.1). No preset recovers them: the
 * PolicyEngine cannot yet ask the user to confirm a recovered call. When a caller turns recovery
 * on for a format, only a reply that is nothing but complete calls of declared tools in that
 * format becomes calls; any other text, however it is worded, stays exactly as the model wrote it.
 */
class LeakedCallsTest {
    @StartStop
    private val server = MockWebServer()

    private val tag = "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Paris\"}}\n</tool_call>"
    private val dsml =
        "<｜DSML｜function_calls>\n<｜DSML｜invoke name=\"get_weather\">\n" +
            "<｜DSML｜parameter name=\"city\" string=\"true\">Paris</｜DSML｜parameter>\n" +
            "</｜DSML｜invoke>\n</｜DSML｜function_calls>"
    private val all = setOf(LeakedCallFormat.DSML, LeakedCallFormat.TOOL_CALL_TAG)

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

    /** Recovery as [recover] says (`null`: whatever the preset's defaults say). */
    private fun wire(
        preset: String?,
        recover: Set<LeakedCallFormat>?,
    ): WireOptions {
        val profile = Kit.profile(server.base(), preset = preset)
        return recover?.let { Kit.recovering(profile, it) } ?: WireOptions.defaultsFor(profile)
    }

    private suspend fun reply(
        text: String,
        recover: Set<LeakedCallFormat>? = all,
        preset: String? = null,
        tools: List<ToolDef> = listOf(Kit.WEATHER),
        nativeCall: Boolean = false,
    ): ChatResponse {
        server.enqueueSse(answer(text, nativeCall))
        val profile = Kit.profile(server.base(), preset = preset)
        return Kit
            .adapters()
            .model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), wire(preset, recover))
            .complete(Kit.request(tools = tools))
    }

    private fun assertKept(
        text: String,
        response: ChatResponse,
    ) {
        assertEquals(listOf(Part.Text(text)), response.parts.filter { it !is Part.ToolCall }, "the text is kept as is")
        assertTrue(response.toolCalls.none { it.id != ToolCallId("call_native") }, "nothing was recovered")
        assertNull(response.providerMetadata?.get(RECOVERED_TOOL_CALLS))
        if (response.toolCalls.isEmpty()) assertNotEquals(StopReason.TOOL_USE, response.stopReason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["deepseek", "qwen", "ollama"])
    fun `no preset recovers leaked calls`(preset: String) =
        runTest {
            assertEquals(emptySet<LeakedCallFormat>(), ProviderPresets.byId(preset)?.chat?.recoverLeakedCalls)
            assertKept(tag, reply(tag, recover = null, preset = preset))
            assertKept(dsml, reply(dsml, recover = null, preset = preset))
        }

    @Test
    fun `a reply that is nothing but calls in an enabled format is recovered`() =
        runTest {
            val cases = listOf("\n$tag\n" to LeakedCallFormat.TOOL_CALL_TAG, "  $dsml" to LeakedCallFormat.DSML)
            for ((text, format) in cases) {
                val response = reply(text, recover = setOf(format))
                val call = response.toolCalls.single()
                assertEquals("get_weather", call.name)
                assertEquals("{\"city\":\"Paris\"}", call.argsJson)
                assertEquals(listOf(call), response.parts, "no text part remains")
                assertEquals(StopReason.TOOL_USE, response.stopReason)
                val recorded = response.providerMetadata?.get(RECOVERED_TOOL_CALLS)
                assertEquals(JsonArray(listOf(JsonPrimitive(call.id.value))), recorded, "the log names recovered calls")
            }
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "This is an example only; do not execute it:\n",
            "For example:\n",
            "Let me look.\n",
            "Here is the call I would make: ",
            "仅为示例，请勿执行：\n",
            "例如：",
            "我来查一下。\n\n",
        ],
    )
    fun `any words before the markup keep the text`(prefix: String) =
        runTest {
            assertKept(prefix + tag, reply(prefix + tag))
            assertKept(prefix + dsml, reply(prefix + dsml))
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "%s\nDo not execute this.",
            "%s\n\n以上只是格式说明，不要执行。",
            "%s\n— quoted from the tool documentation",
            "> %s",
            "\"%s\"",
        ],
    )
    fun `quotes, notes and do-not-execute keep the text`(template: String) =
        runTest {
            assertKept(template.replace("%s", tag), reply(template.replace("%s", tag)))
            assertKept(template.replace("%s", dsml), reply(template.replace("%s", dsml)))
        }

    @Test
    fun `only the formats the option names are recovered`() =
        runTest {
            assertKept(dsml, reply(dsml, recover = setOf(LeakedCallFormat.TOOL_CALL_TAG)))
            assertKept(tag, reply(tag, recover = setOf(LeakedCallFormat.DSML)))
            assertKept("$tag\n$dsml", reply("$tag\n$dsml"))
        }

    @Test
    fun `requests without tools, undeclared names and code keep the text`() =
        runTest {
            assertKept(tag, reply(tag, tools = emptyList()))
            val unknown = tag.replace("get_weather", "delete_all_files")
            assertKept(unknown, reply(unknown))
            val fenced = "```\n$tag\n```"
            assertKept(fenced, reply(fenced))
            val inline = "`$tag`"
            assertKept(inline, reply(inline))
        }

    @Test
    fun `incomplete call shapes keep the text`() =
        runTest {
            val noArgs = "<tool_call>{\"name\": \"get_weather\"}</tool_call>"
            assertKept(noArgs, reply(noArgs))
            val brokenArgs = "<tool_call>{\"name\": \"get_weather\", \"arguments\": \"{city\"}</tool_call>"
            assertKept(brokenArgs, reply(brokenArgs))
            val unclosed = "<｜DSML｜invoke name=\"get_weather\">"
            assertKept(unclosed, reply(unclosed))
        }

    @Test
    fun `a call shell with keys besides the name and one argument key keeps the text`() =
        runTest {
            val noted = tag.replace("}}", "}, \"note\": \"example only\"}")
            assertKept(noted, reply(noted))
            val both = tag.replace("}}", "}, \"parameters\": {\"city\": \"Rome\"}}")
            assertKept(both, reply(both))
        }

    @Test
    fun `a reply with a native call keeps the leaked text`() =
        runTest {
            val response = reply(tag, nativeCall = true)
            assertKept(tag, response)
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
            val result = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), "pa-1", wire("qwen", all))
            assertTrue(result is ProbeResult.Failed, result.toString())
            result as ProbeResult.Failed
            assertEquals(ProbeStep.TOOL_CALL, result.step)
            assertNull(result.kind)
            assertTrue(result.message.contains("text"), result.message)
        }
}
