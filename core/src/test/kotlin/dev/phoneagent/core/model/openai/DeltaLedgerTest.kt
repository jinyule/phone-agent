package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeltaLedgerTest {
    private val id = ToolCallId("call_1")

    private fun reasoning(summary: String) =
        Part.Reasoning(
            ProviderId("p"),
            ModelId("m"),
            ReasoningKind.RESPONSES_ENCRYPTED,
            JsonObject(emptyMap()),
            summary,
        )

    @Test
    fun `the end shows only what the deltas missed`() {
        val deltas = Deltas()
        val ledger = DeltaLedger(deltas)
        ledger.text("Hel")
        ledger.reasoning("Thi")
        ledger.started(id, "tap")
        ledger.args(id, "{\"x\"")
        ledger.args(ToolCallId("unknown"), "dropped")
        val parts = listOf(reasoning("Think"), Part.Text("Hello"), Part.ToolCall(id, "tap", "{\"x\":1}"))
        assertEquals(emptyList<String>(), ledger.catchUp(parts))
        assertEquals("Hello" to "Think", deltas.text to deltas.reasoning)
        assertEquals("{\"x\":1}", deltas.args(id))
        assertEquals(listOf(id), deltas.started.map { it.id }, "announced once")
    }

    @Test
    fun `channels the reply contradicts are reported, in order`() {
        val ledger = DeltaLedger(Deltas())
        ledger.text("Hello")
        ledger.reasoning("Thinking")
        ledger.started(id, "tap")
        ledger.args(id, "{\"x\":1}")
        val parts = listOf(reasoning("Other"), Part.Text("Goodbye"), Part.ToolCall(id, "tap", "{\"y\":2}"))
        assertEquals(listOf("text", "reasoning", "args"), ledger.catchUp(parts))
    }
}
