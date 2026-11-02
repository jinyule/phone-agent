package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptCaching
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * Small live checks against an Anthropic Messages endpoint; skipped unless
 * `PA_E2E_ANTHROPIC_BASE_URL`, `PA_E2E_ANTHROPIC_KEY` and `PA_E2E_ANTHROPIC_MODEL` are set. Run
 * against a gateway that converts Messages to another model, they check the wire format (text,
 * streaming, a tool round trip, an image, append-only history), not Claude's own behavior:
 * thinking signatures, cache hits, refusals and fallbacks are covered by the stream fixtures.
 *
 * Four model calls. One run sends at most `PA_E2E_MAX_REQUESTS` (default 6) requests; a rate limit
 * or quota error stops every remaining check, and nothing is retried. Each call prints one `e2e:`
 * line; raw streams (key masked) go to `build/e2e-anthropic/` for review. Assertion messages never
 * quote what the server sent.
 */
@EnabledIfEnvironmentVariable(named = "PA_E2E_ANTHROPIC_BASE_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnthropicEndToEndTest {
    private val env = System.getenv()
    private val key = env["PA_E2E_ANTHROPIC_KEY"]?.takeIf { it.isNotBlank() }
    private val budget = env["PA_E2E_MAX_REQUESTS"]?.toInt() ?: DEFAULT_BUDGET
    private val redactor = Redactor(listOfNotNull(key))

    private val http =
        AnthropicTimeouts()
            .client()
            .newBuilder()
            .addInterceptor { chain ->
                if (chain.request().method == "POST" && sent.incrementAndGet() > budget) {
                    throw IOException("e2e budget of $budget requests used up; not sending")
                }
                chain.proceed(chain.request())
            }.addInterceptor(Recorder())
            .build()

    private val profile = Kit.profile(checkNotNull(env["PA_E2E_ANTHROPIC_BASE_URL"]).removeSuffix("/"))
    private val modelId = ModelId(env.getValue("PA_E2E_ANTHROPIC_MODEL"))
    private val capabilities =
        ModelCapabilities(tools = true, vision = true, contextTokens = 128_000, promptCaching = PromptCaching.EXPLICIT)

    /**
     * The recommended settings; on the Claude API itself a stale signature is an error instead of
     * a dropped block, so the append-only check also catches it on accounts that would drop it
     * (model-layer §6). A gateway gets no binding control: it is not Claude.
     */
    private val settings =
        AnthropicSettings.defaultsFor(profile, modelId).let {
            if (AnthropicPresets.isOfficial(profile.baseUrl)) it.copy(prefixMismatch = PrefixMismatch.ERROR) else it
        }

    private fun model(images: Map<BlobRef, ByteArray> = emptyMap()): ChatModel =
        Kit.adapters(Keys(key), http).model(profile, modelId, capabilities, Kit.blobs(*images.toList().toTypedArray()))

    private fun request(
        vararg messages: Message,
        tools: Boolean = true,
    ): ChatRequest {
        val wire = AnthropicWire.snapshot(profile, modelId, capabilities, ReasoningLevel.LOW, settings)
        return Kit.request(
            *messages,
            tools = if (tools) listOf(Kit.WEATHER) else emptyList(),
            system = listOf("你是测试助手。回答尽量简短。"),
            reasoning = ReasoningLevel.LOW,
            wire = wire,
            maxOutputTokens = MAX_TOKENS,
        )
    }

    private suspend fun call(
        label: String,
        model: ChatModel,
        request: ChatRequest,
    ): Pair<ChatResponse, Deltas> {
        assumeFalse(stopped, "an earlier call hit a rate limit or quota; not calling again")
        val deltas = Deltas()
        try {
            val reply = model.complete(request, deltas)
            report(
                "e2e: $label model=${model.info.model} stop=${reply.stopReason} usage=${reply.usage} " +
                    "parts=${reply.parts.map { it::class.simpleName }} meta=${reply.providerMetadata}",
            )
            assertTrue(
                reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text } == deltas.text,
                "deltas add up (values not shown)",
            )
            reply.toolCalls.forEach {
                assertTrue(it.argsJson == deltas.args(it.id), "argument deltas add up (values not shown)")
            }
            return reply to deltas
        } catch (e: ModelError) {
            report("e2e: $label model=${model.info.model} error=${e.kind} ${e.message}")
            if (e is ModelError.RateLimited || e is ModelError.QuotaExceeded) stopped = true
            throw e
        }
    }

    private fun report(line: String) = println(redactor.text(line))

    @Test
    @Order(1)
    fun `text and streaming`() =
        runTest(timeout = TIMEOUT) {
            val (reply, deltas) = call("text", model(), request(Kit.user("只回复一个英文单词：pong"), tools = false))
            assertEquals(StopReason.END_TURN, reply.stopReason)
            assertTrue(deltas.text.lowercase(Locale.ROOT).contains("pong"), "the answer says pong (value not shown)")
        }

    @Test
    @Order(2)
    fun `tool round trip with append-only history`() =
        runTest(timeout = TIMEOUT) {
            val model = model()
            val ask = Kit.user("巴黎现在天气怎么样？请调用 get_weather 工具，reason 填“查天气”。")
            val first = request(ask)
            val (reply, deltas) = call("tool call", model, first)
            assertEquals(StopReason.TOOL_USE, reply.stopReason)
            val call = reply.toolCalls.single()
            assertTrue(call.name == Kit.WEATHER.name, "the weather tool is called (name not shown)")
            assertTrue(listOf(call.id) == deltas.started.map { it.id }, "the call is announced once (ids not shown)")
            val args = runCatching { Json.parseToJsonElement(call.argsJson) }.getOrNull() as? JsonObject
            assertTrue(args?.containsKey("city") == true, "the arguments name a city (arguments not shown)")
            val second =
                request(
                    ask,
                    Message(Role.ASSISTANT, reply.parts),
                    Message(Role.TOOL, listOf(Part.ToolResult(call.id, listOf(Part.Text("晴，22°C")), isError = false))),
                )
            val (answer) = call("tool result", model, second)
            assertEquals(StopReason.END_TURN, answer.stopReason)
            assertTrue(
                answer.parts
                    .filterIsInstance<Part.Text>()
                    .joinToString("")
                    .contains("22"),
                "the answer uses the tool result (value not shown)",
            )
            assertAppendOnly(first, second)
        }

    @Test
    @Order(3)
    fun `an image is understood`() =
        runTest(timeout = TIMEOUT) {
            val png = redSquare()
            val ref = BlobRef(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)))
            val image = Part.Image(ref, "image/png", SIDE, SIDE)
            val ask = Message(Role.USER, listOf(Part.Text("这张图主要是什么颜色？只回答颜色。"), image))
            val (reply) = call("image", model(mapOf(ref to png)), request(ask, tools = false))
            val text =
                reply.parts
                    .filterIsInstance<Part.Text>()
                    .joinToString("") { it.text }
                    .lowercase(Locale.ROOT)
            assertTrue(text.contains("红") || text.contains("red"), "the answer names the colour (value not shown)")
        }

    /**
     * model-layer §6: the messages of a request, without their moving cache breakpoints, are a
     * prefix of the next request's.
     */
    private fun assertAppendOnly(
        first: ChatRequest,
        second: ChatRequest,
    ) {
        val wire = MessagesWire(profile.id, modelId)

        fun messages(request: ChatRequest): List<JsonElement> {
            val body = wire.body(request, WireRules.of(request, CredentialKind.API_KEY), ImageData.NONE)
            return body["messages"]!!.jsonArray.map(::withoutCache)
        }
        val before = messages(first)
        val after = messages(second)
        assertTrue(before == after.take(before.size), "history is append-only (bodies not shown)")
    }

    private fun withoutCache(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.filterKeys { it != "cache_control" }.mapValues { withoutCache(it.value) },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::withoutCache))
            }

            is JsonPrimitive -> {
                element
            }
        }

    private fun redSquare(): ByteArray {
        val image = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, SIDE, SIDE)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Copies each response body, as streamed, to `build/e2e-anthropic/<n>.sse` with the key masked. */
    private inner class Recorder : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val n = recorded.incrementAndGet()
            val copy = Buffer()
            val source =
                object : ForwardingSource(response.body.source()) {
                    override fun read(
                        sink: Buffer,
                        byteCount: Long,
                    ): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) sink.copyTo(copy, sink.size - read, read)
                        return read
                    }

                    override fun close() {
                        super.close()
                        Kit.keepUnderBuild("e2e-anthropic/$n-${response.code}.sse", redactor.text(copy.readUtf8()))
                    }
                }
            return response.newBuilder().body(source.buffer().asResponseBody(response.body.contentType())).build()
        }
    }

    private companion object {
        const val DEFAULT_BUDGET = 6
        const val MAX_TOKENS = 4096
        const val SIDE = 64
        val TIMEOUT = 3.minutes
        val sent = AtomicInteger()
        val recorded = AtomicInteger()

        @Volatile var stopped = false
    }
}
