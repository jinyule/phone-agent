package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
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
import dev.phoneagent.core.ports.Clock
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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Shared fixtures of the OpenAI adapter tests. Secrets are obvious placeholders. */
internal object Kit {
    val PROVIDER = ProviderId("test")
    val MODEL = ModelId("test-model")
    const val FAKE_KEY = "sk-test-not-a-real-key"
    const val NOW_MS = 1_793_500_000_000L
    val KEY = Credential.ApiKey(SecretRef("test-key"))
    val PLAN = Credential.ChatGptPlan(SecretRef("test-plan"))

    /** A 64-hex blob ref made of [digit]. */
    fun blob(digit: Char): BlobRef = BlobRef(digit.toString().repeat(BLOB_REF_LENGTH))

    val WEATHER_SCHEMA: JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("city") { put("type", "string") } }
            putJsonArray("required") { add("city") }
        }
    val WEATHER = ToolDef("get_weather", "Current weather of a city.", WEATHER_SCHEMA)

    @Suppress("LongParameterList") // Named test defaults; every caller sets one or two.
    fun profile(
        baseUrl: String,
        protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        credential: Credential = KEY,
        reasoningField: String? = null,
        echoReasoning: Boolean = false,
        preset: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ProviderProfile =
        ProviderProfile(
            id = PROVIDER,
            displayName = "Test",
            protocol = protocol,
            baseUrl = baseUrl,
            credential = credential,
            headers = headers,
            reasoningField = reasoningField,
            echoReasoning = echoReasoning,
            preset = preset,
        )

    fun capabilities(
        vision: Boolean = false,
        levels: List<ReasoningLevel>? = null,
    ): ModelCapabilities =
        ModelCapabilities(tools = true, vision = vision, contextTokens = 128_000, reasoningLevels = levels)

    fun user(text: String): Message = Message(Role.USER, listOf(Part.Text(text)))

    fun request(
        vararg messages: Message,
        tools: List<ToolDef> = listOf(WEATHER),
        system: String? = "Be brief.",
        reasoning: ReasoningLevel? = null,
        maxOutputTokens: Int? = null,
    ): ChatRequest =
        ChatRequest(
            system = listOfNotNull(system?.let { PromptBlock("sys", it) }),
            tools = tools,
            messages = messages.toList().ifEmpty { listOf(user("Weather in Paris?")) },
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
        )

    /** Call ids `call_gen_1`, `call_gen_2`, … */
    fun ids(): () -> String {
        val next = AtomicInteger()
        return { "call_gen_${next.incrementAndGet()}" }
    }

    @Suppress("InjectDispatcher") // Real sockets: the I/O dispatcher is what production uses.
    fun adapters(
        credentials: CredentialResolver = Keys(),
        client: OkHttpClient = HttpTimeouts().client(),
    ): OpenAiAdapters = OpenAiAdapters(client, credentials, { NOW_MS }, Dispatchers.IO, ids())

    fun clock(): Clock = Clock { NOW_MS }

    /** Blobs from a map; unknown refs fail like a missing file. */
    fun blobs(vararg entries: Pair<BlobRef, ByteArray>): BlobSource {
        val map = entries.toMap()
        return BlobSource { ref -> map[ref] ?: throw IOException("no blob $ref") }
    }

    /**
     * [profile]'s default wire options, recovering calls leaked into the text in [formats]: by
     * default every format the route allows (a preset route's own; none on a custom route that
     * declared none, see [declaring]).
     */
    fun recovering(
        profile: ProviderProfile,
        formats: Set<LeakedCallFormat>? = null,
    ): WireOptions {
        val defaults = WireOptions.defaultsFor(profile)
        val recover = formats ?: defaults.allowedLeakedCalls(profile)
        return defaults.copy(chat = defaults.chat.copy(recoverLeakedCalls = recover))
    }

    /** Wire options of the custom route [profile] that declares [formats] and recovers them all. */
    fun declaring(
        profile: ProviderProfile,
        formats: Set<LeakedCallFormat>,
    ): WireOptions {
        val defaults = WireOptions.defaultsFor(profile)
        return defaults.copy(chat = defaults.chat.copy(leakedCallFormats = formats, recoverLeakedCalls = formats))
    }

    /** Resource text under `core/src/test/resources/wire/`. */
    fun wire(path: String): String =
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
     * `cp -R core/build/wire-actual/. core/src/test/resources/wire/` after a test run.
     */
    fun assertWire(
        path: String,
        actual: String,
    ) {
        val expected = if (wireExists(path)) wire(path) else null
        if (expected == actual) return
        val kept = Path.of(ACTUAL_DIR, path)
        Files.createDirectories(kept.parent)
        Files.writeString(kept, actual)
        val what = if (expected == null) "is missing" else "differs"
        org.junit.jupiter.api.Assertions.assertEquals(
            expected,
            actual,
            "fixture wire/$path $what; the actual output is in $ACTUAL_DIR/$path (review, then copy to accept)",
        )
    }

    /** `data:` events for [chunks], then `[DONE]`. */
    fun sse(
        vararg chunks: String,
        done: Boolean = true,
    ): String = chunks.joinToString("") { "data: $it\n\n" } + if (done) "data: [DONE]\n\n" else ""

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
     * The address every test server binds and every test client dials. Never `localhost`: OkHttp
     * tries `[::1]` first for it, and whatever listens there at the same port (an emulator's
     * ephemeral port) would get the request.
     */
    val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

    /** A test server bound to [LOOPBACK]; `@StartStop` finds it started and only stops it. */
    fun server(): MockWebServer = MockWebServer().apply { start(LOOPBACK, 0) }

    /** [path] on this server at the literal address it is bound to. */
    fun MockWebServer.literalUrl(path: String): HttpUrl =
        url(path).newBuilder().host(checkNotNull(socketAddress.address.hostAddress)).build()

    fun MockWebServer.base(): String = literalUrl("/v1").toString().removeSuffix("/")

    fun MockWebServer.next(): RecordedRequest =
        checkNotNull(takeRequest(TAKE_TIMEOUT_S, TimeUnit.SECONDS)) { "no request" }

    /**
     * Checks that the request carries `Bearer [secret]` (or no `Authorization` header for `null`).
     * Failures never print header values, so credentials stay out of test output.
     */
    fun RecordedRequest.assertBearer(secret: String?) {
        val header = headers["Authorization"]
        val matches = if (secret == null) header == null else header == "Bearer $secret"
        org.junit.jupiter.api.Assertions
            .assertTrue(matches, "Authorization header is not the expected one (values are not shown)")
    }

    fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(checkNotNull(body).utf8()) as JsonObject

    private const val ACTUAL_DIR = "build/wire-actual"
    private const val BLOB_REF_LENGTH = 64
    private const val TAKE_TIMEOUT_S = 5L
}

