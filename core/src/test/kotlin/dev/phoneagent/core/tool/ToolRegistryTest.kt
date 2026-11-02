package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ToolInteraction
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

    private fun problemsWith(
        properties: String,
        topLevel: String = "",
    ): List<String> {
        val text =
            """{"type":"object","properties":{"reason":{"type":"string"}$properties},""" +
                """"required":["reason"]$topLevel}"""
        return ToolRegistry.problemsOf(FakeTool("t", ToolGroup.PHONE, Json.parseToJsonElement(text) as JsonObject))
    }

    /** Name to (properties added to a valid schema, path of the property that must be reported). */
    private val unsupportedSchemas: Map<String, Pair<String, String>> =
        mapOf(
            "unknown type" to (""","x":{"type":"strng"}""" to "x"),
            "type list" to (""","x":{"type":["string","null"]}""" to "x"),
            "missing type" to (""","x":{"description":"no type"}""" to "x"),
            "unsupported keyword" to (""","x":{"type":"string","oneOf":7}""" to "x"),
            "pattern is not supported" to (""","x":{"type":"string","pattern":"a+"}""" to "x"),
            "keyword value of the wrong type" to (""","x":{"type":"integer","minimum":"zero"}""" to "x"),
            "keyword of another type" to (""","x":{"type":"string","minimum":1}""" to "x"),
            "fractional bound for an integer" to (""","x":{"type":"integer","maximum":1.5}""" to "x"),
            "array without items" to (""","x":{"type":"array"}""" to "x"),
            "bad items" to (""","x":{"type":"array","items":{"type":"strng"}}""" to "x.items"),
            "bad nested property" to
                (""","x":{"type":"object","properties":{"y":{"type":"integer","maximum":"ten"}}}""" to "x.y"),
            "nested required not declared" to
                (""","x":{"type":"object","properties":{},"required":["z"]}""" to "x"),
            "enum of the wrong type" to (""","x":{"type":"string","enum":[1]}""" to "x"),
            "empty enum" to (""","x":{"type":"string","enum":[]}""" to "x"),
            "duplicate enum values" to (""","x":{"type":"string","enum":["a","a"]}""" to "x"),
            "min above max" to (""","x":{"type":"string","minLength":3,"maxLength":2}""" to "x"),
            "negative length" to (""","x":{"type":"string","minLength":-1}""" to "x"),
            "additionalProperties of the wrong type" to
                (""","x":{"type":"object","additionalProperties":"no"}""" to "x"),
            "description not a string" to (""","x":{"type":"string","description":1}""" to "x"),
            "nested properties not an object" to (""","x":{"type":"object","properties":[]}""" to "x"),
            "nested property not a schema" to (""","x":{"type":"object","properties":{"y":1}}""" to "x"),
            "required twice" to
                (""","x":{"type":"object","properties":{"y":{"type":"string"}},"required":["y","y"]}""" to "x"),
            "items not a schema" to (""","x":{"type":"array","items":[{"type":"string"}]}""" to "x"),
            "negative item count" to (""","x":{"type":"array","items":{"type":"string"},"minItems":-1}""" to "x"),
            "enum not an array" to (""","x":{"type":"integer","enum":1}""" to "x"),
            "bad additionalProperties schema" to
                (""","x":{"type":"object","additionalProperties":{"type":"null"}}""" to "x.additionalProperties"),
            "keyword on a boolean" to (""","x":{"type":"boolean","enum":[true]}""" to "x"),
            "number bound not a number" to (""","x":{"type":"number","minimum":true}""" to "x"),
            "number bounds crossed" to (""","x":{"type":"number","minimum":2,"maximum":1.5}""" to "x"),
            "integer enum with a fraction" to (""","x":{"type":"integer","enum":[1,1.5]}""" to "x"),
            // Above 2^53 both bounds round to the same Double; integers are compared exactly.
            "integer bounds crossed above 2^53" to
                (""","x":{"type":"integer","minimum":9007199254740993,"maximum":9007199254740992}""" to "x"),
            "lengths crossed above 2^53" to
                (""","x":{"type":"string","minLength":9007199254740993,"maxLength":9007199254740992}""" to "x"),
            "item counts crossed above 2^53" to
                (
                    ""","x":{"type":"array","items":{"type":"string"},""" +
                        """"minItems":9007199254740993,"maxItems":9007199254740992}""" to "x"
                ),
        )

    @Test
    fun `schemas outside the supported subset are rejected at every depth`() {
        val accepted =
            unsupportedSchemas.mapNotNull { (name, case) ->
                val (properties, path) = case
                val problems = problemsWith(properties)
                if (problems.any { it.startsWith("t: $path: ") }) null else "$name (at $path): $problems"
            }
        assertEquals(emptyList<String>(), accepted, "schemas that should have been rejected")
        assertTrue(problemsWith("", ""","additionalProperties":"no"""").isNotEmpty(), "top level is checked too")
        assertTrue(problemsWith("", ""","title":"T"""").isNotEmpty(), "unknown top-level keyword")
    }

    @Test
    fun `the supported subset is accepted at every depth, integers above 2^53 included`() {
        val supported =
            ""","x":{"type":"array","minItems":1,"maxItems":3,"description":"points",""" +
                """"items":{"type":"object","additionalProperties":false,"required":["n"],""" +
                """"properties":{"n":{"type":"integer","minimum":0,"maximum":999,"description":"d"}}}},""" +
                """"k":{"type":"string","enum":["a","b"],"minLength":1,"maxLength":5},""" +
                """"m":{"type":"object","additionalProperties":{"type":"string"}},""" +
                """"b":{"type":"boolean"},"f":{"type":"number","minimum":0.5,"enum":[0.5,1]},""" +
                """"big":{"type":"integer","minimum":9007199254740992,"maximum":9007199254740993}"""
        assertEquals(
            emptyList<String>(),
            problemsWith(supported, ""","additionalProperties":false,"description":"d""""),
        )
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
                    override val interaction: ToolInteraction = Unattended
                    override val apps: AppCatalog? = null

                    override suspend fun storeBlob(bytes: ByteArray) = BlobRef("c".repeat(64))
                }
            val result = tap.execute(call, ctx)
            assertEquals(ToolResult.ok("t1:phone_tap:${"c".repeat(64)}"), result)
            assertFalse(tap.concurrencySafe)
            assertEquals(StepCard("phone_tap", "打开聊天", state = StepState.RUNNING), tap.presentCall(call))
            assertEquals(StepState.SUCCEEDED, tap.presentResult(call, result).state)
            assertNull(ctx.screen)
            assertNull(ctx.apps)
            assertEquals(0, ctx.clock.epochMillis())
            assertEquals(ctx.sessionId, call.sessionId)
            assertEquals(AskResult.Unavailable, ctx.interaction.ask(Question("?")))
            assertEquals(HandoffResult.UNAVAILABLE, ctx.interaction.handOff(HandoffKind.LOGIN, "登录"))
        }

    /** What an unattended task gets: nobody can answer. */
    private object Unattended : ToolInteraction {
        override suspend fun ask(question: Question) = AskResult.Unavailable

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ) = HandoffResult.UNAVAILABLE
    }
}
