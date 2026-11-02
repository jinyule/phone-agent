package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Call arguments are checked as text the next step decodes as JSON (model-layer §2): a key in any
 * value (numbers too), in the arguments of a tool call or of a block this adapter does not know,
 * written with escapes the windows cannot see (`\u0070`, `\/`), in arguments a cut-off or broken
 * stream leaves invalid, rejects the whole reply with the fixed [ModelError.BadResponse]. Each case
 * pins the deltas shown before the rejection.
 */
class EscapedArgumentsTest {
    @StartStop
    private val server = MockWebServer()

    private suspend fun rejected(
        secret: String,
        shown: List<Delta>,
        enqueue: () -> Unit,
    ) {
        enqueue()
        val deltas = Deltas()
        val error =
            assertThrows<ModelError.BadResponse> {
                Kit.model(server, credentials = Keys(secret)).complete(Kit.request(), deltas)
            }
        assertEquals("bad response: $DETAIL", error.message)
        assertTrue(CredentialEcho.discardsStreamed(error), "the streamed output is not kept")
        assertTrue(secret !in error.stackTraceToString(), "the error shows the key")
        assertEquals(shown, deltas.all, "shown before the rejection")
    }

    private suspend fun rejected(
        secret: String,
        shown: List<Delta>,
        stream: String,
    ) = rejected(secret, shown) { server.enqueueSse(stream) }

    @Test
    fun `a numeric key in a call input that came whole is refused before anything is shown`() =
        runTest {
            val whole = listOf(start(0, tool("""{"pin":$NUMERIC}""")), stop(0))
            rejected(NUMERIC, emptyList(), body(whole, reason = "tool_use"))
        }

    @Test
    fun `a numeric key in a whole JSON reply is refused before anything is shown`() =
        runTest {
            val json =
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",""" +
                    """"content":[${tool("""{"pin":$NUMERIC}""")}],"stop_reason":"tool_use",""" +
                    """"usage":{"input_tokens":1,"output_tokens":1}}"""
            rejected(NUMERIC, emptyList()) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(json)
                        .build(),
                )
            }
        }

    @Test
    fun `a declined server tool call whose arguments escape the key still refuses the reply`() =
        runTest {
            for ((secret, args) in listOf(
                SLASHED to """{"q":"slash\/key-without-shape-789"}""",
                SLASHED to U_SLASHED,
            )) {
                val serverTool =
                    listOf(
                        start(0, """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}"""),
                        delta(0, args(args)),
                        stop(0),
                        start(1, """{"type":"fallback","from":{"model":"a"},"to":{"model":"b"}}"""),
                        stop(1),
                    )
                rejected(secret, emptyList(), body(serverTool + text(2, "好。")))
            }
        }

    @Test
    fun `cut-off or invalid arguments that escape the key refuse the reply`() =
        runTest {
            for ((secret, escaped) in ESCAPED) {
                val cut = """{"city":"$escaped"""
                for ((args, reason) in listOf(cut to "max_tokens", "$cut\"}}" to "tool_use")) {
                    val stream = body(call(args), reason = reason)
                    rejected(secret, listOf(STARTED, Delta.ToolCallArgs(CALL, args)), stream)
                }
            }
        }

    @Test
    fun `an escape split between two argument fragments is still decoded`() =
        runTest {
            val first = """{"q":"\u00"""
            val second = """73lash/key-without-shape-789"""
            val shown = listOf(STARTED, Delta.ToolCallArgs(CALL, first), Delta.ToolCallArgs(CALL, second))
            rejected(SLASHED, shown, body(call(first, second), reason = "max_tokens"))
        }

    @Test
    fun `a stream that breaks off after escaped arguments refuses the reply instead of failing as a network error`() =
        runTest {
            val args = """{"city":"$U_PLAIN"""
            val cut = Kit.sse(MESSAGE_START, start(0, tool("{}")), delta(0, args(args)))
            rejected(PLAIN, listOf(STARTED, Delta.ToolCallArgs(CALL, args)), cut)
        }

    @Test
    fun `unparseable data after escaped arguments refuses the reply`() =
        runTest {
            val args = """{"city":"$U_PLAIN"""
            val broken = Kit.sse(MESSAGE_START, start(0, tool("{}")), delta(0, args(args))) + "data: {not json\n\n"
            rejected(PLAIN, listOf(STARTED, Delta.ToolCallArgs(CALL, args)), broken)
        }

    @Test
    fun `the key as a string, or escaped in valid arguments, is refused as before`() =
        runTest {
            val string = listOf(start(0, tool("""{"pin":"$NUMERIC"}""")), stop(0))
            rejected(NUMERIC, emptyList(), body(string, reason = "tool_use"))
            val valid = """{"city":"$U_PLAIN"}"""
            rejected(PLAIN, listOf(STARTED, Delta.ToolCallArgs(CALL, valid)), body(call(valid), reason = "tool_use"))
        }

    private companion object {
        const val DETAIL = "the reply contained a credential this request sent and was discarded"

        /** One backslash. */
        const val BS = "\\"

        /** A key that is a valid JSON number. */
        const val NUMERIC = "123456"
        const val PLAIN = "plain-key-without-a-known-shape-123"
        const val SLASHED = "slash/key-without-shape-789"

        /** [PLAIN] with its first letter as a `\u` escape. */
        const val U_PLAIN = "${BS}u0070lain-key-without-a-known-shape-123"

        /** `{"q":` [SLASHED] `}` with its first letter as a `\u` escape. */
        const val U_SLASHED = "{\"q\":\"${BS}u0073lash/key-without-shape-789\"}"

        /** Each key with the escaped spelling that hides it from the windows. */
        val ESCAPED = listOf(PLAIN to U_PLAIN, SLASHED to "slash$BS/key-without-shape-789")

        val CALL = ToolCallId("toolu_1")
        val STARTED = Delta.ToolCallStarted(CALL, "get_weather")

        val MESSAGE_START =
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""

        fun start(
            index: Int,
            block: String,
        ) = "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":$block}"""

        fun delta(
            index: Int,
            delta: String,
        ) = "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":$delta}"""

        fun stop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

        /** An `input_json_delta` carrying [text] (raw argument text). */
        fun args(text: String) = """{"type":"input_json_delta","partial_json":${JsonPrimitive(text)}}"""

        /** The start of call [CALL] with [input]. */
        fun tool(input: String) = """{"type":"tool_use","id":"toolu_1","name":"get_weather","input":$input}"""

        /** Call [CALL] at index 0 whose arguments stream as [fragments]. */
        fun call(vararg fragments: String): List<Pair<String, String>> =
            listOf(start(0, tool("{}"))) + fragments.map { delta(0, args(it)) } + stop(0)

        fun text(
            index: Int,
            text: String,
        ): List<Pair<String, String>> =
            listOf(
                start(index, """{"type":"text","text":""}"""),
                delta(index, """{"type":"text_delta","text":"$text"}"""),
                stop(index),
            )

        fun body(
            blocks: List<Pair<String, String>>,
            reason: String = "end_turn",
        ): String =
            Kit.sse(
                MESSAGE_START,
                *blocks.toTypedArray(),
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":1}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )
    }
}
