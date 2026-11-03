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
import dev.phoneagent.core.model.VerifiedCapability
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
            val reply = Kit.official(server).complete(Kit.request(wire = Kit.wire()), deltas)
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
            val request = Kit.request(wire = Kit.wire())
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
    fun `a snapshot made for the Claude API is refused on another endpoint instead of sending its betas`() =
        runTest {
            val official =
                listOf(
                    AnthropicSettings(serverFallback = true),
                    AnthropicSettings(prefixMismatch = PrefixMismatch.DROP_BLOCK),
                )
            for (settings in official) {
                server.enqueueSse(Kit.TEXT_REPLY) // would be consumed if the request were sent
                val wire = Kit.wire(settings = settings)
                val error =
                    assertThrows<ModelError.InvalidRequest> { Kit.model(server).complete(Kit.request(wire = wire)) }
                assertEquals("wire.options", error.param, settings.toString())
                assertTrue(error.detail.contains("made for another endpoint"), error.detail)
                assertFalse(error.retryable, "the same snapshot is refused again")
            }
            assertEquals(0, server.requestCount, "nothing was sent")
        }

    @Test
    fun `the Claude API host on another port or path is another endpoint`() =
        runTest {
            for (baseUrl in listOf("https://api.anthropic.com:8443", "https://api.anthropic.com/proxy")) {
                server.enqueueSse(Kit.TEXT_REPLY) // would be consumed if the request were sent
                val keys = Keys()
                val error =
                    assertThrows<ModelError.InvalidRequest> {
                        Kit.rerouted(server, baseUrl, keys).complete(Kit.request(wire = Kit.wire()))
                    }
                assertEquals("wire.options", error.param, baseUrl)
                assertTrue(error.detail.contains("make a new snapshot"), error.detail)
                assertEquals(emptyList<Any>(), keys.asked, "$baseUrl: no credential was resolved")
            }
            assertEquals(0, server.requestCount, "nothing was sent")
        }

    @Test
    fun `the Claude API in another letter case or with port 443 written out is the Claude API`() =
        runTest {
            for (baseUrl in listOf("https://API.Anthropic.COM", "https://api.anthropic.com:443")) {
                server.enqueueSse(Kit.TEXT_REPLY)
                Kit.rerouted(server, baseUrl).complete(Kit.request(wire = Kit.wire()))
                assertEquals(
                    "server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01",
                    server.next().headers["anthropic-beta"],
                    baseUrl,
                )
            }
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

    /**
     * The collections a model is made from belong to the caller, who may edit them afterwards
     * (fix-list r6, 16:2x): a snapshot given out before still completes, one request is sent, and it
     * carries the snapshot's headers and effort. A header the adapter sets itself, added to the
     * caller's map, would get a snapshot that shared the map refused before anything is sent.
     */
    @Test
    fun `an earlier snapshot still completes after the caller edits the collections the model was made from`() =
        runTest {
            val headers = mutableMapOf("x-client" to "phone-agent")
            val levels = mutableListOf(ReasoningLevel.LOW, ReasoningLevel.HIGH)
            val verified = mutableSetOf(VerifiedCapability.TOOL_CALL)
            val capabilities = Kit.CAPABILITIES.copy(reasoningLevels = levels, verified = verified)
            val profile = Kit.profile(server.base(), headers = headers)
            val model = Kit.adapters().model(profile, Kit.MODEL, capabilities, Kit.blobs())
            val settings = model.prepare(ReasoningLevel.HIGH)
            headers["x-client"] = "edited"
            headers["anthropic-beta"] = "edited"
            levels.remove(ReasoningLevel.HIGH)
            verified.clear()
            server.enqueueSse(Kit.TEXT_REPLY)
            model.complete(Kit.request(reasoning = settings.reasoning, wire = settings.wire))
            assertEquals(1, server.requestCount, "one request sent")
            val sent = server.next()
            assertEquals("phone-agent", sent.headers["x-client"])
            assertFalse(sent.headers.values("anthropic-beta").contains("edited"), "the caller's later header")
            val effort =
                sent
                    .json()["output_config"]
                    ?.jsonObject
                    ?.get("effort")
                    ?.jsonPrimitive
                    ?.content
            assertEquals("high", effort)
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
