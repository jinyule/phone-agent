package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Every key is matched exactly, however short (model-layer §2), so "test and save" tells the user
 * when the key is what fails: a key the trial request itself contains (any correct reply would
 * echo it) fails before any request, and a reply that echoed the key fails with a typed reason
 * and readable text. Both point to another key, or to no key for a service that needs none.
 */
class ProbeCredentialGuidanceTest {
    @StartStop
    private val server = Kit.server()

    @ParameterizedTest(name = "key {0}")
    // `":"pa-` only in the arguments a correct reply sends: `{"token":"pa-…"}`.
    @ValueSource(strings = ["token", "pa", "a", "confirm", "\"token\":", "\":\"pa-"])
    fun `a key the trial request contains fails before any request`(key: String) =
        runTest {
            // Answers a regression that sends the requests anyway, so it fails at once.
            server.enqueueError(404, "{}")
            server.enqueueError(500, "{}")
            val probe = Kit.adapters(Keys(key)).probe()
            val failed =
                probe.testAndSave(
                    Kit.profile(server.base()),
                    Kit.MODEL,
                    Kit.capabilities(),
                ) as? ProbeResult.Failed
            assertEquals(0, server.requestCount, "no request is sent")
            assertEquals(ProbeFailureReason.CREDENTIAL_IN_TRIAL, failed?.reason)
            assertEquals(ProbeStep.TOOL_CALL, failed?.step)
            assertNull(failed?.kind, "no model error")
            assertTrue(failed?.message.orEmpty().contains("no key"), "the text points to choosing no key")
        }

    @Test
    fun `a reply that echoed the key fails with that reason and readable text`() =
        runTest {
            // A short key the trial request does not contain, but the fixed rejection text does.
            val key = "dis"
            server.enqueueError(404, "{}")
            server.enqueueSse(
                Kit.sse(
                    """{"choices":[{"index":0,"delta":{"content":${JsonPrimitive("Served on a dish.")}}}]}""",
                    """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ),
            )
            val result =
                Kit
                    .adapters(
                        Keys(key),
                    ).probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities())
            val failed = result as? ProbeResult.Failed
            assertEquals(ProbeFailureReason.CREDENTIAL_ECHOED, failed?.reason)
            assertEquals(ModelErrorKind.BAD_RESPONSE, failed?.kind)
            assertEquals("bad response: ${CredentialEcho.DETAIL}", failed?.message, "the fixed text is not masked")
        }

    @Test
    fun `other failures carry no reason`() =
        runTest {
            server.enqueueError(500, "{}")
            val result =
                Kit
                    .adapters(Keys("plain-placeholder-guidance-0001"))
                    .probe()
                    .testAndSave(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities())
            assertNull((result as ProbeResult.Failed).reason)
        }
}
