package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.PromptCaching
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.CompactionPrune
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.session.deriveMessages
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
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
 * streaming, a tool round trip and a follow-up whose requests are derived from a session log, an
 * image, append-only history), not Claude's own behavior: thinking signatures, cache hits,
 * refusals and fallbacks are covered by the stream fixtures.
 *
 * Five model calls. One run sends at most `PA_E2E_MAX_REQUESTS` (default 6) requests; a rate limit
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

    /**
     * The preset's facts for a model it knows (the default compaction trigger must lie below the
     * window), else a small window.
     */
    private val capabilities =
        AnthropicPresets.capabilities(modelId)
            ?: ModelCapabilities(
                tools = true,
                vision = true,
                contextTokens = 128_000,
                promptCaching = PromptCaching.EXPLICIT,
            )

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
        Kit
            .adapters(Keys(key), http)
            .model(profile, modelId, capabilities, Kit.blobs(*images.toList().toTypedArray()), settings)

    private fun request(
        vararg messages: Message,
        tools: Boolean = true,
    ): ChatRequest {
        val prepared = model().prepare(ReasoningLevel.LOW)
        return Kit.request(
            *messages,
            tools = if (tools) listOf(Kit.WEATHER) else emptyList(),
            system = listOf("你是测试助手。回答尽量简短。"),
            reasoning = prepared.reasoning,
            wire = prepared.wire,
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
    fun `tool round trip and a follow-up, each request derived from the log, append-only`() =
        runTest(timeout = TIMEOUT) {
            val model = model()
            val log = Log(model.prepare(ReasoningLevel.LOW))
            log += UserMessage(UserMessageKind.USER, listOf(Part.Text(ASK)))
            log += log.header
            log += UserMessage(UserMessageKind.RUNTIME_CONTEXT, listOf(Part.Text("前台：天气 App")))
            val first = log.request()
            val (reply, deltas) = call("tool call", model, first)
            assertEquals(StopReason.TOOL_USE, reply.stopReason)
            val call = reply.toolCalls.single()
            assertTrue(call.name == Kit.WEATHER.name, "the weather tool is called (name not shown)")
            assertTrue(listOf(call.id) == deltas.started.map { it.id }, "the call is announced once (ids not shown)")
            val args = runCatching { Json.parseToJsonElement(call.argsJson) }.getOrNull() as? JsonObject
            assertTrue(args?.containsKey("city") == true, "the arguments name a city (arguments not shown)")
            log += AssistantMessage(1, profile.id, modelId, reply.parts, reply.stopReason, reply.usage)
            log +=
                ToolCallRecorded(
                    ToolCall(call.id, SessionSamples.task, SessionSamples.session, call.name, args!!, "查天气"),
                )
            val result =
                log.add(
                    ToolResultRecorded(call.id, call.name, ToolResult(ToolStatus.OK, listOf(Part.Text("晴，22°C")))),
                )
            val second = log.request()
            val (answer) = call("tool result", model, second)
            assertEquals(StopReason.END_TURN, answer.stopReason)
            assertTrue(
                answer.parts
                    .filterIsInstance<Part.Text>()
                    .joinToString("")
                    .contains("22"),
                "the answer uses the tool result (value not shown)",
            )
            log += AssistantMessage(1, profile.id, modelId, answer.parts, answer.stopReason, answer.usage)
            // A prune the Anthropic projection leaves out of the request (history only appended).
            log.add(CompactionPrune(listOf(Part.Text("[结果已省略]"))), SurfaceOp.Replace(result, result))
            log += UserMessage(UserMessageKind.USER, listOf(Part.Text("用一句话复述刚才查到的天气，不要调用工具。")))
            val third = log.request()
            val (again) = call("follow-up", model, third)
            assertEquals(StopReason.END_TURN, again.stopReason)
            assertAppendOnly(first, second)
            assertAppendOnly(second, third)
        }

    /** A session log in memory, with each request derived from it as the loop does. */
    private inner class Log(
        prepared: RequestSettings,
    ) {
        val header =
            RequestHeader(
                provider = profile.id,
                model = modelId,
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                wire = prepared.wire,
                system = listOf(PromptBlock("system", "你是测试助手。回答尽量简短。")),
                tools = listOf(Kit.WEATHER),
                cache = CacheLayout.DEFAULT,
                contextEdits = prepared.contextEdits,
                maxOutputTokens = MAX_TOKENS,
                reasoning = prepared.reasoning,
            )
        private val records = mutableListOf(SessionRecord(1, 0, SessionSamples.start))

        /** Appends [event]; returns its seq. */
        fun add(
            event: SessionEvent,
            surfaceOp: SurfaceOp = SurfaceOp.Append,
        ): Long = (records.size + 1L).also { records += SessionRecord(it, 0, event, surfaceOp) }

        operator fun plusAssign(event: SessionEvent) {
            add(event)
        }

        fun request(): ChatRequest = header.toRequest(deriveMessages(records))
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
     * model-layer §6: a request, without its moving cache breakpoints, has the system and tools of
     * the one before it, whose message blocks (each with its role: consecutive messages of a role
     * merge on the wire) are a prefix of its own.
     */
    private fun assertAppendOnly(
        first: ChatRequest,
        second: ChatRequest,
    ) {
        val wire = MessagesWire(profile.id, modelId)

        fun body(request: ChatRequest) =
            withoutCache(
                wire.body(request, WireRules.of(request, CredentialKind.API_KEY), ImageData.NONE),
            ) as JsonObject

        fun blocks(body: JsonObject): List<JsonElement> =
            body["messages"]!!.jsonArray.flatMap { message ->
                val role = (message as JsonObject).getValue("role")
                message.getValue("content").jsonArray.map { JsonArray(listOf(role, it)) }
            }
        val (before, after) = body(first) to body(second)
        assertTrue(before["system"] == after["system"], "the system prompt is unchanged (values not shown)")
        assertTrue(before["tools"] == after["tools"], "the tools are unchanged (values not shown)")
        val earlier = blocks(before)
        assertTrue(earlier == blocks(after).take(earlier.size), "history is append-only (bodies not shown)")
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
        const val ASK = "巴黎现在天气怎么样？请调用 get_weather 工具，reason 填“查天气”。"
        const val DEFAULT_BUDGET = 6
        const val MAX_TOKENS = 4096
        const val SIDE = 64
        val TIMEOUT = 3.minutes
        val sent = AtomicInteger()
        val recorded = AtomicInteger()

        @Volatile var stopped = false
    }
}
