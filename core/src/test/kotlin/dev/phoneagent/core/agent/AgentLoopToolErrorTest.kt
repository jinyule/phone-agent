@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A tool that throws gets an ERROR result with a fixed text and the exception's class name only:
 * its message, cause and `toString` may quote the screen, typed input or credentials, and the
 * result goes to the session log and to the model.
 */
class AgentLoopToolErrorTest {
    // Test data only: a test card number, the documentation phone number, fake secrets.
    private val secrets = listOf("6222020200112233445", "13800138000", "pw-test-not-real", "sk-test-not-a-real-key")

    @Test
    fun `a tool's exception text reaches neither the log nor the next request, its class does`() =
        runTest {
            val leaky =
                FakeTool("pay", risk = RiskClass.WRITE) { _, _ ->
                    val cause = IOException("token ${secrets[3]}")
                    throw IllegalStateException("card ${secrets[0]} phone ${secrets[1]} password ${secrets[2]}", cause)
                }
            val model = FakeChatModel().calls(call("c1", "pay")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(leaky))
            val loop = harness.start()
            loop.followup(text("付款"))
            loop.awaitPhase(Phase.Done)

            val result =
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result
            assertEquals(ToolStatus.ERROR, result.status)
            assertEquals(
                "${LoopTexts().toolError}java.lang.IllegalStateException",
                (result.content.single() as Part.Text).text,
            )
            val log =
                harness.storage.files.values
                    .joinToString("\n") { it.decodeToString() }
            val nextRequest = model.requests[1].toString()
            for (secret in secrets) {
                assertTrue(secret !in log, "the log keeps no $secret")
                assertTrue(secret !in nextRequest, "the model sees no $secret")
            }
            assertTrue("java.lang.IllegalStateException" in nextRequest)
            LoopGolden.assertMatches(
                "tool-error.expected.txt",
                (result.content.single() as Part.Text).text,
            )
        }
}
