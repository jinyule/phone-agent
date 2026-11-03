package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Numbers in the opaque reasoning of a Responses reply reach the session log, and a request
 * rebuilt from it, as kotlinx.serialization writes them back: `1.23456e5` becomes `123456.0`
 * (model-layer §2). The echo check reads that form too, so a short numeric key the number only
 * shows written back still fails the reply; a number it cannot write back at all (`1e400`) fails
 * the reply with fixed text, since the log could not record it. The Chat adapter keeps no model
 * JSON it writes back (its reasoning is text, its arguments are kept as written). Each runs on a
 * stream and on one JSON body. Assertions never print the key.
 */
class WrittenBackNumberTest {
    @StartStop
    private val server = Kit.server()

    private fun model(): ChatModel {
        val profile = Kit.profile(server.base(), protocol = ModelProtocol.OPENAI_RESPONSES)
        return Kit.adapters(Keys(NUMERIC)).model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), WireOptions())
    }

    /** Queues a reply whose only output is [item], streamed or ([streamed] false) as one JSON body. */
    private fun enqueue(
        item: String,
        streamed: Boolean,
    ) {
        if (streamed) {
            server.enqueueSse(
                "data: {\"type\":\"response.completed\",\"response\":" +
                    "{\"status\":\"completed\",\"output\":[$item]}}\n\n",
            )
        } else {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("""{"id":"resp_1","object":"response","status":"completed","output":[$item]}""")
                    .build(),
            )
        }
    }

    @Test
    fun `reasoning holding the key only once a number is written back is rejected`() =
        runTest {
            for (number in listOf("1.23456e5", "1.23456E+5", "12345.6e1")) {
                for (streamed in listOf(true, false)) {
                    enqueue("""{"type":"reasoning","id":"rs_1","summary":[],"score":$number}""", streamed)
                    val error = assertThrows<ModelError.BadResponse>("$number") { model().send(Kit.request()) }
                    assertEquals("bad response: ${CredentialEcho.DETAIL}", error.message, number)
                    assertTrue(error.discardsStreamed, number)
                    assertFalse(error.stackTraceToString().contains(NUMERIC), "the error holds the key (not shown)")
                }
            }
        }

    @Test
    fun `a number written back the same way is kept`() =
        runTest {
            for (streamed in listOf(true, false)) {
                enqueue("""{"type":"reasoning","id":"rs_1","summary":[],"score":1.5e3}""", streamed)
                assertEquals(1, model().send(Kit.request()).parts.size, "1500.0 holds no form of the key")
            }
        }

    @Test
    fun `reasoning holding a number no encoder can write back fails with fixed text`() =
        runTest {
            for (streamed in listOf(true, false)) {
                enqueue("""{"type":"reasoning","id":"rs_1","summary":[],"score":1e400}""", streamed)
                val error = assertThrows<ModelError.BadResponse>("$streamed") { model().send(Kit.request()) }
                assertEquals("bad response: $UNWRITABLE_NUMBER", error.message)
                assertFalse(error.stackTraceToString().contains("1e400"), "nothing prints the value")
            }
        }

    private companion object {
        /** A short numeric key, such as a placeholder a local service was given. */
        const val NUMERIC = "123456"
    }
}
