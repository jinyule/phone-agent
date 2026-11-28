package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** The echo check on its own: windows over fragments, the parts a reply keeps, the marker. */
class EchoGuardTest {
    private val guard = Redactor(listOf(ECHOED)).guard()

    @Test
    fun `a window catches a secret fed one character at a time, at its last character`() {
        val window = guard.Window()
        ECHOED.dropLast(1).forEach { window.next(it.toString()) }
        val error = assertThrows<ModelError.BadResponse> { window.next(ECHOED.takeLast(1)) }
        assertTrue(error.discardsStreamed)
    }

    @Test
    fun `windows of different channels do not join`() {
        val text = guard.Window()
        val args = guard.Window()
        text.next(ECHOED.take(PREFIX))
        assertDoesNotThrow { args.next(ECHOED.drop(PREFIX)) }
        assertDoesNotThrow { text.next("x" + ECHOED.drop(PREFIX)) }
    }

    @Test
    fun `every value a reply keeps is checked, images and tool results are not part of one`() {
        val clean =
            listOf(
                Part.Text("hi"),
                Part.ToolCall(ToolCallId("call_1"), "tap", """{"x":1}"""),
                Part.Reasoning(Kit.PROVIDER, Kit.MODEL, ReasoningKind.RESPONSES_ENCRYPTED, JsonPrimitive("e"), "s"),
                Part.Image(Kit.blob('a'), "image/png", 1, 1),
                Part.ToolResult(ToolCallId("call_1"), listOf(Part.Text(ECHOED)), isError = false),
            )
        assertDoesNotThrow { guard.parts(clean) }
        val opaque = buildJsonObject { put("encrypted_content", "x$ECHOED") }
        val echoes =
            listOf(
                Part.Text("a $ECHOED"),
                Part.ToolCall(ToolCallId("call_$ECHOED"), "tap", "{}"),
                Part.ToolCall(ToolCallId("call_1"), ECHOED, "{}"),
                Part.ToolCall(ToolCallId("call_1"), "tap", "not json $ECHOED"),
                Part.Reasoning(Kit.PROVIDER, Kit.MODEL, ReasoningKind.RESPONSES_ENCRYPTED, JsonPrimitive("e"), ECHOED),
                Part.Reasoning(Kit.PROVIDER, Kit.MODEL, ReasoningKind.RESPONSES_ENCRYPTED, opaque, null),
            )
        for (part in echoes) assertThrows<ModelError.BadResponse> { guard.parts(listOf(part)) }
    }

    @Test
    fun `without a secret nothing is checked, and a short secret is checked like any other`() {
        assertSame(EchoGuard.NONE, Redactor(emptyList()).guard())
        assertThrows<ModelError.BadResponse> { Redactor(listOf("test")).guard().check("a test run") }
        val window = EchoGuard.NONE.Window()
        assertDoesNotThrow { window.next("qwen") }
        assertDoesNotThrow { EchoGuard.NONE.parts(listOf(Part.Text("qwen"))) }
    }

    @Test
    fun `the rejection is fixed text that discards what was streamed`() {
        val error = CredentialEcho.error()
        assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message)
        assertTrue(error.discardsStreamed)
        assertEquals(null, error.cause)
    }

    private companion object {
        const val ECHOED = "plain-placeholder-0001"
        const val PREFIX = 8
    }
}
