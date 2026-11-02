package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Secrets that providers echo back never reach error messages, causes or probe results (model-layer
 * §2): the secrets a request used become `***`, and key-shaped text is masked as a fallback.
 * Assertions only say whether a secret leaked, never print it.
 */
class RedactionTest {
    @StartStop
    private val server = MockWebServer()

    private fun chat(keys: Keys = Keys(KEY)): ChatModel =
        Kit.adapters(keys).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    private fun responses(
        credential: Credential,
        keys: Keys,
    ): ChatModel =
        Kit.adapters(keys).unprobed(
            Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES, credential = credential),
            Kit.MODEL,
            Kit.capabilities(),
            Kit.blobs(),
        )

    private fun assertClean(
        text: String,
        vararg secrets: String,
    ) {
        for ((index, secret) in secrets.withIndex()) {
            assertFalse(text.contains(secret), "secret #$index leaked (value not shown)")
        }
    }

    @Test
    fun `a 401 echoing the key keeps it out of the error and its trace`() =
        runTest {
            server.enqueueError(
                401,
                """{"error":{"message":"Incorrect API key provided: $KEY.","code":"invalid_api_key"}}""",
            )
            val error = assertThrows<ModelError.Auth> { chat().complete(Kit.request()) }
            assertClean(error.stackTraceToString(), KEY)
            assertTrue(error.message!!.contains("Incorrect API key provided: ***."), "the provider's words stay")
        }

    @Test
    fun `probe results never carry an echoed key`() =
        runTest {
            val probe = Kit.adapters(Keys(KEY)).probe()
            server.enqueueError(401, """{"error":{"message":"Incorrect API key provided: $KEY."}}""")
            val listing = probe.testAndSave(Kit.profile(server.base()), ModelId("m"), Kit.capabilities())
            assertClean(listing.toString(), KEY)
            server.enqueueError(404, """{"error":{"message":"no listing"}}""")
            server.enqueueError(401, """{"error":{"message":"key $KEY is revoked"}}""")
            val trial = probe.testAndSave(Kit.profile(server.base()), ModelId("m"), Kit.capabilities())
            assertTrue(trial is ProbeResult.Failed && trial.step == ProbeStep.TOOL_CALL, trial.toString())
            assertClean(trial.toString(), KEY)
        }

    @Test
    fun `a listing that is not JSON keeps the key out of the error and its cause`() =
        runTest {
            server.enqueue(MockResponse.Builder().body("{\"data\": [\"$KEY\"").build())
            val error =
                assertThrows<ModelError.BadResponse> {
                    Kit.adapters(Keys(KEY)).probe().listModels(Kit.profile(server.base()))
                }
            assertClean(error.stackTraceToString(), KEY)
        }

    @Test
    fun `an error chunk echoing the key keeps it out of the error`() =
        runTest {
            server.enqueueSse(Kit.sse("""{"error":{"message":"upstream rejected key $KEY","code":502}}"""))
            val chat = assertThrows<ModelError> { chat().complete(Kit.request()) }
            assertClean(chat.stackTraceToString(), KEY)
            server.enqueueSse(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"bad key $KEY\"," +
                    "\"code\":\"server_error\"}}}\n\n",
            )
            val responses = assertThrows<ModelError> { responses(Kit.KEY, Keys(KEY)).complete(Kit.request()) }
            assertClean(responses.stackTraceToString(), KEY)
        }

    @Test
    fun `a chunk that is not JSON keeps the key out of the error and its cause`() =
        runTest {
            server.enqueueSse(Kit.sse("""{"error": "key $KEY is invalid" ]"""))
            val error = assertThrows<ModelError.BadResponse> { chat().complete(Kit.request()) }
            assertClean(error.stackTraceToString(), KEY)
        }

    @Test
    fun `an HTML error page echoing the key keeps it out of the error`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(502)
                    .body("<html><body>Proxy saw Authorization: Bearer $KEY</body></html>")
                    .build(),
            )
            val error = assertThrows<ModelError.Server> { chat().complete(Kit.request()) }
            assertClean(error.stackTraceToString(), KEY)
        }

    @Test
    fun `OpenRouter metadata raw echoing the key is masked`() =
        runTest {
            server.enqueueError(
                400,
                """{"error":{"message":"Provider returned error","code":400,""" +
                    """"metadata":{"raw":"{\"error\":\"invalid x-api-key $KEY\"}","provider_name":"Up"}}}""",
            )
            val error = assertThrows<ModelError.InvalidRequest> { chat().complete(Kit.request()) }
            assertClean(error.stackTraceToString(), KEY)
            assertTrue(error.message!!.contains("Provider returned error"), "the diagnosis stays")
        }

    @Test
    fun `a secret hidden behind JSON escapes is masked once parsed`() =
        runTest {
            val plain = "plain-secret-not-real"
            server.enqueueError(400, """{"error":{"message":"bad key plain-\u0073ecret-not-real","param":"x"}}""")
            val error = assertThrows<ModelError.InvalidRequest> { chat(Keys(plain)).complete(Kit.request()) }
            assertClean(error.stackTraceToString(), plain)
        }

    @Test
    fun `key-shaped text the request did not use is masked too`() =
        runTest {
            val other = "sk-proj-not-a-real-key-0123456789"
            val token = "test.not-a-real-bearer-token"
            server.enqueueError(403, """{"error":{"message":"key $other / header Bearer $token refused"}}""")
            val error = assertThrows<ModelError> { chat().complete(Kit.request()) }
            assertClean(error.stackTraceToString(), other, token)
            assertTrue(error.message!!.contains("Bearer ***"), error.message)
        }

    @Test
    fun `after a plan token refresh the rejected token stays masked as well`() =
        runTest {
            val old = "plan-token-old-not-real"
            val new = "plan-token-new-not-real"
            server.enqueueError(401, """{"error":{"message":"token $old expired"}}""")
            server.enqueueError(400, """{"error":{"message":"stale token $old and new $new","param":"input"}}""")
            val error =
                assertThrows<ModelError.InvalidRequest> { responses(Kit.PLAN, Keys(old, new)).complete(Kit.request()) }
            assertClean(error.stackTraceToString(), old, new)
        }

    private companion object {
        const val KEY = "sk-test-echoed-not-a-real-key"
    }
}
