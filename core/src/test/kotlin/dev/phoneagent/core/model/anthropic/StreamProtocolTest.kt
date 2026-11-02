package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Events that break the stream protocol are [ModelError.BadResponse], each with a precise reason. */
class StreamProtocolTest {
    private fun stream(onDelta: (Delta) -> Unit = {}) =
        MessagesStream(Kit.PROVIDER, Kit.MODEL, Redactor.SHAPES_ONLY, onDelta)

    /** Feeds [events] (data only, the type inside) and finishes. */
    private fun run(vararg events: String) =
        stream().run {
            events.forEach { accept(SseEvent(null, it)) }
            finish()
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations", "blockViolations", "messageViolations", "assemblyViolations")
    fun `protocol violations are bad responses`(
        expected: String,
        events: List<String>,
    ) {
        val error = assertThrows<ModelError.BadResponse> { run(*events.toTypedArray()) }
        assertTrue(error.message!!.contains(expected), "${error.message} should say '$expected'")
    }

    @Test
    fun `the event name stands in for a missing type, blank data and DONE are skipped`() {
        val stream = stream()
        assertFalse(stream.accept(SseEvent("message_start", """{"message":{"id":"m","model":"x"}}""")))
        assertFalse(stream.accept(SseEvent(null, "")))
        assertFalse(stream.accept(SseEvent(null, "[DONE]")))
        val delta = """{"type":"message_delta","delta":{"stop_reason":"stop_sequence","stop_sequence":"END"}}"""
        assertFalse(stream.accept(SseEvent(null, delta)))
        assertTrue(stream.accept(SseEvent("message_stop", "{}")))
        val reply = stream.finish()
        assertEquals(StopReason.OTHER, reply.stopReason)
        assertNull(reply.usage, "no usage was reported")
        assertEquals("\"END\"", reply.providerMetadata!!["stop_sequence"].toString())
    }

    @Test
    fun `a whole reply that is an error or not a message is mapped`() {
        val busy = """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}"""
        val overloaded = assertThrows<ModelError.Overloaded> { stream().acceptJson(busy) }
        assertNull(overloaded.retryAfterMs)
        assertThrows<ModelError.BadResponse> { stream().acceptJson("[]") }
        assertThrows<ModelError.BadResponse> { stream().acceptJson("""{"type":"message"}""") }
        assertThrows<ModelError.BadResponse> { stream().acceptJson("{") }
    }

    @Test
    fun `a tool call whose input came whole shows it once as arguments`() {
        val deltas = Deltas()
        val reply =
            stream(deltas).run {
                accept(SseEvent(null, START))
                val whole = """{"type":"tool_use","id":"toolu_1","name":"t","input":{"a":1}}"""
                accept(SseEvent(null, blockStart(0, whole)))
                accept(SseEvent(null, blockStop(0)))
                accept(SseEvent(null, blockStart(1, """{"type":"tool_use","id":"toolu_2","name":"t","input":null}""")))
                accept(SseEvent(null, blockStop(1)))
                accept(SseEvent(null, messageDelta("""{"stop_reason":"tool_use"}""")))
                accept(SseEvent(null, STOP))
                finish()
            }
        assertEquals(listOf("""{"a":1}""", "{}"), reply.toolCalls.map { it.argsJson })
        assertEquals(listOf("""{"a":1}""", "{}"), reply.toolCalls.map { deltas.args(it.id) })
        assertEquals(2, deltas.started.size)
    }

    @Test
    fun `the last message_delta value wins and usage totals replace earlier ones`() {
        val reply =
            run(
                usageStart("""{"input_tokens":5,"output_tokens":1,"cache_read_input_tokens":null}"""),
                messageDelta("""{"stop_reason":"max_tokens","stop_details":{"type":"x"}}""", """{"output_tokens":7}"""),
                messageDelta("""{"stop_reason":"end_turn","stop_details":null}""", """{"output_tokens":9}"""),
                STOP,
            )
        assertEquals(StopReason.END_TURN, reply.stopReason)
        assertEquals(9, reply.usage!!.outputTokens)
        assertEquals(5, reply.usage.inputTokens)
        assertNull(reply.providerMetadata!!["stop_details"])
        assertTrue(reply.parts.isEmpty())
    }

    @Test
    fun `redacted thinking of a declined attempt is dropped without a preview divergence`() {
        val reply =
            run(
                START,
                blockStart(0, """{"type":"redacted_thinking","data":"cmVkYWN0ZWQ="}"""),
                STOP0,
                blockStart(1, """{"type":"fallback","from":{"model":"a"},"to":{"model":"b"}}"""),
                blockStop(1),
                blockStart(2, """{"type":"redacted_thinking","data":"a2VwdA=="}"""),
                blockStop(2),
                END,
                STOP,
            )
        val kept = reply.parts.single() as Part.Reasoning
        assertEquals("\"a2VwdA==\"", kept.opaque.jsonObject["data"].toString(), "only the served attempt's block")
        assertEquals(
            """[{"index":0,"type":"redacted_thinking"}]""",
            reply.providerMetadata!!["fallback_discarded"].toString(),
            "which block was dropped, not its data",
        )
        assertNull(reply.providerMetadata[PREVIEW_DIVERGED], "redacted thinking was never shown")
    }

    @Test
    fun `the served model is the last fallback's target, also when usage lists no iterations`() {
        val reply =
            run(
                START,
                blockStart(
                    0,
                    """{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-5"}}""",
                ),
                STOP0,
                END,
                STOP,
            )
        assertEquals("\"claude-opus-5\"", reply.providerMetadata!!["served_model"].toString())
        assertEquals("\"${Kit.MODEL.value}\"", reply.providerMetadata["requested_model"].toString())
    }

    @Test
    fun `empty text blocks leave no part`() {
        val reply =
            run(
                START,
                TEXT0,
                STOP0,
                END,
                STOP,
            )
        assertEquals(emptyList<Part>(), reply.parts)
    }

    companion object {
        private const val START =
            """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""
        private const val STOP = """{"type":"message_stop"}"""
        private const val TEXT = """{"type":"text","text":""}"""
        private val TEXT0 = blockStart(0, TEXT)
        private val THINK0 = blockStart(0, """{"type":"thinking","thinking":""}""")
        private val TOOL0 = blockStart(0, """{"type":"tool_use","id":"toolu_1","name":"t","input":{}}""")
        private val STOP0 = blockStop(0)
        private val END = messageDelta("""{"stop_reason":"end_turn"}""")

        private fun blockStart(
            index: Int,
            block: String,
        ) = """{"type":"content_block_start","index":$index,"content_block":$block}"""

        private fun blockDelta(
            index: Int,
            delta: String,
        ) = """{"type":"content_block_delta","index":$index,"delta":$delta}"""

        private fun blockStop(index: Int) = """{"type":"content_block_stop","index":$index}"""

        private fun messageDelta(
            delta: String,
            usage: String? = null,
        ) = """{"type":"message_delta","delta":$delta${usage?.let { ""","usage":$it""" }.orEmpty()}}"""

        private fun usageStart(usage: String) = """{"type":"message_start","message":{"usage":$usage}}"""

        private fun case(
            expected: String,
            vararg events: String,
        ) = arrayOf<Any>(expected, events.toList())

        @JvmStatic
        fun violations(): List<Array<Any>> =
            listOf(
                case("event data is not a JSON object", "[1]"),
                case("event is not JSON", "{"),
                case("event without a type", "{}"),
                case("message_start without a message", """{"type":"message_start"}"""),
                case("message_start twice", START, START),
                case(
                    "message_start with content",
                    """{"type":"message_start","message":{"content":[{"type":"text"}]}}""",
                ),
                case("content_block_start before message_start", TEXT0),
                case(
                    "event without a valid block index",
                    START,
                    """{"type":"content_block_start","index":"0","content_block":{"type":"text"}}""",
                ),
                case("event without a valid block index", START, blockStart(-1, """{"type":"text"}""")),
            )

        @JvmStatic
        fun blockViolations(): List<Array<Any>> =
            listOf(
                case("block 0 without content_block", START, """{"type":"content_block_start","index":0}"""),
                case("block 0 started twice", START, TEXT0, TEXT0),
                case("tool_use block 0 without an id", START, blockStart(0, """{"type":"tool_use","name":"t"}""")),
                case(
                    "tool_use block 0 without a name",
                    START,
                    blockStart(0, """{"type":"tool_use","id":"toolu_1","name":" "}"""),
                ),
                case(
                    "content_block_delta for block 3, which never started",
                    START,
                    blockDelta(3, """{"type":"text_delta","text":"x"}"""),
                ),
                case(
                    "after its content_block_stop",
                    START,
                    TEXT0,
                    STOP0,
                    blockDelta(0, """{"type":"text_delta","text":"x"}"""),
                ),
                case(
                    "content_block_delta without a delta",
                    START,
                    TEXT0,
                    """{"type":"content_block_delta","index":0}""",
                ),
                case("text_delta without text", START, TEXT0, blockDelta(0, """{"type":"text_delta"}""")),
                case("thinking_delta without thinking", START, THINK0, blockDelta(0, """{"type":"thinking_delta"}""")),
                case(
                    "signature_delta without signature",
                    START,
                    THINK0,
                    blockDelta(0, """{"type":"signature_delta"}"""),
                ),
                case(
                    "input_json_delta without partial_json",
                    START,
                    TOOL0,
                    blockDelta(0, """{"type":"input_json_delta"}"""),
                ),
                case(
                    "unexpected text_delta for a tool_use block",
                    START,
                    TOOL0,
                    blockDelta(0, """{"type":"text_delta","text":"x"}"""),
                ),
                case(
                    "unexpected signature_delta for a redacted_thinking block",
                    START,
                    blockStart(0, """{"type":"redacted_thinking","data":"x"}"""),
                    blockDelta(0, """{"type":"signature_delta","signature":"x"}"""),
                ),
                case("content_block_stop twice", START, TEXT0, STOP0, STOP0),
            )

        /** Block order, tool ids and what a kept block must carry to be echoed. */
        @JvmStatic
        fun assemblyViolations(): List<Array<Any>> =
            listOf(
                case("block 0 starts after block 1", START, blockStart(1, TEXT), blockStop(1), TEXT0),
                case("block 1 starts while block 0 is open", START, TEXT0, blockStart(1, TEXT)),
                case(
                    "block 1 repeats tool id toolu_1",
                    START,
                    blockStart(0, """{"type":"server_tool_use","id":"toolu_1","name":"web_search","input":{}}"""),
                    STOP0,
                    blockStart(1, """{"type":"tool_use","id":"toolu_1","name":"t","input":{}}"""),
                ),
                case(
                    "thinking block 0 has no signature",
                    START,
                    THINK0,
                    blockDelta(0, """{"type":"signature_delta","signature":""}"""),
                    STOP0,
                    END,
                    STOP,
                ),
                case(
                    "redacted_thinking block 0 has no data",
                    START,
                    blockStart(0, """{"type":"redacted_thinking","data":""}"""),
                    STOP0,
                    END,
                    STOP,
                ),
                case(
                    "block 0 has unsupported type 'mystery'",
                    START,
                    blockStart(0, """{"type":"mystery"}"""),
                    STOP0,
                    END,
                    STOP,
                ),
            )

        @JvmStatic
        fun messageViolations(): List<Array<Any>> =
            listOf(
                case("message_delta before message_start", END),
                case("message_delta without a delta", START, """{"type":"message_delta"}"""),
                case("message_stop before message_start", STOP),
                case("content_block_start after message_stop", START, END, STOP, TEXT0),
                case("is not a count", usageStart("""{"output_tokens":"5"}"""), END, STOP),
                case("is out of range", usageStart("""{"output_tokens":-1}"""), END, STOP),
                case("is out of range", usageStart("""{"output_tokens":3000000000}"""), END, STOP),
                case(
                    "input token total",
                    usageStart("""{"input_tokens":2000000000,"cache_read_input_tokens":2000000000}"""),
                    END,
                    STOP,
                ),
            )
    }
}
