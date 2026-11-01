package dev.phoneagent.core.model

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PartRoundTripTest {
    private val blob = BlobRef("b".repeat(64))
    private val image = Part.Image(blob, "image/png", 10, 20)
    private val reasoning =
        Part.Reasoning(
            ProviderId(
                "openai",
            ),
            ModelId("gpt-6"),
            ReasoningKind.RESPONSES_ENCRYPTED,
            buildJsonObject {
                put("encrypted_content", "zzz")
            },
        )

    private val parts: List<Part> =
        listOf(
            Part.Text("hi"),
            image,
            Part.ToolCall(ToolCallId("c1"), "phone_tap", "{not json"),
            Part.ToolResult(ToolCallId("c1"), listOf(Part.Text("ok"), image), isError = false),
            reasoning,
            Part.Reasoning(ProviderId("a"), ModelId("m"), ReasoningKind.ANTHROPIC_COMPACTION, JsonNull, summary = "s"),
        )

    @Test
    fun `every part round-trips with its discriminator`() {
        val names =
            serializer<Part>()
                .descriptor
                .getElementDescriptor(1)
                .elementNames
                .toSet()
        assertEquals(setOf("text", "image", "tool_call", "tool_result", "reasoning"), names)
        for (part in parts) {
            val json = Json.encodeToString(part)
            assertEquals(part, Json.decodeFromString<Part>(json), json)
        }
        assertEquals("""{"type":"text","text":"hi"}""", Json.encodeToString<Part>(Part.Text("hi")))
    }

    @Test
    fun `messages and requests round-trip`() {
        val request =
            ChatRequest(
                system = listOf(PromptBlock("identity", "x")),
                tools = emptyList(),
                messages =
                    listOf(
                        Message(Role.USER, listOf(Part.Text("hi"), image)),
                        Message(Role.ASSISTANT, listOf(reasoning, Part.Text("t"), parts[2])),
                        Message(Role.TOOL, listOf(parts[3])),
                    ),
                reasoning = ReasoningLevel.XHIGH,
            )
        assertEquals(request, Json.decodeFromString<ChatRequest>(Json.encodeToString(request)))
    }

    @Test
    fun `message roles restrict which parts they hold`() {
        assertThrows<IllegalArgumentException> { Message(Role.USER, listOf(parts[2])) }
        assertThrows<IllegalArgumentException> { Message(Role.USER, listOf(reasoning)) }
        assertThrows<IllegalArgumentException> { Message(Role.USER, listOf(parts[3])) }
        assertThrows<IllegalArgumentException> { Message(Role.TOOL, listOf(Part.Text("x"))) }
        assertThrows<IllegalArgumentException> { Message(Role.ASSISTANT, listOf(parts[3])) }
        Message(Role.ASSISTANT, listOf(image))
    }

    @Test
    fun `parts validate their content`() {
        assertThrows<IllegalArgumentException> { Part.Image(blob, "image/gif", 1, 1) }
        assertThrows<IllegalArgumentException> { Part.Image(blob, "image/png", 1, 0) }
        assertThrows<IllegalArgumentException> { Part.ToolResult(ToolCallId("c"), listOf(reasoning), false) }
        assertTrue("image/webp" in Part.IMAGE_MIMES)
    }
}