/**
 * The "secret straddles the cut" template: [filler] up to [secret], which starts half its length
 * before character [cut], then [tail] more filler. Text that is cut at [cut] before it is masked
 * leaves the first half of the secret behind.
 */
internal object Straddle {
    /** Shortest prefix [assertNoPrefix] looks for. */
    private const val MIN_PREFIX = 4

    fun text(
        cut: Int,
        secret: String,
        filler: Char,
        tail: Int = 0,
    ): String = filler.toString().repeat(cut - secret.length / 2) + secret + filler.toString().repeat(tail)

    /** Fails when [text] holds [secret] or a prefix of it of [MIN_PREFIX] or more characters; never prints it. */
    fun assertNoPrefix(
        what: String,
        text: String,
        secret: String,
    ) {
        val leaked = (secret.length downTo MIN_PREFIX).firstOrNull { text.contains(secret.take(it)) }
        org.junit.jupiter.api.Assertions.assertNull(
            leaked,
            "$what holds the first $leaked characters of the secret (value not shown)",
        )
    }
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

/** Collects deltas and checks their invariants against the final reply. */
internal class Deltas : (Delta) -> Unit {
    val all: MutableList<Delta> = mutableListOf()

    override fun invoke(delta: Delta) {
        all += delta
    }

    val text: String get() = all.filterIsInstance<Delta.Text>().joinToString("") { it.text }
    val reasoning: String get() = all.filterIsInstance<Delta.Reasoning>().joinToString("") { it.text }
    val started: List<Delta.ToolCallStarted> get() = all.filterIsInstance<Delta.ToolCallStarted>()

    fun args(id: ToolCallId): String =
        all.filterIsInstance<Delta.ToolCallArgs>().filter { it.id == id }.joinToString("") { it.fragment }
}

/**
 * The model for a route "test and save" never probed: [profile]'s default wire options, passed
 * explicitly (`OpenAiAdapters.model` has no default for them).
 */
internal fun OpenAiAdapters.unprobed(
    profile: ProviderProfile,
    model: ModelId,
    capabilities: ModelCapabilities,
    blobs: BlobSource,
): ChatModel = model(profile, model, capabilities, blobs, WireOptions.defaultsFor(profile))
