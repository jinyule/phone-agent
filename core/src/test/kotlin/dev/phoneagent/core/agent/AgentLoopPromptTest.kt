@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `answer` returns `true` exactly when its reply is the prompt's answer (TaskRuntime.answer). */
class AgentLoopPromptTest {
    private val card = ConfirmCard(ToolCallId("c1"), ConfirmReason.OUTWARD, "send", target = "小王")

    private class Waiting(
        val harness: LoopHarness,
        val loop: AgentLoop,
        val tool: FakeTool,
        val prompt: Phase.Waiting,
    )

    /** A task waiting for the confirmation of its first call (the re-check then allows it). */
    private suspend fun TestScope.waitingForConfirmation(config: LoopConfig = LoopConfig()): Waiting {
        var asked = false
        val policy =
            PolicyEngine { _, _, _ ->
                if (asked) {
                    Verdict.Allow
                } else {
                    asked = true
                    Verdict.Confirm(card)
                }
            }
        val tool = FakeTool("send", RiskClass.OUTWARD)
        val model = FakeChatModel().calls(call("c1", "send")).answers("好了")
        val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
        val harness = LoopHarness(this, model, tools = listOf(tool), policy = policy, screen = screen, config = config)
        val loop = harness.start()
        loop.followup(text("给小王发消息"))
        val prompt = loop.phase.first { it is Phase.Waiting } as Phase.Waiting
        return Waiting(harness, loop, tool, prompt)
    }

    @Test
    fun `an answer right after a stop is refused and the call never runs`() =
        runTest {
            val waiting = waitingForConfirmation()
            waiting.loop.stop(StopCause.USER)
            assertFalse(waiting.loop.answer(waiting.prompt.promptId!!, UserReply.Approve))
            advanceUntilIdle()

            val events = waiting.harness.events()
            assertEquals(emptyList<Any>(), waiting.tool.calls)
            assertEquals(listOf(PromptWithdrawn(waiting.prompt.promptId)), events.only<PromptWithdrawn>())
            assertEquals(emptyList<ApprovalDecided>(), events.only<ApprovalDecided>())
        }

    @Test
    fun `an answer taken at the moment the confirmation expires counts`() =
        runTest {
            val waiting = waitingForConfirmation(LoopConfig(confirmTimeoutMs = 5_000))
            advanceTimeBy(5_000) // the expiry is due now, and has not run yet
            val taken = waiting.loop.answer(waiting.prompt.promptId!!, UserReply.Approve)
            runCurrent()
            advanceUntilIdle()

            assertTrue(taken)
            val decided =
                waiting.harness
                    .events()
                    .only<ApprovalDecided>()
                    .single()
            assertEquals(ConfirmResult.APPROVED, decided.result)
            assertEquals(1, waiting.tool.calls.size)
        }

    @Test
    fun `an expired confirmation takes no answer`() =
        runTest {
            val waiting = waitingForConfirmation(LoopConfig(confirmTimeoutMs = 5_000))
            advanceTimeBy(5_001)
            assertFalse(waiting.loop.answer(waiting.prompt.promptId!!, UserReply.Approve))
            advanceUntilIdle()

            assertEquals(
                ConfirmResult.EXPIRED,
                waiting.harness
                    .events()
                    .only<ApprovalDecided>()
                    .single()
                    .result,
            )
            assertEquals(emptyList<Any>(), waiting.tool.calls)
        }
}
