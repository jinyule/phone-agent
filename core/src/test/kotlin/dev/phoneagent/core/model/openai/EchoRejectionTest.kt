package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A reply whose model-produced content holds a secret the request sent is rejected whole, never
 * rewritten (model-layer §2): text, call arguments (recovered calls too), reasoning and encrypted
 * reasoning, as sent or JSON-escaped, whole or split over deltas. The rejection carries the
 * "streamed output is not kept" marker ([CredentialEcho]) and none of the echo; the delta that
 * completes the secret is not shown, the ones before it are not held back. Every secret counts,
 * however short. Assertions never print a secret.
 */
class EchoRejectionTest {
    @StartStop
    private val server = MockWebServer()

    private fun model(
        protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        secret: String = ECHOED,
        recover: Boolean = false,
    ): ChatModel {
        val profile = Kit.profile(server.base(), protocol = protocol)
        val wire = if (recover) Kit.recovering(profile) else WireOptions.defaultsFor(profile)
        return Kit.adapters(Keys(secret)).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), wire)
    }

    /** Expects the marked echo rejection; neither the error nor a shown delta may hold [secret]. */
    private suspend fun assertRejected(
        model: ChatModel,
        secret: String = ECHOED,
        deltas: Deltas = Deltas(),
    ): ModelError {
        val error = assertThrows<ModelError.BadResponse> { model.complete(Kit.request(), deltas) }
        assertTrue(CredentialEcho.discardsStreamed(error), "the rejection carries the marker")
        assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message)
        assertFalse(error.stackTraceToString().contains(secret.take(PREFIX)), "the error holds the secret (not shown)")
        val escaped = JsonPrimitive(secret).toString().removeSurrounding("\"")
        val shown = deltas.all.toString()
        assertFalse(shown.contains(secret) || shown.contains(escaped), "a delta shows the secret (not shown)")
        return error
    }

    private fun chat(vararg deltas: String): String =
        Kit.sse(
            *deltas.map { """{"choices":[{"index":0,"delta":$it}]}""" }.toTypedArray(),
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )

    private fun content(text: String) = """{"content":${JsonPrimitive(text)}}"""

    private fun args(
        fragment: String,
        first: Boolean = false,
    ): String {
        val head = if (first) """"id":"call_1","function":{"name":"get_weather",""" else """"function":{"""
        return """{"tool_calls":[{"index":0,$head"arguments":${JsonPrimitive(fragment)}}}]}"""
    }

    private fun responses(
        vararg events: String,
        output: String = "[]",
    ): String =
        events.joinToString("") { "data: $it\n\n" } +
            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":$output}}\n\n"

    private fun textDelta(text: String) = """{"type":"response.output_text.delta","delta":${JsonPrimitive(text)}}"""

    private fun message(text: String) =
        """[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)}}]}]"""

    @Test
    fun `Chat text echoing the key whole is rejected`() =
        runTest {
            server.enqueueSse(chat(content("Your key is $ECHOED.")))
            assertRejected(model())
        }

    @Test
    fun `Chat text echoing the key over two fragments is rejected when the second arrives`() =
        runTest {
            val cut = ECHOED.length / 2
            server.enqueueSse(chat(content("Your key is " + ECHOED.take(cut)), content(ECHOED.drop(cut) + ".")))
            val deltas = Deltas()
            assertRejected(model(), deltas = deltas)
            assertEquals("Your key is " + ECHOED.take(cut), deltas.text, "the first fragment was shown at once")
        }

    @Test
    fun `Chat arguments echoing the key JSON-escaped are rejected, whole or split`() =
        runTest {
            val escaped = JsonPrimitive(QUOTED).toString().removeSurrounding("\"")
            server.enqueueSse(chat(args("""{"city":"$escaped"}""", first = true)))
            assertRejected(model(secret = QUOTED), QUOTED)
            val head = """{"city":"${escaped.take(10)}"""
            server.enqueueSse(chat(args(head, first = true), args("""${escaped.drop(10)}"}""")))
            val deltas = Deltas()
            assertRejected(model(secret = QUOTED), QUOTED, deltas)
            assertEquals(head, deltas.args(ToolCallId("call_1")), "the first fragment was shown")
        }

    @Test
    fun `Chat arguments hiding the key behind another JSON escape are rejected`() =
        runTest {
            server.enqueueSse(chat(args("""{"city":"placeholder\/with-slash-00001"}""", first = true)))
            assertRejected(model(secret = SLASHED), SLASHED)
        }

    @Test
    fun `Chat reasoning echoing the key is rejected`() =
        runTest {
            server.enqueueSse(chat("""{"reasoning_content":"I will use $ECHOED"}""", content("Hi")))
            assertRejected(model())
        }

    @Test
    fun `a whole Chat completion echoing the key is rejected`() =
        runTest {
            val body =
                """{"choices":[{"index":0,"message":{"content":"Your key is $ECHOED."},"finish_reason":"stop"}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build(),
            )
            assertRejected(model())
        }

    @Test
    fun `a recovered call carrying the key is rejected`() =
        runTest {
            val leaked =
                "<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"$ECHOED\"}}\n</tool_call>"
            server.enqueueSse(chat(content(leaked)))
            val deltas = Deltas()
            assertRejected(model(recover = true), deltas = deltas)
            assertTrue(deltas.all.isEmpty(), "the held-back markup was never shown")
        }

    @Test
    fun `Responses text echoing the key whole or over two deltas is rejected`() =
        runTest {
            server.enqueueSse(responses(textDelta("Your key is $ECHOED."), output = message("Your key is $ECHOED.")))
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES))
            val cut = ECHOED.length / 2
            server.enqueueSse(
                responses(
                    textDelta("Key: " + ECHOED.take(cut)),
                    textDelta(ECHOED.drop(cut)),
                    output = message("Key: $ECHOED"),
                ),
            )
            val deltas = Deltas()
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES), deltas = deltas)
            assertEquals("Key: " + ECHOED.take(cut), deltas.text)
        }

    @Test
    fun `Responses text echoing the key only in the final output is rejected`() =
        runTest {
            server.enqueueSse(responses(output = message("Your key is $ECHOED.")))
            val deltas = Deltas()
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES), deltas = deltas)
            assertTrue(deltas.all.isEmpty(), "nothing of the reply was shown")
        }

    @Test
    fun `Responses arguments echoing the key JSON-escaped are rejected`() =
        runTest {
            val args = JsonPrimitive("""{"city":${JsonPrimitive(QUOTED)}}""")
            val call = """[{"type":"function_call","call_id":"c1","name":"get_weather","arguments":$args}]"""
            server.enqueueSse(responses(output = call))
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES, QUOTED), QUOTED)
        }

    @Test
    fun `Responses reasoning summaries and encrypted content echoing the key are rejected`() =
        runTest {
            val summary = """[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"$ECHOED"}]}]"""
            server.enqueueSse(responses(output = summary))
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES))
            val encrypted = """[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"x$ECHOED"}]"""
            server.enqueueSse(responses(output = encrypted))
            assertRejected(model(ModelProtocol.OPENAI_RESPONSES))
        }

    @Test
    fun `a trial reply echoing a key the run sent earlier is rejected`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(chat(content("The old key was $ECHOED.")))
            val result =
                Kit
                    .adapters(Keys(ECHOED, "Zq-placeholder-new-0002"))
                    .probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ModelErrorKind.BAD_RESPONSE, (result as ProbeResult.Failed).kind)
            assertFalse(result.toString().contains(ECHOED.take(PREFIX)), "the result holds the secret (not shown)")
        }

    @Test
    fun `a short key echoed is rejected like any other`() =
        runTest {
            server.enqueueSse(chat(content("Served by ollama, key ${SHORT.take(2)}"), content(SHORT.drop(2) + ".")))
            val deltas = Deltas()
            assertRejected(model(secret = SHORT), SHORT, deltas)
            assertEquals("Served by ollama, key ${SHORT.take(2)}", deltas.text)
        }

    @Test
    fun `text that merely looks like a key is kept`() =
        runTest {
            // Assembled at run time so secret scanners do not take the placeholder for a real key.
            val shaped = "sk-" + "abcdefghijklmnop"
            server.enqueueSse(chat(content("Keys look like $shaped.")))
            val deltas = Deltas()
            val reply = model().complete(Kit.request(), deltas)
            assertEquals(listOf(Part.Text("Keys look like $shaped.")), reply.parts)
            assertEquals(listOf<Delta>(Delta.Text("Keys look like $shaped.")), deltas.all)
        }

    private companion object {
        /** Placeholders without a credential shape. */
        const val ECHOED = "plain-placeholder-0001"
        const val QUOTED = "placeholder\"with-quote-0001"
        const val SLASHED = "placeholder/with-slash-00001"

        /** A short key, such as a placeholder a local service was given. */
        const val SHORT = "tinkey"
        const val PREFIX = 8
    }
}
