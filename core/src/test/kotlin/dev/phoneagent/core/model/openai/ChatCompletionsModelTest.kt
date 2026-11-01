package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.assertBearer
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatCompletionsModelTest {
    @StartStop
    private val server = MockWebServer()

    private val textReply =
        Kit.sse(
            """{"id":"c","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}""",
            """{"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )

    private fun model(
        profile: ProviderProfile = Kit.profile(server.base(), headers = mapOf("X-Title" to "PhoneAgent")),
        keys: Keys = Keys(),
        vision: Boolean = false,
        blobs: BlobSource = Kit.blobs(),
        client: OkHttpClient = HttpTimeouts().client(),
    ): ChatModel = Kit.adapters(keys, client).model(profile, Kit.MODEL, Kit.capabilities(vision = vision), blobs)

    @Test
    fun `posts a streamed request with the key and profile headers`() =
        runTest {
            server.enqueueSse(textReply)
            val reply = model().complete(Kit.request())
            assertEquals(listOf(Part.Text("Hi")), reply.parts)
            assertEquals(StopReason.END_TURN, reply.stopReason)
            val sent = server.next()
            assertEquals("POST", sent.method)
            assertEquals("/v1/chat/completions", sent.url.encodedPath)
            sent.assertBearer(Kit.FAKE_KEY)
            assertEquals("PhoneAgent", sent.headers["X-Title"])
            assertEquals("text/event-stream", sent.headers["Accept"])
            assertTrue(sent.headers["Content-Type"]!!.startsWith("application/json"))
            val body = sent.json()
            assertEquals(JsonPrimitive("test-model"), body["model"])
            assertEquals(JsonPrimitive(false), body["parallel_tool_calls"])
            assertEquals(JsonPrimitive(true), (body["stream_options"] as JsonObject)["include_usage"])
        }

    @Test
    fun `no secret means no authorization header`() =
        runTest {
            server.enqueueSse(textReply)
            server.enqueueSse(textReply)
            val keys = Keys(null)
            model(Kit.profile(server.base(), credential = Credential.None), keys).complete(Kit.request())
            server.next().assertBearer(null)
            assertEquals(listOf(Credential.None to false), keys.asked)
            model(keys = Keys("   ")).complete(Kit.request())
            server.next().assertBearer(null)
        }

    @Test
    fun `a secret a header cannot carry fails without sending or echoing it`() =
        runTest {
            val broken = model(keys = Keys("sk-test\nnot-a-real-key"))
            val error = assertThrows<ModelError.Auth> { broken.complete(Kit.request()) }
            assertFalse(error.message!!.contains("not-a-real-key"))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `rejected optional fields are dropped once and stay off`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Unrecognized request argument supplied: parallel_tool_calls"}}""",
            )
            server.enqueueError(
                400,
                """{"error":{"message":"stream_options is not supported","param":"stream_options"}}""",
            )
            server.enqueueSse(textReply)
            server.enqueueSse(textReply)
            val model = model()
            model.complete(Kit.request())
            val first = server.next().json()
            val second = server.next().json()
            val third = server.next().json()
            assertTrue("parallel_tool_calls" in first && "stream_options" in first)
            assertFalse("parallel_tool_calls" in second)
            assertTrue("stream_options" in second)
            assertFalse("parallel_tool_calls" in third || "stream_options" in third)
            model.complete(Kit.request())
            val later = server.next().json()
            assertFalse("parallel_tool_calls" in later || "stream_options" in later)
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `other 400s fail at once`() =
        runTest {
            server.enqueueError(400, """{"error":{"message":"messages[1]: bad role","param":"messages"}}""")
            val error = assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            assertEquals("messages", error.param)
            assertEquals(1, server.requestCount)
            // Once both optional fields are off, a 400 naming them is an ordinary error.
            val quiet = Kit.profile(server.base(), preset = "kimi")
            server.enqueueError(400, """{"error":{"message":"bad parallel_tool_calls"}}""")
            assertThrows<ModelError.InvalidRequest> {
                model(quiet).complete(Kit.request())
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `retry-after reaches the caller and nothing is retried inside`() =
        runTest {
            server.enqueueError(
                429,
                """{"error":{"message":"Rate limit","code":"rate_limit_exceeded"}}""",
                "retry-after" to "7",
            )
            val error = assertThrows<ModelError.RateLimited> { model().complete(Kit.request()) }
            assertEquals(7000L, error.retryAfterMs)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `html error pages keep their words`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body("<html>Bad Gateway</html>")
                    .build(),
            )
            val error = assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertEquals(502, error.status)
            assertTrue(error.message!!.contains("Bad Gateway"))
        }

    @Test
    fun `invalid conversations fail before anything is sent`() =
        runTest {
            val call = Part.ToolCall(ToolCallId("c1"), "get_weather", "{}")
            val request = Kit.request(Kit.user("hi"), Message(Role.ASSISTANT, listOf(call)))
            assertThrows<ModelError.InvalidRequest> { model().complete(request) }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `vision models send blob bytes and fail on missing ones before sending`() =
        runTest {
            val screen = Kit.blob('a')
            val image = Message(Role.USER, listOf(Part.Text("Look"), Part.Image(screen, "image/png", 2, 2)))
            assertThrows<ModelError.InvalidRequest> { model(vision = true).complete(Kit.request(image)) }
            assertEquals(0, server.requestCount)
            server.enqueueSse(textReply)
            model(vision = true, blobs = Kit.blobs(screen to "PNG".toByteArray())).complete(Kit.request(image))
            val content = ((server.next().json()["messages"] as JsonArray)[1] as JsonObject)["content"] as JsonArray
            val url = ((content[1] as JsonObject)["image_url"] as JsonObject)["url"]
            assertEquals(JsonPrimitive("data:image/png;base64,UE5H"), url)
        }

    @Test
    fun `models without vision never read blobs`() =
        runTest {
            server.enqueueSse(textReply)
            val image = Message(Role.USER, listOf(Part.Image(Kit.blob('a'), "image/png", 2, 2)))
            val untouchable = BlobSource { error("blobs must not be read") }
            model(blobs = untouchable).complete(Kit.request(image))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a dropped connection mid-stream is a network error`() =
        runTest {
            val head = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}\n\n"
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(CuttingBody(head))
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
            val deltas = Deltas()
            val error = assertThrows<ModelError.Network> { model().complete(Kit.request(), deltas) }
            assertEquals("Hi", deltas.text, "the connection broke mid-stream")
            assertTrue(error.cause is java.io.IOException)
            assertFalse(error.cause is java.net.SocketTimeoutException, "the cut, not a timeout, ended the read")
        }

    @Test
    fun `provider values that break invariants are bad responses`() =
        runTest {
            val longId = "x".repeat(300)
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"$longId",""" +
                        """"function":{"name":"get_weather","arguments":"{}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"tool_calls":""" +
                        """[{"index":0,"id":"c","function":{"arguments":"{}"}}]}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            )
            val nameless = assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            assertTrue(nameless.message!!.contains("has no name"))
        }

    @Test
    fun `json replies may follow blank lines and use content arrays and refusals`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body(
                        "\n\n{\"choices\":[{\"index\":0,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"}," +
                            "{\"type\":\"image\"},{\"type\":\"text\",\"text\":\"b\"}]},\"finish_reason\":\"weird\"}]}",
                    ).build(),
            )
            val reply = model().complete(Kit.request())
            assertEquals(listOf(Part.Text("ab")), reply.parts)
            assertEquals(StopReason.OTHER, reply.stopReason)
            server.enqueueSse(Kit.sse("""{"choices":[{"index":0,"delta":{"refusal":"No."},"finish_reason":"stop"}]}"""))
            assertEquals("refusal", assertThrows<ModelError.Refusal> { model().complete(Kit.request()) }.category)
            server.enqueueSse(
                Kit.sse("""{"choices":[{"index":0,"delta":{"content":{"odd":1}},"finish_reason":null}]}""", "[1]"),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        }

    @Test
    fun `odd but harmless chunks are tolerated`() =
        runTest {
            server.enqueueSse(
                Kit.sse(
                    """{"id":"","model":"","choices":[],"usage":{"completion_tokens":3}}""",
                    """{"error":null,"choices":[{"delta":{"reasoning_content":"r1","reasoning":"other field"}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[""" +
                        """{"id":"t1","function":{"name":"get_","arguments":{"city":"Paris"}}},""" +
                        """{"function":{"name":"weather","arguments":null}},"not an object"]}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"max_tokens"}],""" +
                        """"usage":{"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":3,""" +
                        """"completion_tokens":-1}}""",
                ),
            )
            val deltas = Deltas()
            val reply = model().complete(Kit.request(), deltas)
            val reasoning = reply.parts[0] as Part.Reasoning
            assertEquals("r1", reasoning.summary)
            assertEquals(Part.ToolCall(ToolCallId("t1"), "get_weather", "{\"city\":\"Paris\"}"), reply.parts[1])
            assertEquals(StopReason.MAX_TOKENS, reply.stopReason)
            val usage = checkNotNull(reply.usage)
            assertEquals(8, usage.inputTokens)
            assertEquals(5, usage.cacheReadTokens)
            assertEquals(0, usage.outputTokens)
            assertEquals(JsonObject(mapOf("finish_reason" to JsonPrimitive("max_tokens"))), reply.providerMetadata)
            assertEquals("r1", deltas.reasoning)
        }

    @Test
    fun `cancelling the caller cancels the HTTP call`() =
        runTest {
            val release = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        StallingBody(
                            Kit.sse("""{"choices":[{"index":0,"delta":{"content":"Hi"}}]}""", done = false),
                            release,
                        ),
                    ).build(),
            )
            val listener =
                object : EventListener() {
                    override fun canceled(call: Call) = canceled.countDown()
                }
            val client =
                HttpTimeouts()
                    .client()
                    .newBuilder()
                    .eventListener(listener)
                    .build()
            val firstDelta = CompletableDeferred<Unit>()
            try {
                val job = launch { model(client = client).complete(Kit.request()) { firstDelta.complete(Unit) } }
                firstDelta.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertEquals(0L, canceled.count, "the HTTP call was cancelled")
                assertEquals(1L, release.count, "the read ended before the server finished the body")
            } finally {
                release.countDown()
            }
        }

    /** Writes [head], then holds the response open until [release] (or the client goes away). */
    private class StallingBody(
        private val head: String,
        private val release: CountDownLatch,
    ) : MockResponseBody {
        override val contentLength: Long = -1

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(head)
            sink.flush()
            release.await(STALL_LIMIT_S, TimeUnit.SECONDS)
        }
    }

    /** Promises [CUT_LENGTH] bytes but writes only [head]; the server then closes the socket. */
    private class CuttingBody(
        private val head: String,
    ) : MockResponseBody {
        override val contentLength: Long = CUT_LENGTH

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(head)
            sink.flush()
        }
    }

    private companion object {
        const val STALL_LIMIT_S = 30L
        const val CUT_LENGTH = 100_000L
    }
}
