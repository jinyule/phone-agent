package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A reply whose model-produced content holds the request's key (model-layer §2) is rejected whole
 * with a fixed [ModelError.BadResponse], never rewritten: text, tool calls, thinking text and
 * signature, redacted thinking, compaction and blocks this adapter does not know, a declined
 * attempt's blocks too, the key whole, split across fragments or JSON-escaped. Deltas are not held
 * back: what came before the fragment that completes the key was shown, that fragment is not. A
 * refusal category is the server's text and is masked; so is metadata, where a field masking would
 * change is left out. The key has no credential shape, so only the request-secret layer can catch it.
 */
class EchoedSecretTest {
    @StartStop
    private val server = MockWebServer()

    private val key = "plain-key-without-a-known-shape-123"
    private val head = key.take(SPLIT)
    private val tail = key.drop(SPLIT)

    private suspend fun complete(
        stream: String,
        deltas: Deltas = Deltas(),
        secret: String = key,
    ): ChatResponse {
        server.enqueueSse(stream)
        return Kit.model(server, credentials = Keys(secret)).complete(Kit.request(), deltas)
    }

    /** No piece of [secret] at least [PIECE] characters long is in [text]. */
    private fun assertNoPiece(
        text: String,
        what: String,
        secret: String = key,
    ) {
        val pieces = secret.windowed(PIECE).filter { it in text }
        assertTrue(pieces.isEmpty(), "$what shows part of the key: $pieces")
    }

    /** The reply is refused with the fixed text; [shown] is exactly what the deltas had shown by then. */
    private fun assertRejected(
        stream: String,
        shown: List<Delta> = emptyList(),
        secret: String = key,
    ) = runTest {
        val deltas = Deltas()
        val error = assertThrows<ModelError.BadResponse> { complete(stream, deltas, secret) }
        assertEquals("bad response: $DETAIL", error.message)
        assertNoPiece(error.stackTraceToString(), "the error", secret)
        assertEquals(shown, deltas.all, "shown before the key was complete")
        assertFalse(deltas.all.toString().contains(secret), "a delta showed the whole key")
    }

    @Test
    fun `text that echoes the key whole rejects the reply and the fragment is not shown`() =
        assertRejected(body(text(0, "好的，", "你的密钥是 $key 。")), shown = listOf(Delta.Text("好的，")))

    @Test
    fun `text that echoes the key across fragments is shown until the fragment that completes it`() =
        assertRejected(
            body(text(0, "前 $head", tail.take(SPLIT), "${tail.drop(SPLIT)} 后")),
            shown = listOf(Delta.Text("前 $head"), Delta.Text(tail.take(SPLIT))),
        )

    @Test
    fun `a key split between a block's start and its first delta rejects the reply`() =
        assertRejected(
            body(
                listOf(
                    start(0, """{"type":"text","text":"前 $head"}"""),
                    delta(0, """{"type":"text_delta","text":"$tail 后"}"""),
                    stop(0),
                ),
            ),
            shown = listOf(Delta.Text("前 $head")),
        )

    @Test
    fun `a fragment that only looks like the start of the key is shown at once and kept`() =
        runTest {
            val deltas = Deltas()
            val reply = complete(body(text(0, "x $head", " y")), deltas)
            assertEquals(listOf<Part>(Part.Text("x $head y")), reply.parts, "content is never rewritten")
            assertEquals(listOf<Delta>(Delta.Text("x $head"), Delta.Text(" y")), deltas.all, "nothing held back")
        }

    @Test
    fun `tool arguments that echo the key across fragments reject the reply`() =
        assertRejected(
            body(
                call(
                    """{"type":"input_json_delta","partial_json":"{\"city\":\"$head"}""",
                    """{"type":"input_json_delta","partial_json":"$tail\"}"}""",
                ),
                reason = "tool_use",
            ),
            shown = listOf(Delta.ToolCallStarted(CALL, "get_weather"), Delta.ToolCallArgs(CALL, "{\"city\":\"$head")),
        )

    @Test
    fun `tool input that came whole with the key rejects the reply before the call is announced`() =
        assertRejected(
            body(listOf(start(0, tool("""{"city":"$key"}""")), stop(0)), reason = "tool_use"),
        )

    @Test
    fun `a key in a tool input key or in the call name rejects the reply`() {
        assertRejected(body(listOf(start(0, tool("{}", name = "get_$key")), stop(0)), reason = "tool_use"))
        assertRejected(body(listOf(start(0, tool("""{"$key":1}""")), stop(0)), reason = "tool_use"))
    }

    @Test
    fun `arguments that carry the JSON-escaped form of a key reject the reply`() {
        val quoted = "quote\"key-without-shape-456"
        val escaped = "quote\\\"key-without-shape-456"
        val first = escaped.take(SPLIT)
        assertRejected(
            body(
                call(
                    """{"type":"input_json_delta","partial_json":${json("{\"q\":\"$first")}}""",
                    """{"type":"input_json_delta","partial_json":${json("${escaped.drop(SPLIT)}\"}")}}""",
                ),
                reason = "tool_use",
            ),
            shown = listOf(Delta.ToolCallStarted(CALL, "get_weather"), Delta.ToolCallArgs(CALL, "{\"q\":\"$first")),
            secret = quoted,
        )
    }

