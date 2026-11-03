package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.WireSetting
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class WireSupportTest {
    private val call = Part.ToolCall(ToolCallId("c1"), "get_weather", "{}")
    private val other = Part.ToolCall(ToolCallId("c2"), "get_weather", "{}")

    private fun result(id: String) = Part.ToolResult(ToolCallId(id), listOf(Part.Text("ok")), isError = false)

    private fun assistant(vararg parts: Part) = Message(Role.ASSISTANT, parts.toList())

    private fun tool(vararg ids: String) = Message(Role.TOOL, ids.map(::result))

    private fun preflight(vararg messages: Message) =
        WireSupport.preflight(ChatRequest(emptyList(), emptyList(), messages.toList(), Kit.UNPREPARED))

    @Test
    fun `a dotted setting is a nested object`() {
        val flat = buildJsonObject { WireSupport.put(this, WireSetting("reasoning_effort", "low")) }
        assertEquals("""{"reasoning_effort":"low"}""", flat.toString())
        val nested = buildJsonObject { WireSupport.put(this, WireSetting("reasoning.effort", "high")) }
        assertEquals("""{"reasoning":{"effort":"high"}}""", nested.toString())
    }

    @Test
    fun `system blocks join with blank lines`() {
        assertEquals("a\n\nb", WireSupport.systemText(listOf(PromptBlock("1", "a"), PromptBlock("2", "b"))))
        assertEquals("data:image/png;base64,QQ==", WireSupport.dataUrl("image/png", "QQ=="))
    }

    @Test
    fun `answered calls pass preflight`() {
        assertDoesNotThrow {
            preflight(Kit.user("hi"), assistant(call, other), tool("c1"), tool("c2"), assistant(Part.Text("done")))
        }
    }

    @Test
    fun `unanswered calls and stray results fail before sending`() {
        val open = assertThrows<ModelError.InvalidRequest> { preflight(Kit.user("hi"), assistant(call)) }
        assertEquals("messages", open.param)
        assertEquals("message 2: tool calls [c1] have no result", open.detail)
        val interrupted = assertThrows<ModelError.InvalidRequest> { preflight(assistant(call), Kit.user("next")) }
        assertEquals("message 1: tool calls [c1] have no result", interrupted.detail)
        val stray = assertThrows<ModelError.InvalidRequest> { preflight(Kit.user("hi"), tool("c9")) }
        assertEquals("message 1: results [c9] answer no pending tool call", stray.detail)
    }

    @Test
    fun `server-side context editing is refused`() {
        val request =
            ChatRequest(
                emptyList(),
                emptyList(),
                listOf(Kit.user("hi")),
                Kit.UNPREPARED,
                contextEdits = ContextEditPolicy.ServerSide(null, 1, null),
            )
        assertEquals("contextEdits", assertThrows<ModelError.InvalidRequest> { WireSupport.preflight(request) }.param)
    }

    @Test
    fun `images are read once, including those in tool results`() =
        runTest {
            val a = Kit.blob('a')
            val b = Kit.blob('b')
            val reads = mutableListOf<String>()
            val blobs =
                dev.phoneagent.core.model.BlobSource { ref ->
                    reads += ref.value
                    byteArrayOf(ref.value[0].code.toByte())
                }
            val image = { ref: dev.phoneagent.core.ids.BlobRef -> Part.Image(ref, "image/png", 1, 1) }
            val request =
                ChatRequest(
                    emptyList(),
                    emptyList(),
                    listOf(
                        Message(Role.USER, listOf(image(a), image(a))),
                        assistant(call),
                        Message(Role.TOOL, listOf(Part.ToolResult(call.id, listOf(image(b)), isError = false))),
                    ),
                    Kit.UNPREPARED,
                )
            val data = ImageData.load(request, blobs)
            assertEquals("YQ==", data[a])
            assertEquals("Yg==", data[b])
            assertEquals(listOf(a.value, b.value), reads)
        }

    @Test
    fun `an unreadable image is an invalid request`() =
        runTest {
            val request =
                ChatRequest(
                    emptyList(),
                    emptyList(),
                    listOf(Message(Role.USER, listOf(Part.Image(Kit.blob('c'), "image/png", 1, 1)))),
                    Kit.UNPREPARED,
                )
            val error = assertThrows<ModelError.InvalidRequest> { ImageData.load(request, Kit.blobs()) }
            assertEquals("image", error.param)
            assertEquals(false, error.retryable)
        }
}
