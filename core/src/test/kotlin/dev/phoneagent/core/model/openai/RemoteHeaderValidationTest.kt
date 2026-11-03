package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A response header HTTP does not allow (here a `Retry-After` with a non-ASCII character and the
 * request's key) never fails the call with OkHttp's validation error, which quotes the value: the
 * failure is the status's own [ModelError] with the header taken as absent, and the response is
 * closed. Assertions never print the key.
 */
class RemoteHeaderValidationTest {
    @StartStop
    private val server = Kit.server()

    private val client: OkHttpClient = HttpTimeouts().client()
    private val adapters get() = Kit.adapters(Keys(KEY), client)

    private fun enqueueRateLimited() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(TOO_MANY_REQUESTS)
                .addHeader("Content-Type", "application/json")
                .addHeaderLenient("Retry-After", "é $KEY")
                .body("{}")
                .build(),
        )
    }

    /** Fails when [text] holds the key; never prints it. */
    private fun assertClean(
        what: String,
        text: String,
    ) = assertFalse(text.contains(KEY), "$what holds the key (value not shown)")

    /** The failure [block] ends with: clean, the 429's own [ModelError], and no response left open. */
    private suspend fun assertRateLimited(block: suspend () -> Unit) {
        // Any type: a failure message must not print the error before it is checked.
        val error = assertThrows<Throwable> { block() }
        assertClean("the error", "${error.message} $error ${error.stackTraceToString()}")
        assertTrue(error is ModelError.RateLimited, "the 429 fails as rate limited")
        assertNull((error as ModelError.RateLimited).retryAfterMs, "the unusable Retry-After counts as absent")
        assertResponseClosed()
    }

    private fun assertResponseClosed() =
        assertEquals(
            0,
            client.connectionPool.connectionCount() - client.connectionPool.idleConnectionCount(),
            "no connection stays allocated to an open response",
        )

    private fun model(protocol: ModelProtocol) =
        Kit.profile(server.base(), protocol = protocol).let { profile ->
            adapters.model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions.defaultsFor(profile))
        }

    @Test
    fun `Chat Completions`() =
        runTest {
            enqueueRateLimited()
            assertRateLimited { model(ModelProtocol.OPENAI_CHAT_COMPLETIONS).send(Kit.request()) }
        }

    @Test
    fun `Responses`() =
        runTest {
            enqueueRateLimited()
            assertRateLimited { model(ModelProtocol.OPENAI_RESPONSES).send(Kit.request()) }
        }

    @Test
    fun `listing the models`() =
        runTest {
            enqueueRateLimited()
            assertRateLimited { adapters.probe().listModels(Kit.profile(server.base())) }
        }

    @Test
    fun `the trial call of test and save`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(NOT_FOUND).build())
            enqueueRateLimited()
            val outcome: Any =
                try {
                    adapters.probe().testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), "t")
                } catch (escaped: IllegalArgumentException) {
                    // OkHttp's validation error, should it get out of the probe: checked like a result.
                    escaped
                }
            assertClean("the outcome", (outcome as? Throwable)?.stackTraceToString() ?: outcome.toString())
            val failed = outcome as? ProbeResult.Failed
            assertEquals(ProbeStep.TOOL_CALL, failed?.step, "the trial step fails")
            assertEquals(ModelErrorKind.RATE_LIMITED, failed?.kind)
            assertResponseClosed()
        }

    private companion object {
        /** A placeholder secret without a credential shape. */
        const val KEY = "plain-placeholder-retry-after-0001"
        const val TOO_MANY_REQUESTS = 429
        const val NOT_FOUND = 404
    }
}
