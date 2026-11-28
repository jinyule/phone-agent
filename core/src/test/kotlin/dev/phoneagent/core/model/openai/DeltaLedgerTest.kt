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
        val ledger = DeltaLedger(deltas, EchoGuard.NONE)
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
        val ledger = DeltaLedger(Deltas(), EchoGuard.NONE)
        ledger.text("Hello")
        ledger.reasoning("Thinking")
        ledger.started(id, "tap")
        ledger.args(id, "{\"x\":1}")
        val parts = listOf(reasoning("Other"), Part.Text("Goodbye"), Part.ToolCall(id, "tap", "{\"y\":2}"))
        assertEquals(listOf("text", "reasoning", "args"), ledger.catchUp(parts))
    }

    @Test
    fun `an announced call the reply does not have, or names differently, is a contradiction`() {
        val ledger = DeltaLedger(Deltas(), EchoGuard.NONE)
        ledger.started(id, "tap")
        ledger.args(id, "{}")
        assertEquals(listOf("calls"), ledger.catchUp(listOf(Part.Text("Done"))), "the call vanished")
        val renamed = DeltaLedger(Deltas(), EchoGuard.NONE)
        renamed.started(id, "tap")
        assertEquals(listOf("calls"), renamed.catchUp(listOf(Part.ToolCall(id, "swipe", "{}"))), "another name")
        val replaced = DeltaLedger(Deltas(), EchoGuard.NONE)
        replaced.started(id, "tap")
        val other = Part.ToolCall(ToolCallId("call_2"), "tap", "{}")
        assertEquals(listOf("calls"), replaced.catchUp(listOf(other)), "another call instead")
        val kept = DeltaLedger(Deltas(), EchoGuard.NONE)
        kept.started(id, "tap")
        assertEquals(
            emptyList<String>(),
            kept.catchUp(listOf(Part.ToolCall(id, "tap", "{}"), other)),
            "one more is fine",
        )
    }

    @Test
    fun `announced calls in another order than the reply's are a contradiction`() {
        val second = ToolCallId("call_2")
        val calls = listOf(Part.ToolCall(id, "tap", "{}"), Part.ToolCall(second, "swipe", "{}"))
        val late = DeltaLedger(Deltas(), EchoGuard.NONE)
        late.started(second, "swipe")
        assertEquals(listOf("calls"), late.catchUp(calls), "a call the reply puts first was announced after")
        val swapped = DeltaLedger(Deltas(), EchoGuard.NONE)
        swapped.started(second, "swipe")
        swapped.started(id, "tap")
        assertEquals(listOf("calls"), swapped.catchUp(calls), "both announced, the other way round")
        val between = DeltaLedger(Deltas(), EchoGuard.NONE)
        between.started(id, "tap")
        between.started(second, "swipe")
        val third = Part.ToolCall(ToolCallId("call_3"), "type", "{}")
        assertEquals(listOf("calls"), between.catchUp(listOf(calls[0], third, calls[1])), "one inserted between")
        val prefix = DeltaLedger(Deltas(), EchoGuard.NONE)
        prefix.started(id, "tap")
        prefix.started(second, "swipe")
        assertEquals(emptyList<String>(), prefix.catchUp(calls + third), "calls added at the end only")
    }
}
