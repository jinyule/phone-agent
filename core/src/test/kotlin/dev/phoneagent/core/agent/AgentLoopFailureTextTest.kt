@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.handOff
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * No exception's text reaches the session log: an interceptor's failure in `turn/end` and a
 * surface move's failure in `handoff/end` keep a fixed text and the exception's class name only
 * (message, cause and `toString` may quote the screen, typed input or credentials).
 */
class AgentLoopFailureTextTest {
    // Test data only: a test card number, the documentation phone number, fake secrets.
    private val secrets = listOf("6222020200112233445", "13800138000", "pw-test-not-real", "sk-test-not-a-real-key")

    private fun leak(): String = "card ${secrets[0]} phone ${secrets[1]} password ${secrets[2]}"

    private fun cause(): IOException = IOException("token ${secrets[3]}")

    private fun assertNoSecretIn(harness: LoopHarness) {
        val log =
            harness.storage.files.values
                .joinToString("\n") { it.decodeToString() }
        for (secret in secrets) assertTrue(secret !in log, "the log keeps no $secret")
    }

    @Test
    fun `an interceptor's failure ends the turn with its class only`() =
        runTest {
            val crash =
                Interceptor<PreStepInput, PreStepResult> { _, _ -> throw IllegalStateException(leak(), cause()) }
            val interceptors = Interceptors(requestError = emptyList(), preStep = listOf(crash))
            val harness = LoopHarness(this, FakeChatModel(), interceptors = interceptors)
            val loop = harness.start()
            loop.followup(text("付款"))
            advanceUntilIdle()

            val ended = harness.events().only<TurnEnded>().single()
            assertEquals(TurnEndReason.FAILED, ended.reason)
            assertEquals("failed with java.lang.IllegalStateException", ended.detail)
            assertNoSecretIn(harness)
        }

    /** Fails handing the task over or taking it back with [failure]. */
    private class FailingSurfaces(
        private val hand: IOException?,
        private val back: IOException?,
    ) : SurfaceManager {
        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            hand?.let { throw it }
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            back?.let { throw it }
            return ticket.from
        }
    }

    @Test
    fun `a failed move of the task ends the handoff with its class only`() =
        runTest {
            val noHand = handOver(FailingSurfaces(hand = IOException(leak(), cause()), back = null), answer = false)
            assertEquals(
                "hand over failed: java.io.IOException",
                noHand
                    .events()
                    .only<HandoffEnded>()
                    .single()
                    .detail,
            )
            assertNoSecretIn(noHand)

            val noBack = handOver(FailingSurfaces(hand = null, back = IOException(leak(), cause())), answer = true)
            val ended = noBack.events().only<HandoffEnded>().single()
            assertEquals(HandoffResult.FAILED, ended.result)
            assertEquals("take back failed: java.io.IOException", ended.detail)
            assertNoSecretIn(noBack)
        }

    /** One `send` the policy hands off over [surfaces]; the user finishes it when [answer]. */
    private suspend fun kotlinx.coroutines.test.TestScope.handOver(
        surfaces: SurfaceManager,
        answer: Boolean,
    ): LoopHarness {
        val model = FakeChatModel().calls(call("c1", "send")).answers("好了")
        val send = FakeTool("send", RiskClass.OUTWARD)
        val harness =
            LoopHarness(this, model, tools = listOf(send), policy = ScriptedPolicy(handOff), surfaces = surfaces)
        val loop = harness.start()
        loop.followup(text("发消息"))
        if (answer) loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
        loop.awaitPhase(Phase.Done)
        return harness
    }
}
