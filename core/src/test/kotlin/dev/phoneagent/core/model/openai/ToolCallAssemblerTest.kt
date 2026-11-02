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
    fun `a later call waits for the names of the calls before it`() {
        merge("""{"index":0,"id":"call_a","function":{"name":"get_"}}""")
        merge("""{"index":1,"id":"call_b","function":{"name":"tap","arguments":"{"}}""")
        assertEquals(emptyList<Any>(), deltas.started, "call_a's name may still grow, and call_b comes after it")
        merge("""{"index":0,"function":{"name":"weather"}}""")
        merge("""{"index":1,"function":{"arguments":"\"x\":1"}}""")
        assertEquals(emptyList<Any>(), deltas.started, "a later call's arguments say nothing about call_a's name")
        merge("""{"index":0,"function":{"arguments":"{}"}}""")
        assertEquals(
            listOf("call_a" to "get_weather", "call_b" to "tap"),
            deltas.started.map { it.id.value to it.name },
        )
        assertEquals("{" + "\"x\":1", deltas.args(ToolCallId("call_b")), "held arguments come with the announcement")
        merge("""{"index":1,"function":{"arguments":"}"}}""")
        assertEquals("{}" to "{\"x\":1}", deltas.args(ToolCallId("call_a")) to deltas.args(ToolCallId("call_b")))
    }

    @Test
    fun `calls are announced in order as each one's arguments start`() {
        merge("""{"index":0,"id":"call_a","function":{"name":"tap","arguments":"{"}}""")
        merge("""{"index":1,"id":"call_b","function":{"name":"swipe"}}""")
        merge("""{"index":2,"id":"call_c","function":{"name":"type","arguments":"{"}}""")
        assertEquals(listOf("call_a"), deltas.started.map { it.id.value }, "call_c waits for call_b")
        merge("""{"index":1,"function":{"arguments":"{"}}""")
        assertEquals(listOf("call_a", "call_b", "call_c"), deltas.started.map { it.id.value })
        assertEquals(listOf("{", "{", "{"), deltas.started.map { deltas.args(it.id) })
    }
}
