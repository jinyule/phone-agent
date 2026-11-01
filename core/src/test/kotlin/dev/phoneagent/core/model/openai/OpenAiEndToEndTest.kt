package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Small live checks against a real endpoint; skipped unless configured:
 *
 * - `PA_E2E_BASE_URL`, `PA_E2E_API_KEY`, `PA_E2E_MODEL`: an OpenAI-compatible gateway serving both
 *   protocols (`PA_E2E_RESPONSES_MODEL` overrides the model for Responses).
 * - `PA_E2E_OLLAMA_BASE_URL`, `PA_E2E_OLLAMA_MODEL`: an Ollama server for the preset check.
 *
 * About five model calls per protocol and one for Ollama. A rate limit or quota error stops every
 * remaining check: nothing is retried. Each call prints one `e2e:` line for the report.
 */
class OpenAiEndToEndTest {
    private val env = System.getenv()
    private val credentials = Keys(env["PA_E2E_API_KEY"])

    /** Production timeouts: local models may think for a long time before the first byte. */
    private val http = HttpTimeouts().client()

    private fun profile(protocol: ModelProtocol) =
        Kit.profile(checkNotNull(env["PA_E2E_BASE_URL"]).removeSuffix("/"), protocol = protocol, credential = Kit.KEY)

    private fun model(protocol: ModelProtocol): ModelId =
        ModelId(
            if (protocol ==
                ModelProtocol.OPENAI_RESPONSES
            ) {
                env["PA_E2E_RESPONSES_MODEL"] ?: env.getValue("PA_E2E_MODEL")
            } else {
                env.getValue("PA_E2E_MODEL")
            },
        )

    private val capabilities = ModelCapabilities(tools = true, vision = true, contextTokens = 128_000)

