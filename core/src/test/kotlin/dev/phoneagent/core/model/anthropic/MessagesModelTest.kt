package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.anthropic.Kit.assertKey
import dev.phoneagent.core.model.anthropic.Kit.base
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import dev.phoneagent.core.model.anthropic.Kit.json
import dev.phoneagent.core.model.anthropic.Kit.next
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

/** The HTTP side of one `complete()`: endpoint, headers, the one request, and what comes back. */
class MessagesModelTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    @Test
    fun `posts the mapped body to v1 messages with version, betas and the key`() =
        runTest {
            server.enqueueSse(Kit.TEXT_REPLY)
            val deltas = Deltas()
            val reply = Kit.model(server).complete(Kit.request(), deltas)
            val sent = server.next()
            assertEquals("/v1/messages", sent.url.encodedPath)
            assertEquals("POST", sent.method)
            assertEquals("2026-10-21", sent.headers["anthropic-version"])
            assertEquals(
                "server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01",
                sent.headers["anthropic-beta"],
            )
            assertEquals("text/event-stream", sent.headers["Accept"])
            assertTrue(sent.headers["Content-Type"]!!.startsWith("application/json"))
            sent.assertKey(Kit.FAKE_KEY)
            val request = Kit.request()
            val expected =
                MessagesWire(
                    Kit.PROVIDER,
                    Kit.MODEL,
                ).body(request, WireRules.of(request, CredentialKind.API_KEY), ImageData.NONE)
            assertEquals(expected, sent.json(), "the body is exactly the mapping")
            assertEquals(listOf(Part.Text("Hi")), reply.parts)
            assertEquals("Hi", deltas.text)
            assertEquals(listOf(Delta.Text("H"), Delta.Text("i")), deltas.all)
        }

    @Test
    fun `bearer auth, extra headers, no betas, and no credential header without a key`() =
        runTest {
            server.enqueueSse(Kit.TEXT_REPLY)
            val gateway = Kit.profile(server.base(), headers = mapOf("X-Gateway-Route" to "anthropic"))
            val settings = AnthropicSettings(auth = AnthropicAuth.BEARER, apiVersion = "2023-06-01")
            val wire = Kit.wire(gateway, settings = settings)
            Kit.adapters().model(gateway, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs()).complete(Kit.request(wire = wire))
            val sent = server.next()
            sent.assertKey(Kit.FAKE_KEY, bearer = true)
            assertEquals("2023-06-01", sent.headers["anthropic-version"])
            assertEquals("anthropic", sent.headers["X-Gateway-Route"])
            assertNull(sent.headers["anthropic-beta"])

            server.enqueueSse(Kit.TEXT_REPLY)
            val local = Kit.profile(server.base(), credential = Credential.None)
            val keyless = Kit.wire(local, settings = AnthropicSettings()).copy(credential = CredentialKind.NONE)
            val resolver = Keys(null)
            Kit
                .adapters(
                    resolver,
                ).model(local, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
                .complete(Kit.request(wire = keyless))
            server.next().assertKey(null)
            assertEquals(listOf(Credential.None to false), resolver.asked, "the key is never asked for again")
        }

    @Test
    fun `thinking and calls of a reply are sent back verbatim in the next request`() =
        runTest {
            server.enqueueSse(Kit.wireText("anthropic/thinking-tool-fragments.sse"))
            val model = Kit.model(server)
            val ask = Kit.user("巴黎天气怎么样？")
            val first = model.complete(Kit.request(ask))
            server.next()
            assertEquals(StopReason.TOOL_USE, first.stopReason)
            val call = first.toolCalls.single()
            server.enqueueSse(Kit.TEXT_REPLY)
            val history =
                arrayOf(
                    ask,
                    Message(Role.ASSISTANT, first.parts),
                    Kit.tool(Part.ToolResult(call.id, listOf(Part.Text("晴")), isError = false)),
                )
            model.complete(Kit.request(*history, cache = CacheLayout.NONE))
            val echoed =
                server
                    .next()
                    .json()["messages"]!!
                    .jsonArray[1]
                    .jsonObject["content"]!!
                    .jsonArray
            val stored = first.parts.filterIsInstance<Part.Reasoning>().map { it.opaque }
            assertEquals(stored, listOf(echoed[0], echoed[2]), "thinking blocks go back byte for byte")
            assertEquals("tool_use", echoed[3].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(
                "巴黎",
                echoed[3]
                    .jsonObject["input"]!!
                    .jsonObject["city"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `nothing is sent for a snapshot the adapter cannot honor or a key HTTP cannot carry`() =
        runTest {
            val model = Kit.model(server)
            val error =
                assertThrows<ModelError.InvalidRequest> {
                    model.complete(Kit.request(wire = Kit.wire().copy(credential = CredentialKind.NONE)))
                }
            assertEquals("wire.credential", error.param)
            val badKey = Kit.model(server, credentials = Keys("sk-test-with\nnewline"))
            val auth = assertThrows<ModelError.Auth> { badKey.complete(Kit.request()) }
            assertFalse(auth.message!!.contains("newline"), "the key is not echoed")
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `provider values that break neutral invariants are bad responses`() =
        runTest {
            val tooManyThinking =
                Kit.sse(
                    "message_start" to
                        """{"type":"message_start","message":{"id":"m","model":"x","usage":{"output_tokens":1}}}""",
                    "message_delta" to
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},""" +
                        """"usage":{"output_tokens":2,"output_tokens_details":{"thinking_tokens":5}}}""",
                    "message_stop" to """{"type":"message_stop"}""",
                )
            server.enqueueSse(tooManyThinking)
            val error = assertThrows<ModelError.BadResponse> { Kit.model(server).complete(Kit.request()) }
            assertTrue(error.message!!.contains("invariant"), error.message)
            val badId =
                Kit.sse(
                    "message_start" to """{"type":"message_start","message":{"id":"m","model":"x"}}""",
                    "content_block_start" to
                        """{"type":"content_block_start","index":0,""" +
                        """"content_block":{"type":"tool_use","id":"tool\u0007u","name":"t"}}""",
                )
            server.enqueueSse(badId)
            assertThrows<ModelError.BadResponse> { Kit.model(server).complete(Kit.request()) }
        }

    @Test
    fun `a whole JSON reply is read like a stream`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(Kit.wireText("anthropic/json-body.json"))
                    .build(),
            )
            val deltas = Deltas()
            val reply: ChatResponse = Kit.model(server).complete(Kit.request(), deltas)
            assertEquals(StopReason.TOOL_USE, reply.stopReason)
            assertEquals(reply.toolCalls.single().argsJson, deltas.args(reply.toolCalls.single().id))
        }

    @Test
    fun `cancelling the caller aborts the exchange`() =
        runTest {
            val opened = CompletableDeferred<Unit>()
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(Kit.TEXT_REPLY.substringBefore("event: content_block_stop"))
                    .build(),
            )
            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    Kit.model(server).complete(Kit.request()) { if (it is Delta.Text) opened.complete(Unit) }
                }
            opened.await()
            call.cancel()
            assertThrows<CancellationException> { call.await() }
        }

    @Test
    fun `the model reports what it was created for and refuses other protocols`() {
        val model = Kit.adapters().model(Kit.profile(), Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
        assertEquals(Kit.PROVIDER, model.info.provider)
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, model.info.protocol)
        val responses =
            ProviderProfile(Kit.PROVIDER, "R", ModelProtocol.OPENAI_RESPONSES, "https://api.example.com/v1", Kit.KEY)
        assertThrows<IllegalArgumentException> {
            Kit.adapters().model(
                responses,
                Kit.MODEL,
                Kit.CAPABILITIES,
                Kit.blobs(),
            )
        }
        assertThrows<IllegalArgumentException> { AnthropicTimeouts(readMs = 0) }
        AnthropicAdapters(Keys())
        assertEquals(ReasoningLevel.HIGH, AnthropicPresets.defaultReasoning(dev.phoneagent.core.model.ModelRole.MAIN))
    }
}
