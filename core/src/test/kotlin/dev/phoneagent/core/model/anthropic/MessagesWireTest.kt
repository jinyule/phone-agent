package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Requests the API would refuse are refused before sending; the rest map deterministically. */
class MessagesWireTest {
    private val wire = MessagesWire(Kit.PROVIDER, Kit.MODEL)

    private fun body(request: ChatRequest): JsonObject =
        wire.body(request, WireRules.of(request, CredentialKind.API_KEY), ImageData.NONE)

    private fun refused(request: ChatRequest): ModelError.InvalidRequest = assertThrows { body(request) }

    @Test
    fun `a call without a result is refused`() {
        val error = refused(Kit.request(Kit.user("a"), Kit.assistant(Kit.call("toolu_1")), Kit.user("b")))
        assertEquals("messages", error.param)
        assertTrue(error.detail.contains("message 2"), error.detail)
        val trailing = refused(Kit.request(Kit.user("a"), Kit.assistant(Kit.call("toolu_1"))))
        assertTrue(trailing.detail.contains("no result"), trailing.detail)
    }

    @Test
    fun `a result that answers no pending call is refused`() {
        val error = refused(Kit.request(Kit.user("a"), Kit.tool(Kit.result("toolu_9", Part.Text("x")))))
        assertTrue(error.detail.contains("answer no pending tool call"), error.detail)
    }

    @Test
    fun `results split over several tool messages are fine`() {
        val request =
            Kit.request(
                Kit.user("a"),
                Kit.assistant(Kit.call("toolu_1"), Kit.call("toolu_2")),
                Kit.tool(Kit.result("toolu_1")),
                Kit.tool(Kit.result("toolu_2")),
            )
        val messages = body(request)["messages"]!!.jsonArray
        assertEquals(3, messages.size, "the two tool messages merge into one user message")
        assertEquals(2, messages[2].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `assistant prefill is refused`() {
        val error = refused(Kit.request(Kit.user("a"), Kit.assistant(Part.Text("好的，"))))
        assertTrue(error.detail.contains("prefill"), error.detail)
    }

    @Test
    fun `a request that maps to no message is refused`() {
        val empty = refused(Kit.request(Message(Role.USER, listOf(Part.Text("")))))
        assertTrue(empty.detail.contains("every message is empty"), empty.detail)
    }

    @Test
    fun `images in assistant messages are refused`() {
        val image = Part.Image(Kit.blob('b'), "image/png", 1, 1)
        val error = refused(Kit.request(Kit.user("a"), Kit.assistant(image), Kit.user("b")))
        assertTrue(error.detail.contains("message 1"), error.detail)
    }

    @Test
    fun `a stored block that is not an object is refused, a foreign one is dropped`() {
        val broken = Part.Reasoning(Kit.PROVIDER, Kit.MODEL, ReasoningKind.ANTHROPIC_THINKING, JsonPrimitive("x"))
        val error = refused(Kit.request(Kit.user("a"), Kit.assistant(broken, Part.Text("b")), Kit.user("c")))
        assertTrue(error.detail.contains("not a JSON object"), error.detail)
        val foreign = broken.copy(kind = ReasoningKind.CHAT_REASONING_FIELD)
        val messages = body(Kit.request(Kit.user("a"), Kit.assistant(foreign), Kit.user("c")))["messages"]!!.jsonArray
        assertEquals(1, messages.size, "the assistant message is left empty, so the two user messages merge")
        assertEquals(2, messages[0].jsonObject["content"]!!.jsonArray.size)
    }

    @Test
    fun `strict tools must close every object`() {
        val open =
            ToolDef(
                "tap",
                "点击",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("list") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") { putJsonObject("x") { put("type", "integer") } }
                            }
                        }
                    }
                    put("additionalProperties", false)
                },
            )
        val strict = Kit.wire(settings = AnthropicSettings(strictTools = true))
        val error = refused(Kit.request(tools = listOf(open), wire = strict))
        assertEquals("tools", error.param)
        assertTrue(error.detail.contains("tap: ") && error.detail.contains("list.items"), error.detail)
        val top = refused(Kit.request(wire = strict))
        assertTrue(top.detail.contains("the top level"), top.detail)
    }

    @Test
    fun `without tools no tool choice is sent, and without server edits no context management`() {
        val body = body(Kit.request(tools = emptyList(), contextEdits = ContextEditPolicy.ServerSide(null, 0, null)))
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        assertNull(body["context_management"])
        val betas =
            wire.betas(
                Kit.request(contextEdits = ContextEditPolicy.ServerSide(null, 0, 9)),
                WireRules.of(Kit.request(), CredentialKind.API_KEY),
            )
        assertEquals(listOf(MessagesWire.BETA_FALLBACK, MessagesWire.BETA_BINDING, MessagesWire.BETA_COMPACT), betas)
    }

    @Test
    fun `breakpoints skip thinking blocks`() {
        val thinking =
            Part.Reasoning(
                Kit.PROVIDER,
                Kit.MODEL,
                ReasoningKind.ANTHROPIC_THINKING,
                buildJsonObject { put("type", "thinking") },
            )
        val redacted =
            thinking.copy(
                kind = ReasoningKind.ANTHROPIC_REDACTED_THINKING,
                opaque =
                    buildJsonObject {
                        put("type", "redacted_thinking")
                    },
            )
        val request =
            Kit.request(
                Kit.user("a"),
                Kit.assistant(Part.Text("b"), thinking),
                Kit.assistant(thinking, redacted),
                Kit.user("c"),
                cache = CacheLayout(tools = false, system = false, recentMessages = 3),
            )
        val messages = body(request)["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonArray }
        val marked = messages.map { blocks -> blocks.map { "cache_control" in it.jsonObject } }
        assertEquals(listOf(listOf(true), listOf(true, false, false, false), listOf(true)), marked)
        val noSystem = body(Kit.request(system = listOf("")))
        assertNull(noSystem["system"], "empty prompt blocks are skipped")
    }

    @Test
    fun `tool_use input is the parsed arguments, or by intent an empty object, never a made-up field`() {
        assertEquals(buildJsonObject { put("a", 1) }, MessagesWire.argumentsObject("""{"a":1}"""))
        assertEquals(JsonObject(emptyMap()), MessagesWire.argumentsObject("""{"a":"""))
        assertEquals(JsonObject(emptyMap()), MessagesWire.argumentsObject("[1]"))
        assertEquals(JsonObject(emptyMap()), MessagesWire.argumentsObject(""))
    }

    @Test
    fun `an unreadable image is an invalid request naming the blob`() =
        runTest {
            val image = Part.Image(Kit.blob('c'), "image/png", 1, 1)
            val request = Kit.request(Message(Role.USER, listOf(image)))
            val error = assertThrows<ModelError.InvalidRequest> { ImageData.load(request, Kit.blobs()) }
            assertEquals("image", error.param)
            assertTrue(error.detail.contains(Kit.blob('c').value), error.detail)
        }

    @Test
    fun `is_error is sent only when true and empty results carry no content`() {
        val request =
            Kit.request(
                Kit.user("a"),
                Kit.assistant(Kit.call("toolu_1")),
                Kit.tool(Kit.result("toolu_1", Part.Text(""))),
            )
        val result =
            body(request)["messages"]!!
                .jsonArray[2]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject
        assertFalse("is_error" in result)
        assertFalse("content" in result)
    }
}