    private suspend fun call(
        label: String,
        model: ChatModel,
        request: ChatRequest,
    ): Pair<ChatResponse, Deltas> {
        assumeFalse(stopped, "an earlier call hit a rate limit or quota; not calling again")
        val deltas = Deltas()
        try {
            val reply = model.complete(request, deltas)
            println(
                "e2e: $label model=${model.info.model} stop=${reply.stopReason} usage=${reply.usage} " +
                    "calls=${reply.toolCalls.size}",
            )
            assertEquals(
                reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text },
                deltas.text,
                "deltas add up",
            )
            return reply to deltas
        } catch (e: ModelError) {
            println("e2e: $label model=${model.info.model} error=${e.kind} ${e.message}")
            if (e is ModelError.RateLimited || e is ModelError.QuotaExceeded) stopped = true
            throw e
        }
    }

    private fun chatModel(
        protocol: ModelProtocol,
        images: Map<BlobRef, ByteArray> = emptyMap(),
    ): ChatModel =
        Kit
            .adapters(credentials, http)
            .model(profile(protocol), model(protocol), capabilities, Kit.blobs(*images.toList().toTypedArray()))

    private fun textAndStreaming(protocol: ModelProtocol) =
        runTest(timeout = TIMEOUT) {
            val request = Kit.request(Kit.user("Reply with the single word: pong"), tools = emptyList())
            val (reply, deltas) = call("$protocol text", chatModel(protocol), request)
            assertEquals(StopReason.END_TURN, reply.stopReason)
            assertTrue(deltas.text.lowercase(Locale.ROOT).contains("pong"), deltas.text)
            assertTrue(deltas.all.count { it is Delta.Text } >= 1)
        }

    private fun toolRoundTrip(protocol: ModelProtocol) =
        runTest(timeout = TIMEOUT) {
            val model = chatModel(protocol)
            val ask = Kit.user("What is the weather in Paris? Use the tool.")
            val (first, deltas) = call("$protocol tool call", model, Kit.request(ask))
            assertEquals(StopReason.TOOL_USE, first.stopReason)
            val call = first.toolCalls.single()
            assertEquals("get_weather", call.name)
            assertEquals(JsonPrimitive("Paris"), (Json.parseToJsonElement(call.argsJson) as JsonObject)["city"])
            assertEquals(call.argsJson, deltas.args(call.id))
            val result = Part.ToolResult(call.id, listOf(Part.Text("Sunny, 21 °C.")), isError = false)
            val followUp = Kit.request(ask, Message(Role.ASSISTANT, first.parts), Message(Role.TOOL, listOf(result)))
            val (second, _) = call("$protocol tool result", model, followUp)
            assertEquals(StopReason.END_TURN, second.stopReason)
            assertTrue(
                second.parts
                    .filterIsInstance<Part.Text>()
                    .joinToString()
                    .contains("21"),
            )
        }

    private fun imageInput(protocol: ModelProtocol) =
        runTest(timeout = TIMEOUT) {
            // A colour per protocol, so a model that cannot see the image does not pass by guessing "red".
            val (colour, name) =
                if (protocol ==
                    ModelProtocol.OPENAI_RESPONSES
                ) {
                    Color.BLUE to "blue"
                } else {
                    Color.GREEN to "green"
                }
            val png = square(colour)
            val ref = BlobRef(sha256(png))
            val ask =
                Message(
                    Role.USER,
                    listOf(
                        Part.Text("What colour is this square? Answer with one word."),
                        Part.Image(ref, "image/png", SIDE, SIDE),
                    ),
                )
            val (reply, _) =
                call(
                    "$protocol image",
                    chatModel(protocol, mapOf(ref to png)),
                    Kit.request(ask, tools = emptyList()),
                )
            assertTrue(
                reply.parts
                    .filterIsInstance<Part.Text>()
                    .joinToString()
                    .lowercase(Locale.ROOT)
                    .contains(name),
                reply.toString(),
            )
        }

    private fun probe(protocol: ModelProtocol) =
        runTest(timeout = TIMEOUT) {
            assumeFalse(stopped, "an earlier call hit a rate limit or quota; not calling again")
            val result =
                Kit
                    .adapters(
                        credentials,
                        http,
                    ).probe()
                    .testAndSave(profile(protocol), model(protocol), capabilities)
            println("e2e: $protocol test-and-save model=${model(protocol)} result=$result")
            val limited = setOf(ModelErrorKind.RATE_LIMITED, ModelErrorKind.QUOTA_EXCEEDED)
            if (result is ProbeResult.Failed && result.kind in limited) stopped = true
            assertTrue(result is ProbeResult.Passed, result.toString())
        }

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `chat completions - text and streaming`() = textAndStreaming(ModelProtocol.OPENAI_CHAT_COMPLETIONS)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `chat completions - tool round trip`() = toolRoundTrip(ModelProtocol.OPENAI_CHAT_COMPLETIONS)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `chat completions - image input`() = imageInput(ModelProtocol.OPENAI_CHAT_COMPLETIONS)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `chat completions - test and save`() = probe(ModelProtocol.OPENAI_CHAT_COMPLETIONS)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `responses - text and streaming`() = textAndStreaming(ModelProtocol.OPENAI_RESPONSES)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `responses - tool round trip`() = toolRoundTrip(ModelProtocol.OPENAI_RESPONSES)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `responses - image input`() = imageInput(ModelProtocol.OPENAI_RESPONSES)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_BASE_URL", matches = ".+")
    fun `responses - test and save`() = probe(ModelProtocol.OPENAI_RESPONSES)

    @Test
    @EnabledIfEnvironmentVariable(named = "PA_E2E_OLLAMA_BASE_URL", matches = ".+")
    fun `ollama preset - test and save`() =
        runTest(timeout = TIMEOUT) {
            val profile =
                ProviderPresets.OLLAMA.profile(
                    ProviderId("ollama"),
                    Credential.None,
                    baseUrl = env.getValue("PA_E2E_OLLAMA_BASE_URL").removeSuffix("/"),
                )
            val model = ModelId(env.getValue("PA_E2E_OLLAMA_MODEL"))
            val result =
                Kit
                    .adapters(
                        Keys(null),
                        http,
                    ).probe()
                    .testAndSave(profile, model, capabilities.copy(vision = false))
            println("e2e: ollama test-and-save model=$model result=$result")
            assertTrue(result is ProbeResult.Passed, result.toString())
            assertEquals(true, (result as ProbeResult.Passed).candidateListed)
        }

    private fun square(colour: Color): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val image = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = colour
        graphics.fillRect(0, 0, SIDE, SIDE)
        graphics.dispose()
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val SIDE = 64
        val TIMEOUT = 5.minutes

        /** Set by a rate limit or quota error; later checks are skipped. */
        @Volatile
        var stopped = false
    }
}
