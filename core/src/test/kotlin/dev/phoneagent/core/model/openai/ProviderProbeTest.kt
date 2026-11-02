package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.openai.Kit.assertBearer
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.literalUrl
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderProbeTest {
    @StartStop
    private val server = Kit.server()

    private val probe get() = Kit.adapters().probe()
    private val profile get() = Kit.profile(server.base())

    private val listing =
        """
        {"object":"list","data":[
          {"id":"test-model","context_length":131072,"architecture":{"input_modalities":["text","image"]},
           "supported_parameters":["tools","reasoning"]},
          {"id":"text-only","context_window":8192,"architecture":{"input_modalities":["text"]},"supported_parameters":["temperature"]},
          {"id":"test-model"},
          {"slug":"by-slug","context_length":0},
          {"id":""},
          {"id":"${"x".repeat(300)}"},
          "not an object"
        ]}
        """.trimIndent()

    private fun trialCall(args: String) =
        Kit.sse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_t","function":""" +
                """{"name":"confirm_connection","arguments":${JsonPrimitive(args)}}}]}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        )

    @Test
    fun `listings are parsed with what they say about each model`() =
        runTest {
            server.enqueue(MockResponse.Builder().body(listing).build())
            val models = probe.listModels(profile)!!
            assertEquals(
                listOf(
                    ListedModel(ModelId("test-model"), 131072, vision = true, tools = true),
                    ListedModel(ModelId("text-only"), 8192, vision = false, tools = false),
                    ListedModel(ModelId("by-slug")),
                ),
                models,
            )
            val sent = server.next()
            assertEquals("GET", sent.method)
            assertEquals("/v1/models", sent.url.encodedPath)
            sent.assertBearer(Kit.FAKE_KEY)
            server.enqueue(MockResponse.Builder().body("""{"models":[{"id":"m"}]}""").build())
            assertEquals(listOf(ListedModel(ModelId("m"))), probe.listModels(profile))
        }

    @Test
    fun `missing listings are null, broken ones fail`() =
        runTest {
            for (status in listOf(404, 405, 501)) {
                server.enqueueError(status, "{}")
                assertNull(probe.listModels(profile))
            }
            server.enqueueError(401, """{"error":{"message":"Invalid key"}}""")
            assertThrows<ModelError.Auth> { probe.listModels(profile) }
            server.enqueue(MockResponse.Builder().body("{<html>").build())
            assertTrue(
                assertThrows<ModelError.BadResponse> { probe.listModels(profile) }.message!!.contains("not JSON"),
            )
            server.enqueue(MockResponse.Builder().body("""{"object":"list"}""").build())
            assertThrows<ModelError.BadResponse> { probe.listModels(profile) }
            server.enqueue(MockResponse.Builder().body("[]").build())
            assertThrows<ModelError.BadResponse> { probe.listModels(profile) }
        }

    @Test
    fun `a real trial call passes and is recorded as verified`() =
        runTest {
            server.enqueue(MockResponse.Builder().body(listing).build())
            server.enqueueSse(trialCall("{\"token\":\"pa-1234\"}"))
            val capabilities = Kit.capabilities().copy(tools = false)
            val result = probe.testAndSave(profile, Kit.MODEL, capabilities, token = "pa-1234")
            result as ProbeResult.Passed
            assertEquals(true, result.candidateListed)
            assertEquals(3, result.models!!.size)
            assertTrue(result.capabilities.tools)
            assertEquals(setOf(VerifiedCapability.TOOL_CALL), result.capabilities.verified)
            server.next()
            val trial = server.next().json()
            val tool = ((trial["tools"] as JsonArray)[0] as JsonObject)["function"] as JsonObject
            assertEquals(JsonPrimitive("confirm_connection"), tool["name"])
            assertTrue(trial.toString().contains("pa-1234"))
        }

    @Test
    fun `test and save turns off what the provider refuses and reports the shape that passed`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueError(
                400,
                """{"error":{"message":"Unrecognized request argument supplied: parallel_tool_calls",""" +
                    """"param":"parallel_tool_calls","code":"unsupported_parameter"}}""",
            )
            server.enqueueError(400, """{"error":{"message":"extra_forbidden: body.stream_options"}}""")
            server.enqueueSse(trialCall("{\"token\":\"pa-1\"}"))
            val result = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            result as ProbeResult.Passed
            assertEquals(ChatQuirks(parallelToolCalls = false, streamUsage = false), result.wire.chat)
            server.next()
            val sent = List(3) { server.next().json() }
            assertTrue("parallel_tool_calls" in sent[0] && "stream_options" in sent[0])
            assertTrue("parallel_tool_calls" !in sent[1] && "stream_options" in sent[1])
            assertTrue("parallel_tool_calls" !in sent[2] && "stream_options" !in sent[2])
            assertEquals(4, server.requestCount)
            server.enqueueSse(trialCall("{}"))
            Kit
                .adapters()
                .model(
                    profile,
                    Kit.MODEL,
                    Kit.capabilities(),
                    Kit.blobs(),
                    result.wire,
                ).complete(Kit.request())
            assertEquals(
                sent[2].keys,
                server.next().json().keys,
                "a model created with the saved options sends that shape",
            )
        }

    @Test
    fun `responses trials drop include only when the param names it`() =
        runTest {
            val responses = Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES)
            val call =
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
                    "{\"type\":\"function_call\",\"call_id\":\"c\",\"name\":\"confirm_connection\"," +
                    "\"arguments\":\"{\\\"token\\\":\\\"pa-1\\\"}\"}]}}\n\n"
            server.enqueueError(404, "{}")
            server.enqueueError(
                400,
                """{"error":{"message":"Encrypted content is not supported.","param":"include"}}""",
            )
            server.enqueueSse(call)
            val passed =
                probe.testAndSave(
                    responses,
                    Kit.MODEL,
                    Kit.capabilities(),
                    token = "pa-1",
                ) as ProbeResult.Passed
            assertEquals(ResponsesQuirks(includeEncryptedReasoning = false), passed.wire.responses)
            server.enqueueError(404, "{}")
            server.enqueueError(
                400,
                """{"error":{"message":"Invalid 'input[1].encrypted_content'.",""" +
                    """"param":"input[1].encrypted_content"}}""",
            )
            val failed = probe.testAndSave(responses, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            assertEquals(ModelErrorKind.INVALID_REQUEST, (failed as ProbeResult.Failed).kind)
            server.enqueueError(404, "{}")
            server.enqueueError(400, """{"error":{"message":"please include a valid model"}}""")
            assertTrue(
                probe.testAndSave(responses, Kit.MODEL, Kit.capabilities(), token = "pa-1") is ProbeResult.Failed,
            )
            assertEquals(7, server.requestCount, "no retry without a structured include param")
        }

    @Test
    fun `a refusal that dropping a field does not cure fails with the provider's error`() =
        runTest {
            val stubborn =
                """{"error":{"message":"parallel_tool_calls is broken here","param":"parallel_tool_calls"}}"""
            server.enqueueError(404, "{}")
            server.enqueueError(400, stubborn)
            server.enqueueError(400, stubborn)
            val result = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            result as ProbeResult.Failed
            assertEquals(ModelErrorKind.INVALID_REQUEST, result.kind)
            assertTrue(result.message.contains("parallel_tool_calls"), result.message)
            assertEquals(3, server.requestCount, "each field is dropped at most once")
            server.enqueueError(404, "{}")
            server.enqueueError(400, """{"error":{"message":"bad stream_options","param":"messages"}}""")
            assertTrue(probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1") is ProbeResult.Failed)
            assertEquals(5, server.requestCount, "a structured param naming another field wins over the message")
        }

    @Test
    fun `a redirect naming an optional field is not a refusal of it`() =
        runTest {
            server.enqueueError(404, "{}")
            val location = server.literalUrl("/elsewhere/chat/completions?parallel_tool_calls=false&stream_options=x")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", location.toString())
                    .build(),
            )
            // Answers the retry the redirect must not cause.
            server.enqueueSse(trialCall("{\"token\":\"pa-1\"}"))
            val result = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            result as ProbeResult.Failed
            assertEquals(ModelErrorKind.INVALID_REQUEST, result.kind)
            assertTrue(result.message.contains("redirect"), result.message)
            assertEquals(2, server.requestCount, "the listing and one trial, no retry without a field")
        }

    @Test
    fun `only a 400 or 422 from the provider refuses an optional field`() =
        runTest {
            val names = """{"error":{"message":"stream_options is not supported","param":"stream_options"}}"""
            server.enqueueError(404, "{}")
            server.enqueueError(422, names)
            server.enqueueSse(trialCall("{\"token\":\"pa-1\"}"))
            val passed = probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1")
            assertEquals(ChatQuirks(streamUsage = false), (passed as ProbeResult.Passed).wire.chat, "a 422 counts")
            server.enqueueError(404, "{}")
            server.enqueueError(415, names)
            // Answers the retry a 415 must not cause.
            server.enqueueSse(trialCall("{\"token\":\"pa-1\"}"))
            assertTrue(probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "pa-1") is ProbeResult.Failed)
            assertEquals(5, server.requestCount, "another 4xx naming the field is no refusal of it")
        }

    @Test
    fun `without a listing the trial call alone decides`() =
        runTest {
            server.enqueueError(404, "{}")
            server.enqueueSse(trialCall("{\"token\":\" pa-1 \"}"))
            val result =
                probe.testAndSave(
                    profile,
                    ModelId("unlisted"),
                    Kit.capabilities(),
                    token = "pa-1",
                ) as ProbeResult.Passed
            assertNull(result.models)
            assertNull(result.candidateListed)
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"other"}]}""").build())
            server.enqueueSse(trialCall("{\"token\":\"pa-1\"}"))
            val unlisted = probe.testAndSave(profile, ModelId("unlisted"), Kit.capabilities(), token = "pa-1")
            assertEquals(false, (unlisted as ProbeResult.Passed).candidateListed)
        }

    @Test
    fun `failures name the step and keep the provider's words`() =
        runTest {
            server.enqueueError(401, """{"error":{"message":"Invalid API key: sk-te****"}}""")
            val listFailed =
                probe.testAndSave(
                    profile,
                    Kit.MODEL,
                    Kit.capabilities(),
                    token = "t",
                ) as ProbeResult.Failed
            assertEquals(ProbeStep.LIST_MODELS, listFailed.step)
            assertEquals(ModelErrorKind.AUTH, listFailed.kind)
            assertTrue(listFailed.message.contains("Invalid API key"))

            server.enqueueError(404, "{}")
            server.enqueueError(
                404,
                """{"error":{"message":"The model `nope` does not exist","code":"model_not_found"}}""",
            )
            val trialFailed =
                probe.testAndSave(
                    profile,
                    ModelId("nope"),
                    Kit.capabilities(),
                    token = "t",
                ) as ProbeResult.Failed
            assertEquals(
                ProbeResult.Failed(ProbeStep.TOOL_CALL, ModelErrorKind.INVALID_REQUEST, trialFailed.message),
                trialFailed,
            )
            assertTrue(trialFailed.message.contains("does not exist"))
        }

    @Test
    fun `answers that are not the trial call fail`() =
        runTest {
            fun failure(result: ProbeResult) = (result as ProbeResult.Failed).also { assertNull(it.kind) }.message
            server.enqueueError(404, "{}")
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"content":"I cannot use tools."},"finish_reason":"stop"}]}""",
                ),
            )
            val text = failure(probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "t"))
            assertTrue(text.contains("without calling the trial tool: I cannot use tools."))
            server.enqueueError(404, "{}")
            server.enqueueSse(trialCall("{\"token\":\"wrong\"}"))
            assertTrue(
                failure(
                    probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "t"),
                ).contains("unexpected arguments"),
            )
            server.enqueueError(404, "{}")
            server.enqueueSse(trialCall("{token=t"))
            assertTrue(
                failure(probe.testAndSave(profile, Kit.MODEL, Kit.capabilities(), token = "t")).contains("{token=t"),
            )
        }

    @Test
    fun `the trial request reads no images`() =
        runTest {
            assertThrows<java.io.IOException> { NO_IMAGES.bytes(Kit.blob('f')) }
        }

    @Test
    fun `the default token is random and must come back`() =
        runTest {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "GET") return MockResponse.Builder().code(404).build()
                        val token = Regex("pa-[0-9a-f-]{8}").find(request.body!!.utf8())!!.value
                        return MockResponse
                            .Builder()
                            .addHeader("Content-Type", "text/event-stream")
                            .body(trialCall("{\"token\":\"$token\"}"))
                            .build()
                    }
                }
            assertTrue(probe.testAndSave(profile, Kit.MODEL, Kit.capabilities()) is ProbeResult.Passed)
        }
}
