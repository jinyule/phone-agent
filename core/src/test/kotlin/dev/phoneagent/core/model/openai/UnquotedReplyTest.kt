package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Diagnostics never quote what the model or the server sent (model-layer §2): an error message,
 * its cause chain and a [ProbeResult] say which field, event, item or step was wrong and how long
 * the content was, never the content. The secret is spelled four ways wherever a reply carries it
 * ([Spelling]); escapes in raw text a parser never read hide it from masking, so only not quoting
 * keeps it out. Text the server wrote to explain an error is the exception: a field of a JSON
 * error is quoted masked, and text that is not JSON is withheld whole when its lenient decoding
 * holds a secret. Assertions never print the secret.
 */
class UnquotedReplyTest {
    @StartStop
    private val server = Kit.server()

    /** The secret as it appears in the reply: the same characters for every reader that decodes JSON escapes once. */
    enum class Spelling(
        val text: String,
    ) {
        AS_SENT(ECHOED),

        /** The quote escaped, as JSON writes it. */
        STANDARD(ECHOED.replace("\"", "$BACKSLASH\"")),

        /** The first letter written as a `u` escape. */
        UNICODE("${BACKSLASH}u0070" + STANDARD.text.drop(1)),

        /** The slash escaped too. */
        SLASH(STANDARD.text.replace("/", "$BACKSLASH/")),
    }

