package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * What a provider echoes back in a reply that succeeds never shows the secret either (model-layer
 * §2): identifiers are never shown rewritten, so a listed model id or a reply metadata field
 * holding it is left out whole; a trial reply whose content echoes it is rejected
 * ([EchoRejectionTest]), so the probe result shows none of it. The
 * secret here has no credential shape, so only a redactor that knows the request's secret can mask
 * it; text is masked before it is cut, so no prefix of the secret survives a cut. Assertions never
 * print the secret.
 */
class SuccessRedactionTest {
    @StartStop
    private val server = MockWebServer()

    private val adapters get() = Kit.adapters(Keys(ECHOED))

    private fun assertClean(
        what: String,
        text: String,
    ) {
        assertFalse(text.contains(ECHOED.take(PREFIX)), "$what leaks the secret or a prefix of it (value not shown)")
    }

    private fun trialCall(args: String) =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                """{"name":"confirm_connection","arguments":${JsonPrimitive(args)}}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        )

    @Test
    fun `trial arguments echoing the key never reach the probe result`() =
        runTest {
            // The cut of the quoted arguments falls inside the secret.
            val args = "{\"token\":\"" + "x".repeat(PAD) + ECHOED + "\"}"
            server.enqueueError(404, "{}")
            server.enqueueSse(trialCall(args))
            val result = adapters.probe().testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ModelErrorKind.BAD_RESPONSE, (result as ProbeResult.Failed).kind, "the echo is rejected")
            assertClean("the probe result", result.toString())
        }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        textBlock = """
        quote     | placeholder"with-quote-0001     | {"token":"placeholder\"with-quote-0001"}
        backslash | placeholder\with-backslash-01   | {"token":"placeholder\\with-backslash-01"}
        slash     | placeholder/with-slash-00001    | {"token":"placeholder\/with-slash-00001"}""",
    )
    fun `trial arguments echoing the key behind JSON escapes are masked`(
        name: String,
        echoed: String,
        args: String,
    ) = runTest {
        server.enqueueError(404, "{}")
        server.enqueueSse(trialCall(args))
        val probe = Kit.adapters(Keys(echoed)).probe()
        val result = probe.testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
        assertTrue(result is ProbeResult.Failed, "the trial fails")
        val shown = result.toString()
        assertFalse(shown.contains(echoed.take(PREFIX)), "$name: the result leaks the key (value not shown)")
    }

    @Test
    fun `trial arguments hiding a key shape behind a JSON escape are masked`() =
        runTest {
            // Assembled at run time so secret scanners do not take the placeholder for a real key.
            val shape = "sk-" + "abcdefghijkl"
            server.enqueueError(404, "{}")
            server.enqueueSse(trialCall("{\"token\":\"\\u0073" + shape.drop(1) + "\"}"))
            val result = adapters.probe().testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertTrue(result is ProbeResult.Failed, "the trial fails")
            assertFalse(result.toString().contains(shape.drop(1)), "the result leaks the key shape (value not shown)")
        }

    @Test
    fun `an answer echoing the key never reaches the probe result`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"content":"Your key is $ECHOED."}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ),
            )
            val result = adapters.probe().testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ModelErrorKind.BAD_RESPONSE, (result as ProbeResult.Failed).kind, "the echo is rejected")
            assertClean("the probe result", result.toString())
        }

    @Test
    fun `listed model ids holding the key are left out, and the candidate is still found`() =
        runTest {
            val listing = """{"data":[{"id":"$ECHOED"},{"id":"m/$ECHOED"},{"id":"${Kit.MODEL.value}"}]}"""
            server.enqueue(MockResponse.Builder().body(listing).build())
            val models = adapters.probe().listModels(Kit.profile(server.base()))
            assertClean("the listing", models.toString())
            assertEquals(listOf(ListedModel(Kit.MODEL)), models)
            server.enqueue(MockResponse.Builder().body(listing).build())
            server.enqueueSse(trialCall("{\"token\":\"t\"}"))
            val candidate = ModelId("m/$ECHOED")
            val result = adapters.probe().testAndSave(Kit.profile(server.base()), candidate, Kit.capabilities(), "t")
            assertClean("the probe result", result.toString())
            assertTrue(result is ProbeResult.Passed, "the trial passes")
            result as ProbeResult.Passed
            assertEquals(listOf(ListedModel(Kit.MODEL)), result.models)
            assertEquals(true, result.candidateListed, "compared with the ids as listed")
        }

    @Test
    fun `Chat reply metadata fields holding the key are left out`() =
        runTest {
            server.enqueueSse(
                Kit.sse(
                    """{"id":"$ECHOED","model":"m/$ECHOED","choices":[{"index":0,"delta":{"content":"hi"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ),
            )
            server.enqueueSse(
                Kit.sse(
                    """{"id":"c-1","model":"m","choices":[{"index":0,"delta":{"content":"hi"}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"$ECHOED"}]}""",
                ),
            )
            val profile = Kit.profile(server.base())
            val model =
                adapters.model(
                    profile,
                    Kit.MODEL,
                    Kit.capabilities(),
                    Kit.blobs(),
                    WireOptions.defaultsFor(profile),
                )
            assertMetadata(mapOf("finish_reason" to "stop"), model.complete(Kit.request()).providerMetadata)
            assertMetadata(mapOf("id" to "c-1", "model" to "m"), model.complete(Kit.request()).providerMetadata)
        }

    @Test
    fun `Responses reply metadata fields holding the key are left out`() =
        runTest {
            server.enqueueSse(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"id\":\"$ECHOED\",\"model\":\"m/$ECHOED\"," +
                    "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"$ECHOED\"},\"output\":[]}}\n\n",
            )
            val profile = Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES)
            val model =
                adapters.model(
                    profile,
                    Kit.MODEL,
                    Kit.capabilities(),
                    Kit.blobs(),
                    WireOptions.defaultsFor(profile),
                )
            assertMetadata(mapOf("status" to "incomplete"), model.complete(Kit.request()).providerMetadata)
        }

    private fun assertMetadata(
        expected: Map<String, String>,
        actual: JsonObject?,
    ) {
        assertClean("the metadata", actual.toString())
        assertFalse(actual.toString().contains("***"), "identifiers are left out, not rewritten")
        assertEquals(JsonObject(expected.mapValues { JsonPrimitive(it.value) }), actual)
    }

    private companion object {
        /** A placeholder secret without a credential shape. */
        const val ECHOED = "plain-placeholder-0001"
        const val PREFIX = 8

        /** Probe snippets are 300 characters; `{"token":"` is 10. */
        const val PAD = 300 - 10 - PREFIX
    }
}