    @Test
    fun `arguments that hide a key behind other JSON escapes are checked once more as parsed`() {
        val slashed = "slash/key-without-shape-789"
        val args = """{"q":"slash\/key-without-shape-789"}"""
        assertRejected(
            body(call("""{"type":"input_json_delta","partial_json":${json(args)}}"""), reason = "tool_use"),
            shown = listOf(Delta.ToolCallStarted(CALL, "get_weather"), Delta.ToolCallArgs(CALL, args)),
            secret = slashed,
        )
    }

    @Test
    fun `thinking that echoes the key across fragments rejects the reply`() =
        assertRejected(
            body(
                listOf(
                    start(0, """{"type":"thinking","thinking":""}"""),
                    delta(0, """{"type":"thinking_delta","thinking":"先想想 $head"}"""),
                    delta(0, """{"type":"thinking_delta","thinking":"$tail 再说"}"""),
                    delta(0, """{"type":"signature_delta","signature":"c2lnbmF0dXJlLW5vdC1yZWFs"}"""),
                    stop(0),
                ) + text(1, "好的。"),
            ),
            shown = listOf(Delta.Reasoning("先想想 $head")),
        )

    @Test
    fun `a signature that carries the key rejects the reply`() =
        assertRejected(
            body(
                listOf(
                    start(0, """{"type":"thinking","thinking":"想好了。"}"""),
                    delta(0, """{"type":"signature_delta","signature":"c2ln${key}bmF0"}"""),
                    stop(0),
                ) + text(1, "好的。"),
            ),
            shown = listOf(Delta.Reasoning("想好了。")),
        )

    @Test
    fun `redacted thinking that carries the key rejects the reply`() =
        assertRejected(
            body(listOf(start(0, """{"type":"redacted_thinking","data":"ZGF0$key"}"""), stop(0)) + text(1, "好。")),
        )

    @Test
    fun `a compaction block that carries the key rejects the reply`() =
        assertRejected(
            body(
                listOf(
                    start(0, """{"type":"compaction","content":null}"""),
                    delta(0, """{"type":"compaction_delta","content":"摘要：$key"}"""),
                    stop(0),
                ) + text(1, "好。"),
            ),
        )

    @Test
    fun `a declined attempt's blocks that carry the key still reject the reply`() {
        val fallback = listOf(start(1, """{"type":"fallback","from":{"model":"a"},"to":{"model":"b"}}"""), stop(1))
        val signed = listOf(start(0, """{"type":"thinking","thinking":"","signature":"c2ln$key"}"""), stop(0))
        assertRejected(body(signed + fallback + text(2, "好。")))
        val serverTool =
            listOf(
                start(0, """{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}"""),
                delta(0, """{"type":"input_json_delta","partial_json":"{\"query\":\"$head"}"""),
                delta(0, """{"type":"input_json_delta","partial_json":"$tail\"}"}"""),
                stop(0),
            )
        assertRejected(body(serverTool + fallback + text(2, "好。")))
    }

    @Test
    fun `a refusal category that echoes the key is masked in the message, toString and stack`() =
        runTest {
            val stream =
                Kit.sse(
                    MESSAGE_START,
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"refusal",""" +
                        """"stop_details":{"type":"refusal","category":"cyber $key"}}}""",
                    MESSAGE_STOP,
                )
            val error = assertThrows<ModelError.Refusal> { complete(stream) }
            for (printed in listOf(error.message.orEmpty(), error.toString(), error.stackTraceToString())) {
                assertFalse(printed.contains(head), "the key leaked")
                assertTrue(printed.contains("cyber ${Redactor.MASK}"), printed)
            }
            assertEquals("cyber ${Redactor.MASK}", error.category)
        }

    private companion object {
        /** Where the key is split between fragments. */
        const val SPLIT = 10

        /** Shortest piece of the key that counts as a leak. */
        const val PIECE = 6

        /** The fixed detail of a reply rejected for an echoed key (no part of the reply in it). */
        const val DETAIL = "the reply contained a credential this request sent and was discarded"

        val CALL = ToolCallId("toolu_1")

        val MESSAGE_START =
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""
        val MESSAGE_STOP = "message_stop" to """{"type":"message_stop"}"""

        /** [text] as a JSON string literal. */
        fun json(text: String): String = JsonPrimitive(text).toString()

        fun start(
            index: Int,
            block: String,
        ) = "content_block_start" to """{"type":"content_block_start","index":$index,"content_block":$block}"""

        fun delta(
            index: Int,
            delta: String,
        ) = "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":$delta}"""

        fun stop(index: Int) = "content_block_stop" to """{"type":"content_block_stop","index":$index}"""

        /** A text block at [index] streamed as [fragments]. */
        fun text(
            index: Int,
            vararg fragments: String,
        ): List<Pair<String, String>> =
            listOf(start(index, """{"type":"text","text":""}""")) +
                fragments.map { delta(index, """{"type":"text_delta","text":"$it"}""") } +
                stop(index)

        /** The start of tool call [CALL] with [input]. */
        fun tool(
            input: String,
            name: String = "get_weather",
        ) = """{"type":"tool_use","id":"toolu_1","name":"$name","input":$input}"""

        /** Tool call [CALL] at index 0 streamed as [deltas]. */
        fun call(vararg deltas: String): List<Pair<String, String>> =
            listOf(start(0, tool("{}"))) +
                deltas.map { delta(0, it) } +
                stop(0)

        fun body(
            blocks: List<Pair<String, String>>,
            reason: String = "end_turn",
        ): String =
            Kit.sse(
                MESSAGE_START,
                *blocks.toTypedArray(),
                "message_delta" to
                    """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":1}}""",
                MESSAGE_STOP,
            )
    }
}
