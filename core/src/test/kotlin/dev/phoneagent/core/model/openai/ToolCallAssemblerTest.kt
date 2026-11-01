package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolCallAssemblerTest {
    private val deltas = Deltas()
    private var ids = 0
    private val calls = ToolCallAssembler({ "call_gen_${++ids}" }, DeltaLedger(deltas), Redactor.SHAPES_ONLY)

    private fun merge(fragment: String) = calls.merge(Json.parseToJsonElement(fragment) as JsonObject)

    @Test
    fun `a call is announced with its whole name once its arguments start`() {
        merge("""{"index":0,"id":"call_a","function":{"name":"get_"}}""")
        merge("""{"index":0,"function":{"name":"weather"}}""")
        assertEquals(emptyList<Any>(), deltas.started, "the name may still grow")
        merge("""{"index":0,"function":{"arguments":"{}"}}""")
        assertEquals(listOf("get_weather"), deltas.started.map { it.name })
        assertEquals("{}", deltas.args(ToolCallId("call_a")))
    }

    @Test
    fun `a call whose arguments start announces the calls before it first`() {
        merge("""{"index":0,"id":"call_a","function":{"name":"tap"}}""")
        merge("""{"index":1,"id":"call_b","function":{"name":"get_weather","arguments":"{"}}""")
        assertEquals(listOf("call_a", "call_b"), deltas.started.map { it.id.value })
        assertEquals("" to "{", deltas.args(ToolCallId("call_a")) to deltas.args(ToolCallId("call_b")))
    }
}
