package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolRegistryTest {
    private fun schema(
        reasonType: String = "string",
        required: List<String> = listOf("reason"),
    ) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reason") { put("type", reasonType) }
            putJsonObject("ref") { put("type", "string") }
        }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    private class FakeTool(
        name: String,
        override val group: ToolGroup,
        parameters: JsonObject,
        description: String = "Does $name.",
        override val timeoutMs: Long = 1_000,
    ) : Tool {
        override val def = ToolDef(name, description, parameters)
        override val risk = RiskClass.READ
        override val concurrencySafe = false

        override suspend fun execute(
            call: ToolCall,
            ctx: ToolContext,
        ): ToolResult = ToolResult.ok("${ctx.taskId}:${call.name}:${ctx.storeBlob(byteArrayOf())}")

        override fun presentCall(call: ToolCall) = StepCard(call.name, call.reason)

        override fun presentResult(
            call: ToolCall,
            result: ToolResult,
        ) = StepCard(
            call.name,
            call.reason,
            listOf(StepDetail("status", result.status.name)),
            state = StepState.SUCCEEDED,
        )
    }

    private fun tool(
        name: String,
        group: ToolGroup = ToolGroup.PHONE,
    ) = FakeTool(name, group, schema())

    @Test
    fun `manifest follows expansion order then registration order, and expanding only appends`() {
        val tools =
            listOf(
                tool("phone_tap"),
                tool("ask_user", ToolGroup.GENERAL),
                tool("phone_observe"),
                tool("recall", ToolGroup.KNOWLEDGE),
            )
        val registry = ToolRegistry(tools, listOf(ToolGroup.GENERAL, ToolGroup.PHONE, ToolGroup.GENERAL))
        assertEquals(listOf(ToolGroup.GENERAL, ToolGroup.PHONE), registry.expandedGroups)
        assertEquals(listOf("ask_user", "phone_tap", "phone_observe"), registry.manifest.map { it.name })
        val expanded = registry.expand(ToolGroup.KNOWLEDGE)
        assertEquals(registry.manifest, expanded.manifest.take(registry.manifest.size), "existing prefix unchanged")
        assertEquals("recall", expanded.manifest.last().name)
        assertSame(expanded, expanded.expand(ToolGroup.PHONE))
        assertNull(registry["recall"], "collapsed tools are not callable")
        assertEquals("recall", expanded["recall"]?.def?.name)
        assertNull(registry["nope"])
        assertEquals(4, registry.tools.size)
    }

    @Test
    fun `duplicate names and invalid definitions fail construction with every problem listed`() {
        val bad =
            listOf(
                tool("a"),
                tool("a", ToolGroup.GENERAL),
                FakeTool("b", ToolGroup.PHONE, schema(), description = " "),
                FakeTool(
                    "c",
                    ToolGroup.PHONE,
                    schema(),
                    description =
                        "x".repeat(ToolRegistry.MAX_DESCRIPTION_LENGTH + 1),
                ),
                FakeTool("d", ToolGroup.PHONE, schema(), timeoutMs = 0),
                FakeTool("e", ToolGroup.PHONE, schema(reasonType = "number")),
                FakeTool("f", ToolGroup.PHONE, schema(required = emptyList())),
            )
        val message = assertThrows<IllegalArgumentException> { ToolRegistry(bad, ToolGroup.entries) }.message.orEmpty()
        listOf(
            "duplicate tool name 'a'",
            "b: description is blank",
            "c: description longer",
            "d: timeoutMs",
            "e: schema must declare",
            "f: schema must declare",
        ).forEach { assertTrue(it in message, "missing '$it' in:\n$message") }
    }

    @Test
    fun `schema checks`() {
        fun problems(schema: JsonObject) = ToolRegistry.problemsOf(FakeTool("t", ToolGroup.PHONE, schema))
        assertEquals(emptyList<String>(), problems(schema()))
        assertTrue(problems(JsonObject(mapOf("type" to JsonPrimitive("array")))).any { "type must be" in it })
        assertTrue(
            problems(JsonObject(mapOf("type" to JsonPrimitive("object")))).any { "needs a \"properties\"" in it },
        )
        val withBadProperty =
            JsonObject(
                schema() + (
                    "properties" to
                        JsonObject(
                            (schema()["properties"] as JsonObject) + ("x" to JsonPrimitive(1)),
                        )
                ),
            )
        assertTrue(problems(withBadProperty).any { "property 'x' must be a schema object" in it })
        assertTrue(
            problems(JsonObject(schema() + ("required" to JsonPrimitive("reason")))).any {
                "must be an array" in
                    it
            },
        )
        assertTrue(
            problems(
                JsonObject(schema() + ("required" to JsonArray(listOf(JsonPrimitive("reason"), JsonPrimitive(1))))),
            ).any {
                "only strings" in
                    it
            },
        )
        assertTrue(problems(schema(required = listOf("reason", "ghost"))).any { "'ghost' is not declared" in it })
        val noRequired = JsonObject(schema() - "required")
        assertTrue(problems(noRequired).any { "required string property 'reason'" in it })
    }

    @Test
    fun `tools execute with a context and present pure cards`() =
        runTest {
            val tap = tool("phone_tap")
            val call =
                ToolCall(ToolCallId("c1"), TaskId("t1"), SessionId("s1"), "phone_tap", JsonObject(emptyMap()), "打开聊天")
            val ctx =
                object : ToolContext {
                    override val taskId = TaskId("t1")
                    override val sessionId = SessionId("s1")
                    override val screen: ScreenPort? = null
                    override val clock = Clock { 0 }

                    override suspend fun storeBlob(bytes: ByteArray) = BlobRef("c".repeat(64))
                }
            val result = tap.execute(call, ctx)
            assertEquals(ToolResult.ok("t1:phone_tap:${"c".repeat(64)}"), result)
            assertFalse(tap.concurrencySafe)
            assertEquals(StepCard("phone_tap", "打开聊天", state = StepState.RUNNING), tap.presentCall(call))
            assertEquals(StepState.SUCCEEDED, tap.presentResult(call, result).state)
            assertNull(ctx.screen)
            assertEquals(0, ctx.clock.epochMillis())
            assertEquals(ctx.sessionId, call.sessionId)
        }
}
