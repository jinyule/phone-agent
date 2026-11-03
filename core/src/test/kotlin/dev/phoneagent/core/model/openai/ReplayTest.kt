package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import dev.phoneagent.core.model.openai.Kit.send
import dev.phoneagent.core.model.openai.WireSupport.array
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Replays every stream fixture under `wire/<provider>/` through the real adapter and HTTP stack,
 * once in one piece and once in 7-byte chunks, and compares the outcome with
 * `<case>.expect.json` (`expect`); a mismatch leaves the actual expectation file for review
 * ([Kit.assertWire]). Delta invariants are checked on every successful replay. A fixture with
 * `"next": true` also pins the history of the request after it (`expect.next`): the reply as the
 * assistant turn and an answer to each of its calls, as the adapter sends them back.
 */
class ReplayTest {
    @StartStop
    private val server = Kit.server()

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    fun replay(fixture: String) =
        runTest {
            val metaPath = fixture.substringBeforeLast('.') + ".expect.json"
            val meta = Json.parseToJsonElement(Kit.wire(metaPath)) as JsonObject
            val whole = run(fixture, meta, Int.MAX_VALUE)
            val chunked = run(fixture, meta, CHUNK)
            assertEquals(whole, chunked, "chunking must not change the outcome")
            val expect =
                if (meta["next"] == JsonPrimitive(true)) JsonObject(whole + ("next" to next(meta, whole))) else whole
            Kit.assertWire(metaPath, Kit.prettyText(JsonObject(meta + ("expect" to expect))))
        }

    @Test
    fun `every fixture has an expectation and a source`() {
        val all = fixtures()
        assertTrue(all.size >= MIN_FIXTURES, "fixtures: $all")
        for (fixture in all) {
            val meta =
                Json.parseToJsonElement(
                    Kit.wire(fixture.substringBeforeLast('.') + ".expect.json"),
                ) as JsonObject
            assertTrue(meta.str("source")?.isNotBlank() == true, "$fixture names its source")
        }
    }

    private suspend fun run(
        fixture: String,
        meta: JsonObject,
        chunk: Int,
    ): JsonObject {
        val body = Kit.wire(fixture)
        val type = meta.str("contentType") ?: "text/event-stream"
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", type)
                .chunkedBody(body, chunk)
                .build(),
        )
        val model = model(meta)
        val deltas = Deltas()
        return try {
            val response = model.send(Kit.request(), deltas)
            checkInvariants(response, deltas)
            buildJsonObject {
                put("response", Json.encodeToJsonElement(serializer<ChatResponse>(), response))
                putJsonObject("deltas") {
                    put("text", deltas.text)
                    put("reasoning", deltas.reasoning)
                    putJsonArray("calls") {
                        for (started in deltas.started) {
                            addJsonObject {
                                put("id", started.id.value)
                                put("name", started.name)
                                put("args", deltas.args(started.id))
                            }
                        }
                    }
                }
            }
        } catch (e: ModelError) {
            buildJsonObject {
                putJsonObject("error") {
                    put("kind", e.kind.name)
                    put("message", e.message)
                }
            }
        }
    }

    private fun model(meta: JsonObject): ChatModel {
        val responses = meta.str("protocol") == "responses"
        val profile =
            Kit.profile(
                server.base(),
                protocol = if (responses) ModelProtocol.OPENAI_RESPONSES else ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                credential = if (meta["plan"] == JsonPrimitive(true)) Kit.PLAN else Credential.None,
                reasoningField = meta.str("reasoningField"),
                preset = meta.str("preset"),
            )
        val recover = meta.array("recover").orEmpty().map { LeakedCallFormat.valueOf((it as JsonPrimitive).content) }
        // A custom route recovers only formats it declares; a preset route only its preset's.
        val wire =
            if (profile.preset == null) {
                Kit.declaring(profile, recover.toSet())
            } else {
                Kit.recovering(profile, recover.toSet())
            }
        return Kit.adapters().model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), wire)
    }

    /**
     * The `messages` (Chat) or `input` (Responses) of the request after [outcome]: the user turn,
     * the reply's parts as the assistant turn, and a text answer to each of its calls.
     */
    private suspend fun next(
        meta: JsonObject,
        outcome: JsonObject,
    ): JsonElement {
        val reply = Json.decodeFromJsonElement(serializer<ChatResponse>(), checkNotNull(outcome["response"]))
        val answers = reply.toolCalls.map { Part.ToolResult(it.id, listOf(Part.Text("Sunny.")), isError = false) }
        val request =
            Kit.request(
                Kit.user("Weather in Paris?"),
                Message(Role.ASSISTANT, reply.parts),
                Message(Role.TOOL, answers),
            )
        val responses = meta.str("protocol") == "responses"
        server.enqueueSse(Kit.wire(if (responses) "openai-responses/text.sse" else "chat/text-include-usage.sse"))
        model(meta).send(request, Deltas())
        repeat(REPLAYS) { server.next() }
        return checkNotNull(server.next().json()[if (responses) "input" else "messages"])
    }

    /** What the UI streamed must agree with the reply the log records. */
    private fun checkInvariants(
        response: ChatResponse,
        deltas: Deltas,
    ) {
        val text = response.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        assertEquals(text, deltas.text, "text deltas add up to the text")
        val reasoning =
            response.parts
                .filterIsInstance<Part.Reasoning>()
                .mapNotNull { it.summary }
                .joinToString("\n\n")
        assertEquals(reasoning, deltas.reasoning, "reasoning deltas add up to the reasoning")
        assertEquals(
            response.toolCalls.map { it.id },
            deltas.started.map { it.id },
            "every call is announced once, in order",
        )
        for (call in response.toolCalls) {
            assertEquals(call.argsJson, deltas.args(call.id), "argument deltas of ${call.id} add up")
            assertEquals(call.name, deltas.started.first { it.id == call.id }.name)
        }
        assertEquals(null, response.providerMetadata?.get(PREVIEW_DIVERGED), "the fixtures are consistent")
    }

    companion object {
        private const val CHUNK = 7

        /** Requests one [replay] makes before [next]: the whole and the chunked run. */
        private const val REPLAYS = 2
        private const val MIN_FIXTURES = 40

        /** Fixture paths relative to `wire/`: every `.sse`, and `.json` bodies that are not expectations or goldens. */
        @JvmStatic
        fun fixtures(): List<String> {
            val root = Path.of(checkNotNull(ReplayTest::class.java.getResource("/wire")).toURI())
            return Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map { root.relativize(it) }
                    .filter { it.extension == "sse" || (it.extension == "json" && !it.name.endsWith(".expect.json")) }
                    .filter { !it.invariantSeparatorsPathString.startsWith("golden/") }
                    .map { it.invariantSeparatorsPathString }
                    .sorted()
                    .toList()
            }
        }
    }
}
