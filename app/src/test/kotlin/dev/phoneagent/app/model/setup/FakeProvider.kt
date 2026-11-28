package dev.phoneagent.app.model.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * An OpenAI-compatible Chat Completions server on loopback for "test and save": a model listing,
 * and a trial that calls the tool with the token it was asked for. [listingStatus] and
 * [trialStatus] make either step fail with the provider's own error body; [holdTrials] keeps the
 * trial calls waiting until released.
 */
internal class FakeProvider : AutoCloseable {
    private val server = MockWebServer()

    @Volatile var listingStatus: Int = 200

    @Volatile var trialStatus: Int = 200

    @Volatile var listing: String = """{"object":"list","data":[{"id":"main-model","context_length":32768}]}"""

    @Volatile private var hold: Hold? = null

    /** Trials wait from now on, each until [Hold.release] lets it answer. */
    fun holdTrials(): Hold = Hold().also { hold = it }

    /** Held trials: each one that starts waiting is reported once to [arrived]. */
    class Hold {
        private val arrivals = Semaphore(0)
        private val passes = Semaphore(0)

        /** Waits until one more trial waits; `false` when none does within [seconds]. */
        fun arrived(seconds: Long = WAIT_SECONDS): Boolean = arrivals.tryAcquire(seconds, TimeUnit.SECONDS)

        /** Lets [count] trials answer, waiting ones first; by default every one from now on. */
        fun release(count: Int = ALL) = passes.release(count)

        internal fun await() {
            arrivals.release()
            passes.acquire()
        }

        private companion object {
            const val WAIT_SECONDS = 10L
            const val ALL = 1_000
        }
    }

    /** Every request: method, path, Authorization header and model asked for. */
    val requests: MutableList<Seen> = CopyOnWriteArrayList()

    data class Seen(
        val method: String,
        val path: String,
        val authorization: String?,
        val model: String?,
    )

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
        server.start()
    }

    /** The base URL, `http://<loopback name>:port/v1` (the name MockWebServer reports). */
    val baseUrl: String get() = server.url("/v1").toString().removeSuffix("/")

    private fun answer(request: RecordedRequest): MockResponse {
        val body = request.body?.utf8()
        val sent = body?.let { Json.parseToJsonElement(it).jsonObject }
        requests +=
            Seen(
                request.method,
                request.url.encodedPath,
                request.headers["Authorization"],
                sent?.get("model")?.jsonPrimitive?.content,
            )
        return when {
            request.url.encodedPath.endsWith("/models") -> {
                if (listingStatus ==
                    200
                ) {
                    json(listing)
                } else {
                    error(listingStatus, "Invalid API key provided: ${request.headers["Authorization"]}")
                }
            }

            trialStatus != 200 -> {
                error(trialStatus, "The model does not exist")
            }

            else -> {
                hold?.await()
                trial(tokenOf(sent!!))
            }
        }
    }

    /** The token in the trial prompt (`Call confirm_connection with token "pa-…".`). */
    private fun tokenOf(sent: kotlinx.serialization.json.JsonObject): String {
        val prompt =
            sent["messages"]!!
                .jsonArray
                .last()
                .jsonObject["content"]!!
                .jsonPrimitive.content
        return Regex("token \"([^\"]+)\"").find(prompt)!!.groupValues[1]
    }

    private fun trial(token: String): MockResponse {
        val args = buildJsonObject { put("token", token) }.toString()
        val chunks =
            listOf(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                    """{"name":"confirm_connection","arguments":${JsonPrimitive(args)}}}]}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            )
        val sse = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        return MockResponse
            .Builder()
            .setHeader("Content-Type", "text/event-stream")
            .body(sse)
            .build()
    }

    private fun json(text: String) =
        MockResponse
            .Builder()
            .setHeader("Content-Type", "application/json")
            .body(text)
            .build()

    private fun error(
        status: Int,
        message: String,
    ) = MockResponse
        .Builder()
        .code(status)
        .setHeader("Content-Type", "application/json")
        .body("""{"error":{"message":"$message"}}""")
        .build()

    override fun close() {
        server.close()
    }
}
