package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * Call arguments that are not JSON (cut at the output limit, or broken) still hide no secret
 * behind an escape (`~u0070` for `p`, `~/` for `/`; `~` stands for a backslash here): the whole
 * argument text is checked as sent, rewritten in the standard escaping when it parses, and
 * leniently decoded ([JsonEscapes.lenientDecode]), so the reply is rejected with the "streamed
 * output is not kept" marker. The same check runs over the arguments already shown when a stream
 * fails before its end, so the failure tells the UI to take them back. Assertions never print a
 * secret.
 */
class EscapedArgumentEchoTest {
    @StartStop
    private val server = Kit.server()

    /** How a secret is spelled in the arguments: [written], with `~` for a backslash, is [key] after decoding. */
    enum class Style(
        val key: String,
        val written: String,
        /** Where [written] is cut to split its escape over two fragments. */
        val inEscape: Int,
    ) {
        UNICODE("plain-placeholder-lenient-0001", "~u0070lain-placeholder-lenient-0001", 3),
        SLASH("slash/placeholder-lenient-0002", "slash~/placeholder-lenient-0002", 6),
        ;

        val wire: String get() = written.replace('~', BACKSLASH)
    }

    enum class Ending(
        val chatReason: String,
        val stop: StopReason,
    ) {
        /** Cut at the output limit: the string and the object never close. */
        TRUNCATED("length", StopReason.MAX_TOKENS),

        /** One brace too many: not JSON, yet a normal end with a call. */
        INVALID("tool_calls", StopReason.TOOL_USE),
        ;

        fun args(value: String): String =
            when (this) {
                TRUNCATED -> """{"city":"$value"""
                INVALID -> """{"city":"$value"}}"""
            }
    }

