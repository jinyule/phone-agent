package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.openai.Kit.assertBearer
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResponsesModelTest {
    private fun event(json: String) = "data: $json\n\n"

    @StartStop
    private val server = MockWebServer()

    private val completed =
        "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"output\":" +
            "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}]}}\n\n"

    private fun model(
        credential: Credential = Kit.KEY,
        keys: Keys = Keys(),
    ): ChatModel {
        val profile = Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES, credential = credential)
        return Kit.adapters(keys).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs())
    }

    @Test
    fun `posts a stateless streamed request`() =
        runTest {
            server.enqueueSse(completed)
            val reply = model().complete(Kit.request(maxOutputTokens = 100))
            assertEquals(listOf(Part.Text("Hi")), reply.parts)
            val sent = server.next()
            assertEquals("/v1/responses", sent.url.encodedPath)
            sent.assertBearer(Kit.FAKE_KEY)
            val body = sent.json()
            assertEquals(JsonPrimitive(false), body["store"])
            assertEquals(JsonPrimitive(true), body["stream"])
            assertEquals(JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))), body["include"])
            assertEquals(JsonPrimitive(100), body["max_output_tokens"])
            assertFalse("previous_response_id" in body)
        }

    @Test
    fun `plan sign-in refreshes the token once on 401`() =
        runTest {
            server.enqueueError(401, """{"error":{"message":"token expired"}}""")
            server.enqueueSse(completed)
            val keys = Keys("plan-token-old", "plan-token-new")
            model(Kit.PLAN, keys).complete(Kit.request(maxOutputTokens = 100))
            assertEquals(listOf(Kit.PLAN to false, Kit.PLAN to true), keys.asked)
            server.next().assertBearer("plan-token-old")
            val retried = server.next()
            retried.assertBearer("plan-token-new")
            assertFalse("max_output_tokens" in retried.json(), "plan requests drop fields the channel refuses")
        }

    @Test
    fun `a second 401 in plan mode means signing in again`() =
        runTest {
            server.enqueueError(401, "{}")
            server.enqueueError(401, "{}")
            assertThrows<ModelError.SignInExpired> { model(Kit.PLAN, Keys("a", "b")).complete(Kit.request()) }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `api keys are never refreshed`() =
        runTest {
            server.enqueueError(401, """{"error":{"message":"Incorrect API key","code":"invalid_api_key"}}""")
            val keys = Keys()
            assertThrows<ModelError.Auth> { model(keys = keys).complete(Kit.request()) }
            assertEquals(1, keys.asked.size)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `plan 403 is not eligible`() =
        runTest {
            server.enqueueError(403, """{"detail":"Your plan does not include this."}""")
            assertThrows<ModelError.PlanNotEligible> { model(Kit.PLAN).complete(Kit.request()) }
        }

    @Test
    fun `models without encrypted reasoning get no include, for good`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Encrypted content is not supported with this model.",""" +
                    """"param":"include","code":"unsupported_parameter"}}""",
            )
            server.enqueueSse(completed)
            server.enqueueSse(completed)
            val model = model()
            model.complete(Kit.request())
            assertTrue("include" in server.next().json())
            assertFalse("include" in server.next().json())
            model.complete(Kit.request())
            assertFalse("include" in server.next().json())
        }

    @Test
    fun `include is recognized by message or code, other 400s fail`() =
        runTest {
            server.enqueueError(400, """{"error":{"message":"reasoning.encrypted_content is unavailable"}}""")
            server.enqueueSse(completed)
            model().complete(Kit.request())
            assertEquals(2, server.requestCount)
            server.enqueueError(400, """{"error":{"message":"bad include","code":"include"}}""")
            server.enqueueSse(completed)
            model().complete(Kit.request())
            assertEquals(4, server.requestCount)
            server.enqueueError(400, """{"error":{"message":"input[0]: bad","param":"input"}}""")
            assertEquals("input", assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }.param)
            server.enqueueError(500, """{"error":{"message":"include broke"}}""")
            assertThrows<ModelError.Server> { model().complete(Kit.request()) }
            assertEquals(6, server.requestCount)
        }

    @Test
    fun `encrypted reasoning comes back verbatim and is replayed`() =
        runTest {
            val item =
                """{"id":"rs_9","type":"reasoning","summary":[],"encrypted_content":"gAAAA-test",""" +
                    """"content":[{"type":"reasoning_text","text":"raw"}]}"""
            server.enqueueSse(
                event("""{"type":"response.output_item.done","item":$item}""") +
                    event(
                        """{"type":"response.reasoning_text.delta","item_id":"rs_9","content_index":0,"delta":"raw"}""",
                    ) +
                    event("""{"type":"response.completed","response":{"status":"completed"}}"""),
            )
            server.enqueueSse(completed)
            val model = model()
            val deltas = Deltas()
            val reply = model.complete(Kit.request(), deltas)
            val reasoning = reply.parts.single() as Part.Reasoning
            assertEquals(ReasoningKind.RESPONSES_ENCRYPTED, reasoning.kind)
            assertEquals("raw", reasoning.summary)
            assertEquals("raw", deltas.reasoning)
            assertEquals(StopReason.END_TURN, reply.stopReason)
            server.next()
            val history =
                Kit.request(
                    Kit.user("Hi"),
                    dev.phoneagent.core.model
                        .Message(dev.phoneagent.core.model.Role.ASSISTANT, reply.parts),
                )
            model.complete(history)
            val input = server.next().json()["input"] as JsonArray
            assertEquals(
                kotlinx.serialization.json.Json
                    .parseToJsonElement(item),
                input[1],
            )
        }

    @Test
    fun `stream failures and odd events`() =
        runTest {
            server.enqueueSse(
                event(
                    """{"type":"response.failed","response":""" +
                        """{"error":{"code":"server_is_overloaded","message":"busy"}}}""",
                ),
            )
            assertThrows<ModelError.Overloaded> { model().complete(Kit.request()) }
            server.enqueueSse(event("""{"type":"error","error":{"code":"insufficient_quota","message":"pay"}}"""))
            assertThrows<ModelError.QuotaExceeded> { model().complete(Kit.request()) }
            server.enqueueSse(event("""not json"""))
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(event("""[1]"""))
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(
                event("""{"type":"response.output_item.done","item":{"type":"function_call","name":"x"}}""") +
                    event("""{"type":"response.completed"}"""),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueueSse(
                event(
                    """{"type":"response.output_item.done","item":{"type":"function_call","name":"x","call_id":" "}}""",
                ) +
                    event("""{"type":"response.completed"}"""),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .body("{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"later\"}}")
                    .build(),
            )
            assertThrows<ModelError.RateLimited> { model().complete(Kit.request()) }
            server.enqueueSse(
                event(
                    """{"type":"response.output_item.added",""" +
                        """"item":{"type":"function_call","name":"x","call_id":" "}}""",
                ),
            )
            assertThrows<ModelError.BadResponse> { model().complete(Kit.request()) }
        }

    @Test
    fun `events by name, done markers, hosted items and incomplete replies`() =
        runTest {
            server.enqueueSse(
                "event: response.output_text.delta\ndata: {\"delta\":\"A\"}\n\n" +
                    event("""{"type":"response.output_item.added","item":{"type":"message"}}""") +
                    event("""{"type":"response.function_call_arguments.delta","item_id":"unknown","delta":"{}"}""") +
                    event("""{"type":"response.output_item.done","item":{"type":"web_search_call"}}""") +
                    event("""{"type":"response.output_item.done","item":{"type":"message","content":[]}}""") +
                    event("""[DONE]""") +
                    "event: response.incomplete\ndata: {}\n\n",
            )
            val deltas = Deltas()
            val reply = model().complete(Kit.request(), deltas)
            assertEquals(emptyList<Part>(), reply.parts)
            assertEquals(StopReason.OTHER, reply.stopReason)
            assertEquals("A", deltas.text)
            server.enqueueSse(event("""{"type":"response.completed"}"""))
            assertEquals(StopReason.END_TURN, model().complete(Kit.request()).stopReason)
        }
}