    private fun model(
        protocol: ModelProtocol,
        credential: Credential = Kit.KEY,
    ): ChatModel {
        val profile = Kit.profile(server.base(), protocol = protocol, credential = credential)
        return Kit
            .adapters(Keys(ECHOED))
            .model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions.defaultsFor(profile))
    }

    private fun enqueueJson(body: String) =
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build(),
        )

    private fun enqueue(
        framing: Framing,
        data: String,
    ) = if (framing == Framing.STREAM) server.enqueueSse(Kit.sse(data)) else enqueueJson(data)

    enum class Framing { STREAM, JSON }

    /** The error of [model]'s call, after checking that nothing it prints holds a piece of the secret. */
    private suspend fun failure(model: ChatModel): Throwable {
        // Any type: a failure message must not print an error before it is checked.
        val error = assertThrows<Throwable> { model.complete(Kit.request()) }
        assertNoPiece("the error", "${error.message} $error ${error.stackTraceToString()}")
        return error
    }

    private suspend fun probe(
        protocol: ModelProtocol,
        trialReply: () -> Unit,
    ): ProbeResult {
        server.enqueueError(404, "{}")
        trialReply()
        val result =
            Kit
                .adapters(Keys(ECHOED))
                .probe()
                .testAndSave(Kit.profile(server.base(), protocol = protocol), Kit.MODEL, Kit.capabilities(), TOKEN)
        assertNoPiece("the probe result", result.toString())
        return result
    }

    @ParameterizedTest(name = "spelling: {0}")
    @EnumSource(Spelling::class)
    fun `each spelling is the secret once decoded`(spelling: Spelling) {
        assertEquals(ECHOED, JsonEscapes.lenientDecode(spelling.text))
        if (spelling != Spelling.AS_SENT) assertFalse(spelling.text.contains(ECHOED), "the secret is not there as sent")
    }

    @ParameterizedTest(name = "broken Chat line: {0}")
    @EnumSource(Spelling::class)
    fun `a broken Chat data line quotes none of it`(spelling: Spelling) =
        runTest {
            for (framing in Framing.entries) {
                enqueue(framing, """{"choices":[{"index":0,"delta":{"content":"${spelling.text}"}}""")
                val error = failure(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
                assertTrue(error is ModelError.BadResponse && error.message.orEmpty().contains("not JSON"), "$framing")
            }
        }

    @ParameterizedTest(name = "broken Responses event: {0}")
    @EnumSource(Spelling::class)
    fun `a broken Responses event quotes none of it`(spelling: Spelling) =
        runTest {
            for (framing in Framing.entries) {
                enqueue(framing, """{"type":"response.output_text.delta","delta":"${spelling.text}"""")
                val error = failure(model(ModelProtocol.OPENAI_RESPONSES))
                assertTrue(error is ModelError.BadResponse && error.message.orEmpty().contains("not JSON"), "$framing")
            }
        }

    /** A Responses function_call item that lacks [missing] and carries [spelling] in its arguments. */
    private fun maimedCall(
        missing: String,
        spelling: Spelling,
        tool: String = "get_weather",
    ): String {
        val args = JsonPrimitive("""{"token":"$TOKEN","note":"${spelling.text}"}""")
        val id = if (missing == "call_id") "" else """"call_id":"c1","""
        val name = if (missing == "name") "" else """"name":"$tool","""
        return """{"type":"function_call","id":"fc_1",$id$name"arguments":$args}"""
    }

    private fun maimedStream(item: String): String =
        Kit.sse(
            """{"type":"response.output_item.added","output_index":0,"item":$item}""",
            """{"type":"response.completed","response":{"status":"completed","output":[$item]}}""",
            done = false,
        )

    @ParameterizedTest(name = "function_call without call_id or name: {0}")
    @EnumSource(Spelling::class)
    fun `a function_call without call_id or name quotes none of it`(spelling: Spelling) =
        runTest {
            for (missing in listOf("call_id", "name")) {
                for (framing in Framing.entries) {
                    val item = maimedCall(missing, spelling)
                    if (framing == Framing.STREAM) {
                        server.enqueueSse(maimedStream(item))
                    } else {
                        enqueueJson("""{"status":"completed","output":[$item]}""")
                    }
                    val error = failure(model(ModelProtocol.OPENAI_RESPONSES))
                    assertTrue(error is ModelError.BadResponse, "$missing $framing is rejected")
                    assertTrue(error.message.orEmpty().contains(missing), "$missing $framing names the missing field")
                }
            }
        }

    @ParameterizedTest(name = "trial call without call_id: {0}")
    @EnumSource(Spelling::class)
    fun `a trial reply whose call lacks its call_id quotes none of it`(spelling: Spelling) =
        runTest {
            val result =
                probe(ModelProtocol.OPENAI_RESPONSES) {
                    server.enqueueSse(maimedStream(maimedCall("call_id", spelling, TRIAL_TOOL)))
                }
            assertEquals(ModelErrorKind.BAD_RESPONSE, (result as? ProbeResult.Failed)?.kind, "the trial fails")
        }

    @ParameterizedTest(name = "unusable call_id: {0}")
    @EnumSource(Spelling::class)
    fun `an unusable call_id is not quoted`(spelling: Spelling) =
        runTest {
            val id = JsonPrimitive(spelling.text + CONTROL)
            enqueueJson(
                """{"status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":$id,""" +
                    """"name":"get_weather","arguments":"{}"}]}""",
            )
            assertTrue(failure(model(ModelProtocol.OPENAI_RESPONSES)) is ModelError.BadResponse)
        }

    @ParameterizedTest(name = "Chat call without name, unusable id: {0}")
    @EnumSource(Spelling::class)
    fun `a Chat call without a name or with an unusable id is not quoted`(spelling: Spelling) =
        runTest {
            val end = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
            // No name: never announced, so the id is only checked when the parts are built.
            val nameless = """{"index":0,"id":${JsonPrimitive(spelling.text)},"function":{"arguments":"{}"}}"""
            // No arguments: never announced either.
            val unusable = """{"index":0,"id":${JsonPrimitive(spelling.text + CONTROL)},"function":{"name":"tap"}}"""
            for (call in listOf(nameless, unusable)) {
                server.enqueueSse(Kit.sse("""{"choices":[{"index":0,"delta":{"tool_calls":[$call]}}]}""", end))
                assertTrue(failure(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS)) is ModelError.BadResponse)
            }
        }

    @ParameterizedTest(name = "unknown status, reason: {0}")
    @EnumSource(Spelling::class)
    fun `a status or incomplete reason that is not known is not quoted`(spelling: Spelling) =
        runTest {
            val value = JsonPrimitive(spelling.text)
            enqueueJson("""{"status":$value,"output":[]}""")
            assertTrue(failure(model(ModelProtocol.OPENAI_RESPONSES)) is ModelError.BadResponse, "whole reply")
            server.enqueueSse(
                Kit.sse("""{"type":"response.completed","response":{"status":$value,"output":[]}}""", done = false),
            )
            assertTrue(failure(model(ModelProtocol.OPENAI_RESPONSES)) is ModelError.BadResponse, "terminal event")
            server.enqueueSse(
                Kit.sse(
                    """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                        """"incomplete_details":{"reason":$value},"output":[]}}""",
                    done = false,
                ),
            )
            val plan = failure(model(ModelProtocol.OPENAI_RESPONSES, Kit.PLAN))
            assertTrue(plan is ModelError.BadResponse, "incomplete on the plan")
        }

    @ParameterizedTest(name = "trial text answer, other arguments: {0}")
    @EnumSource(Spelling::class)
    fun `a trial answer in text or with other arguments is not quoted`(spelling: Spelling) =
        runTest {
            val stop = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
            val text = """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive(spelling.text)}}}]}"""
            val answered = probe(ModelProtocol.OPENAI_CHAT_COMPLETIONS) { server.enqueueSse(Kit.sse(text, stop)) }
            assertTrue(answered is ProbeResult.Failed, "an answer in text fails")
            val args = JsonPrimitive("""{"token":"other","note":${JsonPrimitive(spelling.text)}}""")
            val call =
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
                    """"function":{"name":"$TRIAL_TOOL","arguments":$args}}]}}]}"""
            val end = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
            val other = probe(ModelProtocol.OPENAI_CHAT_COMPLETIONS) { server.enqueueSse(Kit.sse(call, end)) }
            assertTrue(other is ProbeResult.Failed, "other arguments fail")
        }

    @ParameterizedTest(name = "model list not JSON: {0}")
    @EnumSource(Spelling::class)
    fun `a model list that is not JSON is not quoted`(spelling: Spelling) =
        runTest {
            val body = """{"data":[{"id":"${spelling.text}""""
            server.enqueue(MockResponse.Builder().body(body).build())
            val error =
                assertThrows<Throwable> { Kit.adapters(Keys(ECHOED)).probe().listModels(Kit.profile(server.base())) }
            assertNoPiece("the error", "${error.message} $error ${error.stackTraceToString()}")
            assertTrue(error is ModelError.BadResponse, "the listing is rejected")
            server.enqueue(MockResponse.Builder().body(body).build())
            val result =
                Kit
                    .adapters(Keys(ECHOED))
                    .probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), TOKEN)
            assertNoPiece("the probe result", result.toString())
        }

    @ParameterizedTest(name = "error body not JSON, no message field: {0}")
    @EnumSource(Spelling::class)
    fun `an error body that is not JSON is withheld when it holds the secret`(spelling: Spelling) =
        runTest {
            for (protocol in listOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)) {
                server.enqueueError(400, """{"error":{"message":"${spelling.text}"""")
                assertTrue(failure(model(protocol)) is ModelError.InvalidRequest, "$protocol")
                server.enqueueError(400, """{"oops":"${spelling.text}"}""")
                assertTrue(failure(model(protocol)) is ModelError.InvalidRequest, "$protocol, no message field")
            }
        }

    @ParameterizedTest(name = "redirect target: {0}")
    @EnumSource(Spelling::class)
    fun `a redirect target that holds the secret is withheld`(spelling: Spelling) =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "https://example.invalid/${spelling.text}")
                    .build(),
            )
            assertTrue(failure(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS)) is ModelError.InvalidRequest)
        }

    @ParameterizedTest(name = "I/O failure: {0}")
    @EnumSource(Spelling::class)
    fun `an I-O failure quotes none of what the server sent`(spelling: Spelling) =
        runTest {
            // OkHttp's ProtocolException quotes a status line it cannot read.
            server.enqueue(
                MockResponse
                    .Builder()
                    .status("HTTP/1.1 2x0 ${spelling.text}")
                    .build(),
            )
            val error = failure(model(ModelProtocol.OPENAI_CHAT_COMPLETIONS))
            assertTrue(error is ModelError.Network, "the call fails as a network error")
            assertTrue(error.message.orEmpty().contains("ProtocolException"), "the message names the failure")
        }

    private companion object {
        val BACKSLASH = Char(92)

        /** A placeholder secret with a slash and a quote, so each of its escapes changes it. */
        const val ECHOED = "plain/quill\"vexm-r5-0001"
        const val TOKEN = "pa-r5"
        const val TRIAL_TOOL = "confirm_connection"
        val CONTROL = Char(1)
        const val MIN_PIECE = 6

        /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [ECHOED]; never prints it. */
        fun assertNoPiece(
            what: String,
            text: String,
        ) {
            val leaked = (0..ECHOED.length - MIN_PIECE).any { text.contains(ECHOED.substring(it, it + MIN_PIECE)) }
            assertFalse(leaked, "$what holds a piece of the secret (value not shown)")
        }
    }
}