    private fun model(
        protocol: ModelProtocol,
        key: String,
    ): ChatModel {
        val profile = Kit.profile(server.base(), protocol = protocol)
        return Kit
            .adapters(
                Keys(key),
            ).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions.defaultsFor(profile))
    }

    /** Expects the marked echo rejection; no piece of [key] may reach the error. */
    private suspend fun assertRejected(
        model: ChatModel,
        key: String,
        deltas: Deltas = Deltas(),
    ) {
        // Any type: a failure message must not print an error before it is checked.
        val error = assertThrows<Throwable> { model.complete(Kit.request(), deltas) }
        assertNoPiece("the error", error.stackTraceToString(), key)
        assertTrue(
            error is ModelError.BadResponse && CredentialEcho.discardsStreamed(error),
            "the marked echo rejection",
        )
        assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message)
    }

    private fun chatChunk(
        fragment: String,
        first: Boolean,
        tool: String,
    ): String {
        val head = if (first) """"id":"call_1","function":{"name":"$tool",""" else """"function":{"""
        return """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,$head"arguments":${JsonPrimitive(
            fragment,
        )}}}]}}]}"""
    }

    private fun chatEnd(reason: String) = """{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""

    /** A Chat stream whose call arguments come in [fragments]; ends with [reason] and `[DONE]` unless `null`. */
    private fun chatStream(
        fragments: List<String>,
        reason: String?,
        tool: String = "get_weather",
    ): String {
        val chunks = fragments.mapIndexed { i, fragment -> chatChunk(fragment, first = i == 0, tool) }
        return if (reason ==
            null
        ) {
            Kit.sse(*chunks.toTypedArray(), done = false)
        } else {
            Kit.sse(*(chunks + chatEnd(reason)).toTypedArray())
        }
    }

    private fun chatJson(
        args: String,
        reason: String,
    ): String =
        """{"choices":[{"index":0,"message":{"tool_calls":[{"id":"call_1","type":"function",""" +
            """"function":{"name":"get_weather","arguments":${JsonPrimitive(args)}}}]},"finish_reason":"$reason"}]}"""

    private fun responsesObject(
        args: String,
        ending: Ending,
    ): String {
        val call = """{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather","arguments":${JsonPrimitive(
            args,
        )}}"""
        val state =
            when (ending) {
                Ending.TRUNCATED -> """"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}"""
                Ending.INVALID -> """"status":"completed""""
            }
        return """{$state,"output":[$call]}"""
    }

    private fun responsesEvents(fragments: List<String>): List<String> =
        listOf(
            """{"type":"response.output_item.added","output_index":0,""" +
                """"item":{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather","arguments":""}}""",
        ) +
            fragments.map { fragment ->
                val delta = JsonPrimitive(fragment)
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":$delta}"""
            }

    /** A Responses stream of [fragments], then [ending]'s terminal event with all of them, unless `null`. */
    private fun responsesStream(
        fragments: List<String>,
        ending: Ending?,
    ): String {
        val terminal =
            ending?.let {
                val type = if (it == Ending.TRUNCATED) "response.incomplete" else "response.completed"
                """{"type":"$type","response":${responsesObject(fragments.joinToString(""), it)}}"""
            }
        return (responsesEvents(fragments) + listOfNotNull(terminal)).joinToString("") { "data: $it\n\n" }
    }

    private fun enqueueJson(body: String) =
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )

    @ParameterizedTest(name = "not JSON: {0} {1}, {2}, {3}")
    @CsvSource(
        "OPENAI_CHAT_COMPLETIONS, stream, TRUNCATED, UNICODE",
        "OPENAI_CHAT_COMPLETIONS, stream, TRUNCATED, SLASH",
        "OPENAI_CHAT_COMPLETIONS, stream, INVALID, UNICODE",
        "OPENAI_CHAT_COMPLETIONS, stream, INVALID, SLASH",
        "OPENAI_CHAT_COMPLETIONS, json, TRUNCATED, UNICODE",
        "OPENAI_CHAT_COMPLETIONS, json, INVALID, SLASH",
        "OPENAI_RESPONSES, stream, TRUNCATED, UNICODE",
        "OPENAI_RESPONSES, stream, TRUNCATED, SLASH",
        "OPENAI_RESPONSES, stream, INVALID, UNICODE",
        "OPENAI_RESPONSES, stream, INVALID, SLASH",
        "OPENAI_RESPONSES, json, TRUNCATED, SLASH",
        "OPENAI_RESPONSES, json, INVALID, UNICODE",
    )
    fun `arguments that are not JSON hiding the key behind an escape are rejected`(
        protocol: ModelProtocol,
        framing: String,
        ending: Ending,
        style: Style,
    ) = runTest {
        val args = ending.args(style.wire)
        val chat = protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS
        when {
            framing == "json" && chat -> enqueueJson(chatJson(args, ending.chatReason))
            framing == "json" -> enqueueJson(responsesObject(args, ending))
            chat -> server.enqueueSse(chatStream(listOf(args), ending.chatReason))
            else -> server.enqueueSse(responsesStream(listOf(args), ending))
        }
        assertRejected(model(protocol, style.key), style.key)
    }

    @ParameterizedTest(name = "split escape: {0}, {1}")
    @CsvSource(
        "OPENAI_CHAT_COMPLETIONS, UNICODE",
        "OPENAI_CHAT_COMPLETIONS, SLASH",
        "OPENAI_RESPONSES, UNICODE",
        "OPENAI_RESPONSES, SLASH",
    )
    fun `an escape split over two argument fragments is rejected`(
        protocol: ModelProtocol,
        style: Style,
    ) = runTest {
        val args = Ending.TRUNCATED.args(style.wire)
        val cut = args.indexOf(style.wire) + style.inEscape
        val fragments = listOf(args.take(cut), args.drop(cut))
        if (protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS) {
            server.enqueueSse(chatStream(fragments, Ending.TRUNCATED.chatReason))
        } else {
            server.enqueueSse(responsesStream(fragments, Ending.TRUNCATED))
        }
        val deltas = Deltas()
        assertRejected(model(protocol, style.key), style.key, deltas)
        val shown = deltas.all.filterIsInstance<dev.phoneagent.core.model.Delta.ToolCallArgs>().map { it.fragment }
        assertEquals(fragments, shown, "both fragments were shown as they came (the UI takes them back)")
    }

    @ParameterizedTest(name = "failed stream: {0} {1}, {2}")
    @CsvSource(
        "OPENAI_CHAT_COMPLETIONS, cut, UNICODE",
        "OPENAI_CHAT_COMPLETIONS, cut, SLASH",
        "OPENAI_CHAT_COMPLETIONS, broken, UNICODE",
        "OPENAI_RESPONSES, cut, UNICODE",
        "OPENAI_RESPONSES, cut, SLASH",
        "OPENAI_RESPONSES, broken, SLASH",
    )
    fun `a stream that fails after showing such arguments fails as the echo, not as its own error`(
        protocol: ModelProtocol,
        failure: String,
        style: Style,
    ) = runTest {
        val args = """{"city":"${style.wire}"}"""
        val chat = protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS
        val body = if (chat) chatStream(listOf(args), reason = null) else responsesStream(listOf(args), ending = null)
        server.enqueueSse(if (failure == "broken") body + "data: {not json\n\n" else body)
        val deltas = Deltas()
        assertRejected(model(protocol, style.key), style.key, deltas)
        assertEquals(
            args,
            deltas.all.filterIsInstance<dev.phoneagent.core.model.Delta.ToolCallArgs>().joinToString(
                "",
            ) {
                it.fragment
            },
        )
    }

    @ParameterizedTest(name = "trial: {0}")
    @EnumSource(Style::class)
    fun `a trial call with such arguments quotes no piece of the key`(style: Style) =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(
                chatStream(listOf("""{"token":"pa-1","note":"${style.wire}"}}"""), "tool_calls", TRIAL_TOOL),
            )
            val result =
                Kit
                    .adapters(
                        Keys(style.key),
                    ).probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "pa-1")
            assertNoPiece("the result", result.toString(), style.key)
            assertEquals(
                ModelErrorKind.BAD_RESPONSE,
                (result as? ProbeResult.Failed)?.kind,
                "the trial reply is rejected",
            )
        }

    @ParameterizedTest(name = "JSON: {0}")
    @EnumSource(Style::class)
    fun `arguments that are JSON hiding the key behind an escape are still rejected`(style: Style) =
        runTest {
            val args = """{"city":"${style.wire}"}"""
            server.enqueueSse(chatStream(listOf(args), "tool_calls"))
            assertRejected(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS, style.key), style.key)
            server.enqueueSse(responsesStream(listOf(args), Ending.INVALID))
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES, style.key), style.key)
        }

    @ParameterizedTest(name = "one id for two Chat calls: {0}")
    @EnumSource(Style::class)
    fun `shown arguments that only hold the key across two calls of one id are rejected`(style: Style) =
        runTest {
            // Two calls, index 0 and 1, with the same id: their deltas add up under that id, the parts do not.
            val args = """{"city":"${style.wire}"}"""
            val cut = args.indexOf(style.wire) + style.inEscape + 2

            fun call(
                index: Int,
                fragment: String,
            ) = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":$index,"id":"call_1",""" +
                """"function":{"name":"get_weather","arguments":${JsonPrimitive(fragment)}}}]}}]}"""
            server.enqueueSse(Kit.sse(call(0, args.take(cut)), call(1, args.drop(cut)), chatEnd("tool_calls")))
            assertRejected(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS, style.key), style.key)
        }

    @ParameterizedTest(name = "spelling: {0}")
    @EnumSource(Style::class)
    fun `each spelling is its key once decoded`(style: Style) {
        assertEquals(style.key, JsonEscapes.lenientDecode(style.wire))
        assertFalse(style.wire.contains(style.key), "the key is not there as sent")
    }

    @Test
    fun `cut or broken arguments without the key are kept as they are`() =
        runTest {
            for (ending in Ending.entries) {
                val args = ending.args("Par~u0069s".replace('~', BACKSLASH))
                server.enqueueSse(chatStream(listOf(args), ending.chatReason))
                val reply = model(ModelProtocol.OPENAI_CHAT_COMPLETIONS, Style.UNICODE.key).complete(Kit.request())
                assertEquals(ending.stop, reply.stopReason)
                assertEquals(listOf(args), reply.toolCalls.map { it.argsJson }, "the arguments are not rewritten")
            }
        }

    private companion object {
        val BACKSLASH = Char(92)
        const val MIN_PIECE = 6
        const val TRIAL_TOOL = "confirm_connection"

        /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [key]; never prints it. */
        fun assertNoPiece(
            what: String,
            text: String,
            key: String,
        ) {
            val leaked = (0..key.length - MIN_PIECE).any { text.contains(key.substring(it, it + MIN_PIECE)) }
            assertFalse(leaked, "$what holds a piece of the key (value not shown)")
        }
    }
}
