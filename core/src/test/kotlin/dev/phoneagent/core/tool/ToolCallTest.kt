package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolCallTest {
    private val id = ToolCallId("c1")

    @Test
    fun `calls carry a reason outside args and a valid name`() {
        val call =
            ToolCall(
                id,
                TaskId("t"),
                SessionId("s"),
                "phone_tap",
                JsonObject(mapOf("ref" to JsonPrimitive("e1"))),
                "tap",
            )
        assertEquals(call, Json.decodeFromString<ToolCall>(Json.encodeToString(call)))
        assertThrows<IllegalArgumentException> { call.copy(reason = " ") }
        assertThrows<IllegalArgumentException> { call.copy(name = "phone tap") }
        assertThrows<IllegalArgumentException> { call.copy(args = JsonObject(mapOf("reason" to JsonPrimitive("x")))) }
    }

    @Test
    fun `results become model parts and only OK is not an error`() {
        val image = Part.Image(BlobRef("d".repeat(64)), "image/jpeg", 1, 1)
        val ok = ToolResult(ToolStatus.OK, listOf(Part.Text("done"), image))
        assertEquals(Part.ToolResult(id, ok.content, isError = false), ok.toPart(id))
        ToolStatus.entries.filter { it != ToolStatus.OK }.forEach {
            assertEquals(true, ToolResult.failure(it, "no").toPart(id).isError, it.name)
        }
        assertThrows<IllegalArgumentException> { ToolResult.failure(ToolStatus.OK, "no") }
        assertThrows<IllegalArgumentException> { ToolResult(ToolStatus.OK, listOf(Part.ToolCall(id, "x", "{}"))) }
        assertEquals(ok, Json.decodeFromString<ToolResult>(Json.encodeToString(ok)))
    }

    @Test
    fun `a result may end the turn, and only a successful one`() {
        val end = ToolControl.EndTurn(EndReason.BLOCKED, "需要登录，已交还给用户")
        val result = ToolResult(ToolStatus.OK, listOf(Part.Text("ended")), control = end)
        assertEquals(
            """{"status":"OK","content":[{"type":"text","text":"ended"}],"control":""" +
                """{"type":"end_turn","reason":"BLOCKED","summary":"需要登录，已交还给用户"}}""",
            Json.encodeToString(result),
        )
        assertEquals(result, Json.decodeFromString<ToolResult>(Json.encodeToString(result)))
        assertEquals(null, ToolResult.ok("x").control)
        assertEquals(
            ToolResult.ok("x"),
            Json.decodeFromString<ToolResult>("""{"status":"OK","content":[{"type":"text","text":"x"}]}"""),
        )
        assertThrows<IllegalArgumentException> {
            ToolResult(ToolStatus.ERROR, listOf(Part.Text("failed")), control = end)
        }
        assertThrows<IllegalArgumentException> { ToolControl.EndTurn(EndReason.OTHER, " ") }
        assertEquals(
            listOf("IMPOSSIBLE", "BLOCKED", "USER_STOPPED", "OTHER"),
            EndReason.entries.map { it.name },
        )
    }
}
