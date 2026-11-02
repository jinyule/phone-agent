package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.SecretRef
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.WireProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Shared fixtures of the Anthropic adapter tests. Secrets are obvious placeholders. */
internal object Kit {
    val PROVIDER = ProviderId("claude")
    val MODEL = ModelId("claude-opus-5-5")
    const val FAKE_KEY = "sk-test-not-a-real-key"
    const val NOW_MS = 1_793_500_000_000L
    val KEY = Credential.ApiKey(SecretRef("claude-key"))

    /** A 64-hex blob ref made of [digit]. */
    fun blob(digit: Char): BlobRef = BlobRef(digit.toString().repeat(BLOB_REF_LENGTH))

    val WEATHER_SCHEMA: JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") { put("type", "string") }
                putJsonObject("reason") { put("type", "string") }
            }
            putJsonArray("required") {
                add("city")
                add("reason")
            }
        }
    val WEATHER = ToolDef("get_weather", "查询城市当前天气。", WEATHER_SCHEMA)

    val CAPABILITIES: ModelCapabilities = checkNotNull(AnthropicPresets.capabilities(AnthropicPresets.MAIN_MODEL))

    fun profile(
        baseUrl: String = AnthropicPresets.OFFICIAL_BASE_URL,
        credential: Credential = KEY,
        headers: Map<String, String> = emptyMap(),
    ): ProviderProfile =
        ProviderProfile(
            id = PROVIDER,
            displayName = "Claude",
            protocol = ModelProtocol.ANTHROPIC_MESSAGES,
            baseUrl = baseUrl,
            credential = credential,
            headers = headers,
        )

    /** The snapshot a request to [profile] carries, with official-endpoint defaults unless [settings] say otherwise. */
    fun wire(
        profile: ProviderProfile = profile(),
        reasoning: ReasoningLevel? = ReasoningLevel.HIGH,
        settings: AnthropicSettings = AnthropicSettings.defaultsFor(profile(), MODEL),
        capabilities: ModelCapabilities = CAPABILITIES,
    ): WireProfile = AnthropicWire.snapshot(profile, MODEL, capabilities, reasoning, settings)

    fun user(text: String): Message = Message(Role.USER, listOf(Part.Text(text)))

    fun assistant(vararg parts: Part): Message = Message(Role.ASSISTANT, parts.toList())

    fun tool(vararg results: Part.ToolResult): Message = Message(Role.TOOL, results.toList())

    fun call(
        id: String,
        args: String = """{"city":"巴黎","reason":"查天气"}""",
    ): Part.ToolCall = Part.ToolCall(ToolCallId(id), WEATHER.name, args)

    fun result(
        id: String,
        vararg parts: Part,
        isError: Boolean = false,
    ): Part.ToolResult = Part.ToolResult(ToolCallId(id), parts.toList(), isError)

    @Suppress("LongParameterList") // Named test defaults; every caller sets one or two.
    fun request(
        vararg messages: Message,
        tools: List<ToolDef> = listOf(WEATHER),
        system: List<String> = listOf("你是手机助手。", "每步只做一个动作。"),
        reasoning: ReasoningLevel? = ReasoningLevel.HIGH,
        wire: WireProfile = wire(reasoning = reasoning),
        cache: CacheLayout = CacheLayout.DEFAULT,
        contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
        maxOutputTokens: Int? = null,
    ): ChatRequest =
        ChatRequest(
            system = system.mapIndexed { index, text -> PromptBlock("block$index", text) },
            tools = tools,
            messages = messages.toList().ifEmpty { listOf(user("巴黎天气怎么样？")) },
            wire = wire,
            cache = cache,
            contextEdits = contextEdits,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
        )

    @Suppress("InjectDispatcher") // Real sockets: the I/O dispatcher is what production uses.
    fun adapters(
        credentials: CredentialResolver = Keys(),
        client: OkHttpClient = AnthropicTimeouts().client(),
    ): AnthropicAdapters = AnthropicAdapters(client, credentials, { NOW_MS }, Dispatchers.IO)

    /** A model on [server] with the production client. */
    fun model(
        server: MockWebServer,
        credential: Credential = KEY,
        credentials: CredentialResolver = Keys(),
        blobs: BlobSource = blobs(),
    ): ChatModel = adapters(credentials).model(profile(server.base(), credential), MODEL, CAPABILITIES, blobs)

    /** Blobs from a map; unknown refs fail like a missing file. */
    fun blobs(vararg entries: Pair<BlobRef, ByteArray>): BlobSource {
        val map = entries.toMap()
        return BlobSource { ref -> map[ref] ?: throw IOException("no blob $ref") }
    }

    /** Resource text under `core/src/test/resources/wire/`. */
    fun wireText(path: String): String =
        checkNotNull(Kit::class.java.getResource("/wire/$path")) { "missing fixture wire/$path" }.readText()

    fun wireExists(path: String): Boolean = Kit::class.java.getResource("/wire/$path") != null

    val pretty: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** Pretty JSON as fixtures store it. */
    fun prettyText(json: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), json) + "\n"

    /** Compares [actual] with the golden JSON at `wire/[path]` ([assertWire]). */
    fun assertGolden(
        path: String,
        actual: JsonElement,
    ) = assertWire(path, prettyText(actual))

    /**
     * Compares [actual] with the fixture text at `wire/[path]`. Tests never write fixtures: on a
     * mismatch (or a missing fixture) the actual text goes to `build/wire-actual/[path]` and the
     * test fails. To accept it, review the diff and copy it over, e.g.
     * `cp -R core/build/wire-actual/anthropic/. core/src/test/resources/wire/anthropic/`.
     */
    fun assertWire(
        path: String,
        actual: String,
    ) {
        val expected = if (wireExists(path)) wireText(path) else null
        if (expected == actual) return
        keepUnderBuild("$ACTUAL_SUBDIR/$path", actual)
        val what = if (expected == null) "is missing" else "differs"
        assertEquals(
            expected,
            actual,
            "fixture wire/$path $what; the actual output is in $ACTUAL_DIR/$path (review, then copy to accept)",
        )
    }

    /** Writes [text] to `build/[relative]` (test output for review, never a fixture). */
    fun keepUnderBuild(
        relative: String,
        text: String,
    ) {
        val path = Path.of(BUILD_DIR, relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    /** Anthropic SSE: each `(event, data)` as `event:` and `data:` lines. */
    fun sse(vararg events: Pair<String, String>): String =
        events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }

    /** A short text reply: `Hi` in two deltas, `end_turn`. */
    val TEXT_REPLY: String =
        sse(
            "message_start" to
                """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",""" +
                """"model":"claude-opus-5-5","content":[],"stop_reason":null,""" +
                """"usage":{"input_tokens":12,"output_tokens":1}}}""",
            "content_block_start" to
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"H"}}""",
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"i"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )

    fun MockWebServer.enqueueSse(
        body: String,
        chunkSize: Int = Int.MAX_VALUE,
    ) {
        enqueue(
            MockResponse
                .Builder()
                .addHeader("Content-Type", "text/event-stream")
                .chunkedBody(body, chunkSize)
                .build(),
        )
    }

    fun MockWebServer.enqueueError(
        code: Int,
        body: String,
        vararg headers: Pair<String, String>,
    ) {
        val builder =
            MockResponse
                .Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        enqueue(builder.build())
    }

    /**
     * The address mock servers listen on and are reached at: a literal IPv4 loopback, never
     * `localhost`, whose first address depends on the machine (and where another listener on the
     * other one would get the request).
     */
    const val LOOPBACK = "127.0.0.1"

    /** A started mock server listening on [LOOPBACK] only; the test closes it. */
    fun loopbackServer(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName(LOOPBACK), 0) }

    /** The base URL of a [loopbackServer], by its literal address. */
    fun MockWebServer.base(): String = "http://$LOOPBACK:$port"

    fun MockWebServer.next(): RecordedRequest =
        checkNotNull(takeRequest(TAKE_TIMEOUT_S, TimeUnit.SECONDS)) { "no request" }

    fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(checkNotNull(body).utf8()) as JsonObject

    /**
     * Checks that the request carries [secret] in `x-api-key` (or, with [bearer], `Authorization`)
     * and nothing in the other one. Failures never print header values.
     */
    fun RecordedRequest.assertKey(
        secret: String?,
        bearer: Boolean = false,
    ) {
        val apiKey = headers["x-api-key"]
        val authorization = headers["Authorization"]
        val matches =
            when {
                secret == null -> apiKey == null && authorization == null
                bearer -> authorization == "Bearer $secret" && apiKey == null
                else -> apiKey == secret && authorization == null
            }
        assertTrue(matches, "credential headers are not the expected ones (values are not shown)")
    }

    private const val BUILD_DIR = "build"
    private const val ACTUAL_SUBDIR = "wire-actual"
    private const val ACTUAL_DIR = "$BUILD_DIR/$ACTUAL_SUBDIR"
    private const val BLOB_REF_LENGTH = 64
    private const val TAKE_TIMEOUT_S = 5L
}

/** A resolver handing out [values] in order (the last one repeats); records what was asked. */
internal class Keys(
    private vararg val values: String? = arrayOf(Kit.FAKE_KEY),
) : CredentialResolver {
    val asked: MutableList<Pair<Credential, Boolean>> = CopyOnWriteArrayList()

    override suspend fun secret(
        credential: Credential,
        rejected: Boolean,
    ): String? {
        asked += credential to rejected
        return values[minOf(asked.size - 1, values.size - 1)]
    }
}

/** Collects deltas. */
internal class Deltas : (Delta) -> Unit {
    val all: MutableList<Delta> = mutableListOf()

    override fun invoke(delta: Delta) {
        all += delta
    }

    val text: String get() = all.filterIsInstance<Delta.Text>().joinToString("") { it.text }
    val reasoning: String get() = all.filterIsInstance<Delta.Reasoning>().joinToString("") { it.text }
    val started: List<Delta.ToolCallStarted> get() = all.filterIsInstance<Delta.ToolCallStarted>()

    fun args(id: ToolCallId): String =
        all
            .filterIsInstance<Delta.ToolCallArgs>()
            .filter {
                it.id == id
            }.joinToString("") { it.fragment }
}
