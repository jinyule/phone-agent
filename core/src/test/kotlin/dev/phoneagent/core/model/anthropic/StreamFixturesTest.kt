package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Replays every stream under `wire/anthropic/` (`*.sse`, plus `*.json` whole replies) through the
 * real HTTP path and compares the outcome — reply or error, and the deltas shown — with
 * `<case>.expect.json` (compare-only). A `: source:` comment at the top of each stream says where
 * it comes from. Thinking signatures, cache hits, refusals and fallbacks in these files are
 * constructed from the API reference, not recorded from Claude.
 */
class StreamFixturesTest {
    @StartStop
    private val server = MockWebServer()

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun `each stream gives its expected reply and deltas`(name: String) =
        runTest {
            val file = DIR.resolve(name)
            val text = file.readText()
            val json = name.endsWith(".json")
            if (json) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(text)
                        .build(),
                )
            } else {
                server.enqueueSse(text, chunkSize = CHUNK)
            }
            val deltas = Deltas()
            val outcome =
                try {
                    val reply = Kit.model(server).complete(Kit.request(), deltas)
                    checkDeltas(name, reply, deltas)
                    buildJsonObject { put("response", Json.encodeToJsonElement(ChatResponse.serializer(), reply)) }
                } catch (e: ModelError) {
                    buildJsonObject {
                        put(
                            "error",
                            buildJsonObject {
                                put("kind", e.kind.name)
                                put("message", e.message)
                            },
                        )
                    }
                }
            val source = if (json) JSON_SOURCE else sourceOf(text)
            val actual =
                buildJsonObject {
                    put("source", source)
                    val shown = JsonArray(deltas.all.map { JsonPrimitive(describe(it)) })
                    put("expect", JsonObject(outcome + ("deltas" to shown)))
                }
            Kit.assertGolden("anthropic/${name.substringBeforeLast('.')}.expect.json", actual)
        }

    /** Deltas add up to the reply unless the reply says the preview diverged (server fallback). */
    private fun checkDeltas(
        name: String,
        reply: ChatResponse,
        deltas: Deltas,
    ) {
        val divergedJson = reply.providerMetadata?.get(PREVIEW_DIVERGED) as? JsonArray
        val diverged = divergedJson?.map { (it as JsonPrimitive).content }.orEmpty()
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        assertEquals(text, deltas.text, "$name: text")
        if ("reasoning" !in diverged) {
            val summaries = reply.parts.filterIsInstance<Part.Reasoning>().mapNotNull { it.summary }
            assertEquals(summaries.joinToString("\n\n"), deltas.reasoning, "$name: reasoning")
        }
        if ("calls" !in diverged) {
            assertEquals(reply.toolCalls.map { it.id }, deltas.started.map { it.id }, "$name: calls")
        }
        if ("args" !in diverged) {
            reply.toolCalls.forEach { assertEquals(it.argsJson, deltas.args(it.id), "$name: args") }
        }
        val renamed =
            deltas.started.filter { started ->
                reply.toolCalls.any {
                    it.id == started.id &&
                        it.name != started.name
                }
            }
        assertTrue(renamed.isEmpty(), "$name: calls announced under another name: $renamed")
    }

    private fun describe(delta: Delta): String =
        when (delta) {
            is Delta.Text -> "text: ${delta.text}"
            is Delta.Reasoning -> "reasoning: ${delta.text}"
            is Delta.ToolCallStarted -> "started: ${delta.id.value} ${delta.name}"
            is Delta.ToolCallArgs -> "args: ${delta.id.value} ${delta.fragment}"
        }

    private fun sourceOf(text: String): String =
        text
            .lineSequence()
            .filter { it.startsWith(SOURCE) }
            .joinToString(" ") { it.removePrefix(SOURCE).trim() }
            .ifEmpty { error("fixture has no '$SOURCE' comment") }

    companion object {
        private val DIR = Path.of("src/test/resources/wire/anthropic")
        private const val SOURCE = ": source:"
        private const val JSON_SOURCE =
            "constructed: a whole Message object, as a server that ignores stream:true sends it"

        /** Small chunks, so events and UTF-8 characters are split across reads. */
        private const val CHUNK = 7

        @JvmStatic
        fun fixtures(): List<String> =
            Files.list(DIR).use { files ->
                files
                    .map { it.name }
                    .filter { (it.endsWith(".sse") || it.endsWith(".json")) && !it.endsWith(".expect.json") }
                    .sorted()
                    .toList()
            }
    }
}
