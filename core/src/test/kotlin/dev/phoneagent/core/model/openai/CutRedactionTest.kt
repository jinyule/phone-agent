package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Every cut of provider text comes after masking with every secret the run has sent (model-layer
 * §2), so a secret that straddles a cut leaves no prefix behind ([Straddle]): the 64 KiB read limit
 * of error bodies on each exit, and in "test and save" with a rotated key, the 500-character error
 * detail and the 300-character redirect target. The secrets have no credential shape, so only exact
 * matching masks them. Assertions never print them.
 */
class CutRedactionTest {
    @StartStop
    private val server = Kit.server()

    private fun model(protocol: ModelProtocol): ChatModel =
        Kit
            .adapters(Keys(CURRENT))
            .unprobed(Kit.profile(server.base(), protocol = protocol), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    /** A 502 whose body is blank up to the key, which starts half its length before 64 KiB. */
    private fun enqueueCutBody(tail: Int) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(502)
                .body(Straddle.text(BODY_LIMIT, CURRENT, ' ', tail))
                .build(),
        )
    }

    @ParameterizedTest(name = "{displayName} [tail {0}]")
    @ValueSource(ints = [0, 100])
    fun `a Chat error body cut at 64 KiB inside the key leaves no prefix`(tail: Int) =
        runTest {
            enqueueCutBody(tail)
            val error =
                assertThrows<ModelError.Server> { model(ModelProtocol.OPENAI_CHAT_COMPLETIONS).send(Kit.request()) }
            Straddle.assertNoPrefix("the Chat error", error.stackTraceToString(), CURRENT)
            assertTrue(error.message!!.endsWith(Redactor.MASK), "the key is masked whole, not dropped")
        }

    @ParameterizedTest(name = "{displayName} [tail {0}]")
    @ValueSource(ints = [0, 100])
    fun `a Responses error body cut at 64 KiB inside the key leaves no prefix`(tail: Int) =
        runTest {
            enqueueCutBody(tail)
            val error =
                assertThrows<ModelError.Server> { model(ModelProtocol.OPENAI_RESPONSES).send(Kit.request()) }
            Straddle.assertNoPrefix("the Responses error", error.stackTraceToString(), CURRENT)
        }

    @ParameterizedTest(name = "{displayName} [tail {0}]")
    @ValueSource(ints = [0, 100])
    fun `a listing error body cut at 64 KiB inside the key leaves no prefix`(tail: Int) =
        runTest {
            val probe = Kit.adapters(Keys(CURRENT)).probe()
            enqueueCutBody(tail)
            val error = assertThrows<ModelError.Server> { probe.listModels(Kit.profile(server.base())) }
            Straddle.assertNoPrefix("the listing error", error.stackTraceToString(), CURRENT)
            enqueueCutBody(tail)
            val result = probe.testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ProbeStep.LIST_MODELS, (result as ProbeResult.Failed).step)
            Straddle.assertNoPrefix("the probe result", result.toString(), CURRENT)
        }

    @Test
    fun `a key shape the 64 KiB cut leaves too short to match leaves no prefix`() =
        runTest {
            // Shapes are matched once the body is parsed: the cut keeps "sk-" and six more, one short of a shape.
            val start = BODY_LIMIT - SHAPE_PREFIX
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body(" ".repeat(start) + SHAPED + " ".repeat(100))
                    .build(),
            )
            val error =
                assertThrows<ModelError.Server> { model(ModelProtocol.OPENAI_CHAT_COMPLETIONS).send(Kit.request()) }
            Straddle.assertNoPrefix("the Chat error", error.stackTraceToString(), SHAPED)
        }

    @Test
    fun `a rotated key cut by the 500-character error detail leaves no prefix`() =
        runTest {
            // The listing sends the first key; the trial, the second; its 400 echoes the first.
            server.enqueueError(404, "{}")
            val message = JsonPrimitive(Straddle.text(DETAIL_LIMIT, ROTATED, '.', tail = 40))
            server.enqueueError(400, """{"error":{"message":$message}}""")
            val result =
                Kit
                    .adapters(Keys(ROTATED, CURRENT))
                    .probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ModelErrorKind.INVALID_REQUEST, (result as ProbeResult.Failed).kind)
            Straddle.assertNoPrefix("the probe result", result.toString(), ROTATED)
        }

    @Test
    fun `a rotated key cut by the 300-character redirect target leaves no prefix`() =
        runTest {
            server.enqueueError(404, "{}")
            val origin = "https://elsewhere.test/"
            val location = origin + Straddle.text(LOCATION_LIMIT - origin.length, ROTATED, 'p', tail = 40)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", location)
                    .build(),
            )
            val result =
                Kit
                    .adapters(Keys(ROTATED, CURRENT))
                    .probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
            assertEquals(ModelErrorKind.INVALID_REQUEST, (result as ProbeResult.Failed).kind)
            Straddle.assertNoPrefix("the probe result", result.toString(), ROTATED)
        }

    private companion object {
        /** Placeholders without a credential shape. */
        const val CURRENT = "Zq-placeholder-cut-0001"
        const val ROTATED = "Yv-placeholder-old-0002"

        /** The cut points: error body read limit, error detail, redirect target. */
        const val BODY_LIMIT = 64 * 1024

        /** A key shape the request did not send, assembled so secret scanners do not take it for a real key. */
        val SHAPED = listOf("sk", "placeholder-shape-0003").joinToString("-")

        /** "sk-" and six characters: one short of what the shape needs. */
        const val SHAPE_PREFIX = 9
        const val DETAIL_LIMIT = 500
        const val LOCATION_LIMIT = 300
    }
}
