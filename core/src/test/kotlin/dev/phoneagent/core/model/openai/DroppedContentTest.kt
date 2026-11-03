package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The exit principle (model-layer §2): model content that reaches an exit (a delta, the reply's
 * parts, the log, a diagnostic, the next request) is checked for request secrets; content the
 * adapters drop before any exit need not be. Each path below drops what it carries, and the test
 * proves it: the key, as sent or behind an escape (`~` stands for a backslash), reaches no delta,
 * no part and nothing of the reply serialized as the log stores it. The one path whose content does
 * reach an exit (argument deltas the final output replaces) is checked like any shown arguments.
 * Assertions never print the key.
 */
class DroppedContentTest {
    @StartStop
    private val server = Kit.server()

    /** How the key is spelled in the dropped content: [written], `~` for a backslash. */
    enum class Spelling(
        val written: String,
    ) {
        AS_SENT(ECHOED),
        ESCAPED("~u0070lain-placeholder-zq7w-0001"),
        ;

        val wire: String get() = written.replace('~', Char(92))
    }

    private fun model(protocol: ModelProtocol): ChatModel {
        val profile = Kit.profile(server.base(), protocol = protocol)
        val wire = WireOptions.defaultsFor(profile)
        return Kit.adapters(Keys(ECHOED)).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), wire)
    }

    /** Runs [model] and checks that no exit holds a piece of [ECHOED]; returns the reply, `null` when it failed. */
    private suspend fun assertNoExit(model: ChatModel): ChatResponse? {
        val deltas = Deltas()
        val reply =
            try {
                model.send(Kit.request(), deltas)
            } catch (rejected: ModelError) {
                // A stricter adapter may reject what it would drop: the error is an exit too.
                assertNoPiece("the error", rejected.stackTraceToString())
                null
            }
        assertNoPiece("a delta", deltas.all.toString())
        reply?.let {
            assertNoPiece("the parts", it.parts.toString())
            assertNoPiece("the logged reply", Json.encodeToString(ChatResponse.serializer(), it))
        }
        return reply
    }

    private fun args(value: String) = JsonPrimitive("""{"city":"$value"}""")

    private fun chatDelta(delta: String) = """{"choices":[{"index":0,"delta":$delta}]}"""

    private fun chatEnd(reason: String) = """{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""

    private fun responses(
        vararg events: String,
        output: String,
    ): String =
        (events.toList() + """{"type":"response.completed","response":{"status":"completed","output":$output}}""")
            .joinToString("") { "data: $it\n\n" }

    private fun call(arguments: String) =
        """{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather","arguments":${JsonPrimitive(
            arguments,
        )}}"""

    private val message = """{"type":"message","content":[{"type":"output_text","text":"Sunny."}]}"""

    private fun secondChoice(spelling: Spelling) =
        """{"index":1,"delta":{"tool_calls":[{"index":0,"id":"call_9",""" +
            """"function":{"name":"get_weather","arguments":${args(spelling.wire)}}}]}}"""

    @Test
    fun `the escaped spelling is the key once decoded`() {
        assertEquals(ECHOED, JsonEscapes.lenientDecode(Spelling.ESCAPED.wire))
    }

    @ParameterizedTest(name = "Chat second choice: {0}")
    @EnumSource(Spelling::class)
    fun `a call in a second choice is dropped`(spelling: Spelling) =
        runTest {
            val chunk = """{"choices":[{"index":0,"delta":{"content":"Sunny."}},${secondChoice(spelling)}]}"""
            server.enqueueSse(Kit.sse(chunk, chatEnd("stop")))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
            assertEquals(listOf(Part.Text("Sunny.")), reply?.parts, "only the first choice of a chunk is read")
        }

    @ParameterizedTest(name = "Chat chunk led by a second choice: {0}")
    @EnumSource(Spelling::class)
    fun `a chunk whose first choice is not index 0 reaches the reply and is checked`(spelling: Spelling) =
        runTest {
            val led = """{"choices":[${secondChoice(spelling)}]}"""
            server.enqueueSse(Kit.sse(chatDelta("""{"content":"Sunny."}"""), led))
            val deltas = Deltas()
            // Any type: a failure message must not print an error before it is checked.
            val model = model(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
            val error = assertThrows<Throwable> { model.send(Kit.request(), deltas) }
            assertNoPiece("the error", error.stackTraceToString())
            assertTrue(
                error is ModelError.BadResponse && error.discardsStreamed,
                "marked echo rejection",
            )
        }

    @ParameterizedTest(name = "Chat legacy function_call: {0}")
    @EnumSource(Spelling::class)
    fun `a legacy function_call is dropped`(spelling: Spelling) =
        runTest {
            val function = """{"name":"get_weather","arguments":${args(spelling.wire)}}"""
            val legacy = """{"content":"Sunny.","function_call":$function}"""
            server.enqueueSse(Kit.sse(chatDelta(legacy), chatEnd("stop")))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
            assertEquals(listOf(Part.Text("Sunny.")), reply?.parts, "the request sent no functions")
        }

    @ParameterizedTest(name = "Responses web_search_call and custom_tool_call: {0}")
    @EnumSource(Spelling::class)
    fun `output items of unknown types are dropped`(spelling: Spelling) =
        runTest {
            val value = JsonPrimitive(spelling.wire)
            val search = """{"type":"web_search_call","id":"ws_1","action":{"type":"search","query":$value}}"""
            val custom = """{"type":"custom_tool_call","id":"ct_1","call_id":"c9","name":"run","input":$value}"""
            val done = """{"type":"response.output_item.done","item":$search}"""
            server.enqueueSse(responses(done, output = "[$search,$custom,$message]"))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_RESPONSES))
            assertEquals(listOf(Part.Text("Sunny.")), reply?.parts)
        }

    @ParameterizedTest(name = "Responses added item arguments: {0}")
    @EnumSource(Spelling::class)
    fun `arguments in an added item are dropped`(spelling: Spelling) =
        runTest {
            val added =
                """{"type":"response.output_item.added","output_index":0,"item":""" +
                    """{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather","arguments":${args(
                        spelling.wire,
                    )}}}"""
            server.enqueueSse(responses(added, output = "[${call("""{"city":"Paris"}""")}]"))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_RESPONSES))
            assertEquals(listOf("""{"city":"Paris"}"""), reply?.toolCalls?.map { it.argsJson })
        }

    @ParameterizedTest(name = "Responses arguments done event: {0}")
    @EnumSource(Spelling::class)
    fun `arguments in a function_call_arguments done event are dropped`(spelling: Spelling) =
        runTest {
            val done =
                """{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,""" +
                    """"arguments":${args(spelling.wire)}}"""
            server.enqueueSse(responses(done, output = "[${call("""{"city":"Paris"}""")}]"))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_RESPONSES))
            assertEquals(listOf("""{"city":"Paris"}"""), reply?.toolCalls?.map { it.argsJson })
        }

    @ParameterizedTest(name = "Responses argument delta of an unknown call: {0}")
    @EnumSource(Spelling::class)
    fun `an argument delta for a call never announced is dropped`(spelling: Spelling) =
        runTest {
            val value = args(spelling.wire)
            val delta = """{"type":"response.function_call_arguments.delta","item_id":"fc_9","delta":$value}"""
            server.enqueueSse(responses(delta, output = "[$message]"))
            val reply = assertNoExit(model(ModelProtocol.OPENAI_RESPONSES))
            assertEquals(listOf(Part.Text("Sunny.")), reply?.parts)
        }

    @ParameterizedTest(name = "Responses argument deltas the final output replaces: {0}")
    @EnumSource(Spelling::class)
    fun `argument deltas the final output replaces reached an exit and are checked`(spelling: Spelling) =
        runTest {
            val added =
                """{"type":"response.output_item.added","output_index":0,"item":""" +
                    """{"type":"function_call","id":"fc_1","call_id":"c1","name":"get_weather","arguments":""}}"""
            val value = args(spelling.wire)
            val delta = """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":$value}"""
            server.enqueueSse(responses(added, delta, output = "[${call("""{"city":"Paris"}""")}]"))
            val deltas = Deltas()
            // Any type: a failure message must not print an error before it is checked.
            val model = model(ModelProtocol.OPENAI_RESPONSES)
            val error = assertThrows<Throwable> { model.send(Kit.request(), deltas) }
            assertNoPiece("the error", error.stackTraceToString())
            assertTrue(
                error is ModelError.BadResponse && error.discardsStreamed,
                "marked echo rejection",
            )
            val shown = deltas.all.filterIsInstance<Delta.ToolCallArgs>().joinToString("") { it.fragment }
            assertEquals(spelling == Spelling.ESCAPED, shown.isNotEmpty(), "only the escape got past the window")
        }

    private companion object {
        const val ECHOED = "plain-placeholder-zq7w-0001"
        const val MIN_PIECE = 6

        /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [ECHOED]; never prints it. */
        fun assertNoPiece(
            what: String,
            text: String,
        ) {
            val leaked = (0..ECHOED.length - MIN_PIECE).any { text.contains(ECHOED.substring(it, it + MIN_PIECE)) }
            assertFalse(leaked, "$what holds a piece of the key (value not shown)")
        }
    }
}
