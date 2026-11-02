package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A successful reply that echoes the request's key (model-layer §2): text and arguments are masked
 * also when the key is split across fragments, and what the deltas showed is exactly the masked
 * reply; a key inside a block that is echoed verbatim (thinking text or signature, redacted
 * thinking, compaction) rejects the whole reply with a fixed message, before any preview shows it;
 * a refusal category is masked. The key has no credential shape, so only the request-secret layer
 * can catch it.
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
    ): ChatResponse {
        server.enqueueSse(stream)
        return Kit.model(server, credentials = Keys(key)).complete(Kit.request(), deltas)
    }

    /** No piece of the key at least [PIECE] characters long is in [text]. */
    private fun assertNoPiece(
        text: String,
        what: String,
    ) {
        val pieces = key.windowed(PIECE).filter { it in text }
        assertTrue(pieces.isEmpty(), "$what shows part of the key: $pieces")
    }

    private fun assertShownIsFinal(
        reply: ChatResponse,
        deltas: Deltas,
    ) {
        val printed = Json.encodeToString(ChatResponse.serializer(), reply) + deltas.all
        assertNoPiece(printed, "the reply or its deltas")
        assertEquals(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, deltas.text)
        reply.toolCalls.forEach { assertEquals(it.argsJson, deltas.args(it.id), "arguments of ${it.id}") }
    }

    private fun assertRejected(
        stream: String,
        deltas: Deltas = Deltas(),
    ) = runTest {
        val error = assertThrows<ModelError.BadResponse> { complete(stream, deltas) }
        assertEquals("bad response: $ECHO_REJECTED", error.message)
        assertNoPiece(error.stackTraceToString(), "the error")
        assertNoPiece(deltas.all.toString(), "the deltas")
    }

    @Test
    fun `text that echoes the key whole is masked in the reply and in its deltas`() =
        runTest {
            val deltas = Deltas()
            val reply = complete(body(text(0, "你的密钥是 $key 。")), deltas)
            assertShownIsFinal(reply, deltas)
            assertEquals(listOf<Part>(Part.Text("你的密钥是 ${Redactor.MASK} 。")), reply.parts)
        }

    @Test
    fun `text that echoes the key across fragments is masked the same way`() =
        runTest {
            val deltas = Deltas()
            val stream = body(text(0, "前 $head", tail.take(SPLIT), "${tail.drop(SPLIT)} 后"))
            val reply = complete(stream, deltas)
            assertShownIsFinal(reply, deltas)
            assertEquals(listOf<Part>(Part.Text("前 ${Redactor.MASK} 后")), reply.parts)
        }

    @Test
    fun `a fragment that only looks like the start of the key is shown once the text moves on`() =
        runTest {
            val deltas = Deltas()
            val reply = complete(body(text(0, "x $head", " y")), deltas)
            assertEquals(listOf<Part>(Part.Text("x $head y")), reply.parts)
            assertEquals(listOf<Delta>(Delta.Text("x "), Delta.Text("$head y")), deltas.all, "held back, then shown")
        }

    @Test
    fun `tool arguments that echo the key across fragments are masked`() =
        runTest {
            val deltas = Deltas()
            val call =
                listOf(
                    start(0, """{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}"""),
                    delta(0, """{"type":"input_json_delta","partial_json":"{\"city\":\"$head"}"""),
                    delta(0, """{"type":"input_json_delta","partial_json":"$tail\"}"}"""),
                    stop(0),
                )
            val reply = complete(body(call, reason = "tool_use"), deltas)
            assertShownIsFinal(reply, deltas)
            assertEquals("""{"city":"${Redactor.MASK}"}""", reply.toolCalls.single().argsJson)
        }

    @Test
    fun `tool input that came whole with the key is masked`() =
        runTest {
            val deltas = Deltas()
            val whole = """{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"$key"}}"""
            val reply = complete(body(listOf(start(0, whole), stop(0)), reason = "tool_use"), deltas)
            assertShownIsFinal(reply, deltas)
            assertEquals("""{"city":"${Redactor.MASK}"}""", reply.toolCalls.single().argsJson)
        }

    @Test
    fun `thinking that echoes the key rejects the reply before the preview shows any of it`() {
        val deltas = Deltas()
        val thinking =
            listOf(
                start(0, """{"type":"thinking","thinking":""}"""),
                delta(0, """{"type":"thinking_delta","thinking":"先想想 $head"}"""),
                delta(0, """{"type":"thinking_delta","thinking":"$tail 再说"}"""),
                delta(0, """{"type":"signature_delta","signature":"c2lnbmF0dXJlLW5vdC1yZWFs"}"""),
                stop(0),
            )
        assertRejected(body(thinking + text(1, "好的。")), deltas)
        assertEquals(listOf<Delta>(Delta.Reasoning("先想想 ")), deltas.all, "only text before the key was shown")
    }

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
    fun `a declined attempt's thinking that carries the key still rejects the reply`() =
        assertRejected(
            body(
                listOf(
                    start(0, """{"type":"thinking","thinking":"","signature":"c2ln$key"}"""),
                    stop(0),
                    start(1, """{"type":"fallback","from":{"model":"a"},"to":{"model":"b"}}"""),
                    stop(1),
                ) + text(2, "好。"),
            ),
        )

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

        /** The fixed message of a reply rejected for an echoed key (no part of the reply in it). */
        const val ECHO_REJECTED = "the reply repeats this request's credential in a block that must be echoed verbatim"

        val MESSAGE_START =
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","content":[]}}"""
        val MESSAGE_STOP = "message_stop" to """{"type":"message_stop"}"""

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
