package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * kotlinx quotes the input around a parse error at a fixed width (`JSON input: .....…`), which can
 * cut a secret in two before anything can mask it. A reply or model list that is not JSON fails
 * without quoting it at all (model-layer §2), only its length; the parser's message, quote included,
 * goes nowhere: not into the error and not into its cause. Assertions never print the secret.
 */
class ParseErrorRedactionTest {
    @StartStop
    private val server = Kit.server()

    private val adapters get() = Kit.adapters(Keys(KEY))

    /** Data whose parse error quotes the input up to the middle of [KEY] (`head`) or from it (`tail`). */
    private fun input(cut: String): String =
        when (cut) {
            "head" -> """{"id":"${"x".repeat(FILLER)}",$KEY}"""
            else -> """{"id":"${"x".repeat(FILLER)}$KEY" "next":1}"""
        }

    /** Fails when [text] holds [MIN_PIECE] or more consecutive characters of [KEY]; never prints it. */
    private fun assertNoPiece(
        what: String,
        text: String,
    ) {
        val leaked = (0..KEY.length - MIN_PIECE).any { text.contains(KEY.substring(it, it + MIN_PIECE)) }
        assertFalse(leaked, "$what holds a piece of the key (value not shown)")
    }

    /** The case is real: the parser's own message holds a piece of [KEY] but not the whole of it. */
    private fun assertParserCutsTheKey(data: String) {
        val quoted = assertThrows<SerializationException> { Json.parseToJsonElement(data) }.message.orEmpty()
        val piece = (0..KEY.length - MIN_PIECE).any { quoted.contains(KEY.substring(it, it + MIN_PIECE)) }
        assertTrue(piece && !quoted.contains(KEY), "the parser's quote cuts the key (value not shown)")
    }

    @ParameterizedTest(name = "{0} {1}, the quote cuts the key at its {2}")
    @CsvSource(
        "OPENAI_CHAT_COMPLETIONS, stream, head",
        "OPENAI_CHAT_COMPLETIONS, stream, tail",
        "OPENAI_CHAT_COMPLETIONS, json, head",
        "OPENAI_CHAT_COMPLETIONS, json, tail",
        "OPENAI_RESPONSES, stream, head",
        "OPENAI_RESPONSES, stream, tail",
        "OPENAI_RESPONSES, json, head",
        "OPENAI_RESPONSES, json, tail",
    )
    fun `unparseable reply data whose quoted input cuts the key`(
        protocol: ModelProtocol,
        framing: String,
        cut: String,
    ) = runTest {
        val data = input(cut)
        assertParserCutsTheKey(data)
        val error = failure(protocol, framing, data)
        assertNoPiece("the error", error.stackTraceToString())
        assertTrue(error.message.orEmpty().contains("not JSON"), "fails as data that is not JSON")
    }

    @ParameterizedTest(name = "{0} {1}, the key crosses where a snippet would be cut")
    @CsvSource(
        "OPENAI_CHAT_COMPLETIONS, stream",
        "OPENAI_CHAT_COMPLETIONS, json",
        "OPENAI_RESPONSES, stream",
        "OPENAI_RESPONSES, json",
    )
    fun `unparseable reply data with the key across where a snippet would be cut`(
        protocol: ModelProtocol,
        framing: String,
    ) = runTest {
        // The key starts 20 characters before a 200-character cut: a quote cut before masking would keep its start.
        val head = """{"id":""""
        val data = head + "x".repeat(SNIPPET - CROSSING - head.length) + KEY + """" "next":1}"""
        assertTrue(data.indexOf(KEY) == SNIPPET - CROSSING, "the key crosses the cut")
        val error = failure(protocol, framing, data)
        assertNoPiece("the error", error.stackTraceToString())
        assertTrue(error.message.orEmpty().contains("not JSON"), "fails as data that is not JSON")
    }

    /** The failure of a reply carrying [data], streamed or as a whole JSON body. */
    private suspend fun failure(
        protocol: ModelProtocol,
        framing: String,
        data: String,
    ): ModelError.BadResponse {
        if (framing == "stream") {
            server.enqueueSse(Kit.sse(data))
        } else {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(data)
                    .build(),
            )
        }
        val profile = Kit.profile(server.base(), protocol = protocol)
        val model =
            adapters.model(
                profile,
                Kit.MODEL,
                Kit.capabilities(),
                Kit.blobs(),
                WireOptions.defaultsFor(profile),
            )
        return assertThrows<ModelError.BadResponse> { model.complete(Kit.request()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["head", "tail"])
    fun `a model list whose quoted input cuts the key`(cut: String) =
        runTest {
            val data = input(cut)
            assertParserCutsTheKey(data)
            server.enqueue(MockResponse.Builder().body(data).build())
            val error = assertThrows<ModelError.BadResponse> { adapters.probe().listModels(Kit.profile(server.base())) }
            assertNoPiece("the error", error.stackTraceToString())
            server.enqueue(MockResponse.Builder().body(data).build())
            val result = adapters.probe().testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertTrue(result is ProbeResult.Failed, "the listing step fails")
            assertNoPiece("the probe result", result.toString())
        }

    private companion object {
        /** A placeholder secret without a credential shape, longer than the parser's 30-character half window. */
        const val KEY = "plain-placeholder-for-the-parse-window-0001"
        const val FILLER = 250
        const val MIN_PIECE = 8

        /** The length the adapters once cut a quote of data that is not JSON to. */
        const val SNIPPET = 200
        const val CROSSING = 20
    }
}
