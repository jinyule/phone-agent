package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RequestTemplatesTest {
    /** The second sample request: the user's words, then a tool result listing `e2 张三`. */
    private val request = ReplaySamples.requests[1].request

    /** A template as it is written in `argsJson`: the regex JSON-escaped (`\\d`, `\"`). */
    private val template = """{"ref":"{{fromRequest:(e\\d+) 张三}}","times":1.0e2}"""

    @Test
    fun `a template resolves to group 1 of the last match and keeps the rest byte for byte`() {
        assertEquals("""{"ref":"e2","times":1.0e2}""", RequestTemplates.resolve(template, request, "t"))
    }

    @Test
    fun `the search covers every text, tool results included`() {
        assertEquals("看看屏幕上有谁\n我看一下\ne2 张三", RequestTemplates.searchText(request))
    }

    @Test
    fun `the last match wins`() {
        val twice = request.copy(messages = request.messages + Message(Role.USER, listOf(Part.Text("e7 张三"))))
        assertEquals("""{"ref":"e7","times":1.0e2}""", RequestTemplates.resolve(template, twice, "t"))
    }

    @Test
    fun `the value is inserted as a JSON string`() {
        val quoted = request.copy(messages = listOf(Message(Role.USER, listOf(Part.Text("name: a\"b")))))
        assertEquals(
            """{"v":"a\"b"}""",
            RequestTemplates.resolve("""{"v":"{{fromRequest:name: (.+)}}"}""", quoted, "t"),
        )
    }

    @Test
    fun `a template that matches nothing fails the replay`() {
        val failure =
            assertThrows<AssertionError> {
                RequestTemplates.resolve("""{"ref":"{{fromRequest:(e\\d+) 李四}}"}""", request, "request #2")
            }
        assertTrue(failure.message.orEmpty().startsWith("request #2: {{fromRequest:(e\\d+) 李四}}"), failure.message)
    }

    @Test
    fun `a template without a group is refused`() {
        assertThrows<IllegalArgumentException> { RequestTemplates.patterns("""{"ref":"{{fromRequest:e\\d+}}"}""") }
    }

    @Test
    fun `the regexes of a template are read unescaped`() {
        assertEquals(listOf("(e\\d+) 张三"), RequestTemplates.patterns(template).map { it.pattern })
    }

    @Test
    fun `arguments without a template are not templated`() {
        assertFalse(RequestTemplates.holdsTemplate("""{"ref":"e2","note":"{{fromRequest is prose"}"""))
    }

    @Test
    fun `only the tool calls of a reply are resolved`() {
        val call = Part.ToolCall(ToolCallId("call-1"), "phone_tap", template)
        val reply = ChatResponse(listOf(Part.Text("{{fromRequest:(x)}}"), call), StopReason.TOOL_USE)
        val resolved = RequestTemplates.resolve(reply, request, "t")
        assertEquals(
            listOf(Part.Text("{{fromRequest:(x)}}"), call.copy(argsJson = """{"ref":"e2","times":1.0e2}""")),
            resolved.parts,
        )
    }

    @Test
    fun `restoring puts the template back where the value is what it resolved to`() {
        val id = ToolCallId("call-1")
        val value = """{"ref":"e2","times":1.0e2}"""
        val restored =
            RequestTemplates.restore(
                listOf(Part.ToolCall(id, "phone_tap", value)),
                mapOf(id to RequestTemplates.Resolved(template, value)),
            )
        assertEquals(listOf(Part.ToolCall(id, "phone_tap", template)), restored)
    }

    @Test
    fun `restoring leaves a value the template did not give`() {
        val id = ToolCallId("call-1")
        val other = Part.ToolCall(id, "phone_tap", """{"ref":"e3"}""")
        assertEquals(
            listOf(other),
            RequestTemplates.restore(
                listOf(other),
                mapOf(id to RequestTemplates.Resolved(template, """{"ref":"e2"}""")),
            ),
        )
    }
}
