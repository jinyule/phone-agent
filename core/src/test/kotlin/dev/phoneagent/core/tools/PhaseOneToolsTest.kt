package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolRegistry
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PhaseOneToolsTest {
    private val formatter = ScreenFormatter()
    private val tools = PhaseOneTools.create(formatter)

    @Test
    fun `the fourteen tools of phase1 section 5, in manifest order`() {
        assertEquals(
            listOf(
                "phone_observe",
                "phone_apps",
                "phone_wait",
                "phone_tap",
                "phone_type",
                "phone_scroll",
                "phone_swipe",
                "phone_key",
                "phone_launch",
                "phone_intent",
                "ask_user",
                "hand_to_user",
                "request_confirmation",
                "end_not_done",
            ),
            tools.map { it.def.name },
        )
    }

    @Test
    fun `the registry accepts them and every schema starts with a required reason`() {
        val registry = ToolRegistry(tools, listOf(ToolGroup.PHONE, ToolGroup.GENERAL))
        assertEquals(14, registry.manifest.size)
        for (tool in tools) {
            val schema = tool.def.parameters
            val properties = schema["properties"]!!.jsonObject
            assertEquals("reason", properties.keys.first(), tool.def.name)
            assertEquals(
                ToolSchema.REASON_DESCRIPTION,
                properties
                    .getValue("reason")
                    .jsonObject["description"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "reason",
                schema["required"]!!
                    .jsonArray
                    .first()
                    .jsonPrimitive.content,
                tool.def.name,
            )
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content, tool.def.name)
        }
    }

    @Test
    fun `timeouts come from the config`() {
        val config =
            ToolsConfig(
                observeTimeoutMs = 1,
                appsTimeoutMs = 2,
                waitTimeoutMs = 50_000,
                actionTimeoutMs = 3,
                typeTimeoutMs = 4,
                launchTimeoutMs = 5,
                userTimeoutMs = 6,
                controlTimeoutMs = 7,
            )
        assertEquals(
            listOf(1L, 2L, 50_000L, 3L, 4L, 3L, 3L, 3L, 5L, 5L, 6L, 6L, 6L, 7L),
            PhaseOneTools.create(formatter, config).map { it.timeoutMs },
        )
    }

    @Test
    fun `config is validated`() {
        assertThrows<IllegalArgumentException> { ToolsConfig(stableQuietMs = 99) }
        assertThrows<IllegalArgumentException> { ToolsConfig(stableQuietMs = 30_001) }
        assertThrows<IllegalArgumentException> { ToolsConfig(waitTimeoutMs = 30_000) }
        assertThrows<IllegalArgumentException> { ToolsConfig(observeTimeoutMs = 0) }
        assertThrows<IllegalArgumentException> { ToolsConfig(controlTimeoutMs = -1) }
    }

    @Test
    fun `schema properties are unique`() {
        assertThrows<IllegalArgumentException> { ToolSchema.of { string("reason", "again") } }
        val (schema, names) = ToolSchema.of { strings("options", "选项", 3) }
        assertEquals(setOf("options"), names)
        assertEquals(
            buildJsonObject {
                put("type", "array")
                put("description", "选项")
                putJsonObject("items") { put("type", "string") }
                put("maxItems", 3)
            },
            schema["properties"]!!.jsonObject["options"],
        )
    }

    @Test
    fun `call cards show the reason and the given arguments in schema order, masked`() {
        val tap = tools.single { it.def.name == "phone_type" }
        val typed =
            call(
                "phone_type",
                """{"text":"卡号 6222021234567894","ref":"e9","screen":"s12","submit":null}""",
                reason = "填卡号",
            )
        assertEquals(
            StepCard(
                "phone_type",
                "填卡号",
                listOf(StepDetail("screen", "s12"), StepDetail("ref", "e9"), StepDetail("text", "卡号 「疑似银行卡 尾号7894」")),
            ),
            tap.presentCall(typed),
        )
        val ask = tools.single { it.def.name == "ask_user" }
        assertEquals(
            listOf(StepDetail("question", "选哪个？"), StepDetail("options", "甲, 乙")),
            ask.presentCall(call("ask_user", """{"question":"选哪个？","options":["甲",null,"乙"]}""")).details,
        )
        val odd = call("phone_observe", """{"detail":{"x":1}}""")
        assertEquals(listOf(StepDetail("detail", """{"x":1}""")), tools.first().presentCall(odd).details)
    }

    @Test
    fun `result cards add the result details, the first image and the state`() =
        runTest {
            val observe = tools.first()
            val call = call("phone_observe", """{"image":true}""")
            val ctx = FakeContext(FakeScreen(Screens.CHAT.copy(quality = TreeQuality.SPARSE)))
            val result = observe.execute(call, ctx)
            val card = observe.presentResult(call, result)
            assertEquals("测试这一步", card.summary)
            assertEquals(
                listOf(
                    StepDetail("image", "true"),
                    StepDetail("screen", "s12"),
                    StepDetail("quality", "SPARSE"),
                    StepDetail("imageAttached", "true"),
                ),
                card.details,
            )
            assertEquals(
                result.content
                    .filterIsInstance<Part.Image>()
                    .single()
                    .blob,
                card.image,
            )
            assertEquals(StepState.SUCCEEDED, card.state)
            val states =
                ToolStatus.entries.associateWith { status ->
                    observe
                        .presentResult(
                            call,
                            ToolResult(status, listOf(Part.Text("x")), JsonObject(emptyMap())),
                        ).state
                }
            // Stopped or timed out while running: it may have taken effect, so the card must not
            // say "failed" (the user might do it again). Not started at all is a plain failure.
            assertEquals(
                mapOf(
                    ToolStatus.OK to StepState.SUCCEEDED,
                    ToolStatus.ERROR to StepState.FAILED,
                    ToolStatus.REFUSED to StepState.REFUSED,
                    ToolStatus.INVALID_ARGS to StepState.FAILED,
                    ToolStatus.TIMEOUT to StepState.UNKNOWN,
                    ToolStatus.CANCELLED to StepState.UNKNOWN,
                    ToolStatus.SKIPPED to StepState.FAILED,
                    ToolStatus.NOT_STARTED to StepState.FAILED,
                    ToolStatus.OUTCOME_UNKNOWN to StepState.UNKNOWN,
                ),
                states,
            )
            assertNull(observe.presentResult(call, ToolResult.ok("x")).image)
        }
}
