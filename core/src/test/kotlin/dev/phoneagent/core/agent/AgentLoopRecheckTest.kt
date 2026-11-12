@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.c1
import dev.phoneagent.core.agent.PolicySamples.handOff
import dev.phoneagent.core.agent.PolicySamples.refuse
import dev.phoneagent.core.agent.PolicySamples.sendCard
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** The re-check after approval, on a fresh screen or on none (phase1 §3.8, contracts addendum §14). */
class AgentLoopRecheckTest {
    @Test
    fun `an approved call is judged again on a fresh screen before it runs`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Confirm(sendCard), Verdict.Allow)
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
            val run = send(policy, screen = screen)
            val waiting = run.loop.awaitPrompt()
            assertEquals(WaitReason.USER, waiting.reason)
            assertEquals(PromptId("prompt-${run.harness.records().last().seq}"), waiting.promptId)

            assertFalse(run.loop.answer(PromptId("prompt-999"), UserReply.Approve), "unknown prompt")
            assertFalse(run.loop.answer(waiting.promptId!!, UserReply.Answer("好")), "an answer does not fit a card")
            assertFalse(run.loop.answer(waiting.promptId, UserReply.HandoffDone), "nor does a handoff")
            assertFalse(run.loop.answer(waiting.promptId, UserReply.Decline), "nor does declining")
            assertTrue(run.loop.answer(waiting.promptId, UserReply.Approve))
            assertFalse(run.loop.answer(waiting.promptId, UserReply.Reject), "answered once")
            run.loop.awaitPhase(Phase.Done)

            assertEquals(1, run.tool.calls.size)
            assertEquals(listOf(null, sendCard), policy.contexts.map { it.approved })
            assertEquals(
                listOf(null, "s1"),
                policy.contexts.map { it.screen?.id?.value },
                "the re-check observes first",
            )
            val observed =
                run.harness
                    .events()
                    .only<ScreenObserved>()
                    .single()
            assertEquals(ToolCallId("c1"), observed.callId, "recorded under the call it re-checks")
            assertEquals(
                2,
                run.harness
                    .events()
                    .only<PolicyVerdictRecorded>()
                    .size,
            )
            assertEquals(
                ConfirmResult.APPROVED,
                run.harness
                    .events()
                    .only<ApprovalDecided>()
                    .single()
                    .result,
            )
            val asked =
                run.harness
                    .events()
                    .only<ApprovalAsked>()
                    .single()
            assertEquals(LoopConfig().confirmTimeoutMs, asked.expiresAt, "expiry from the virtual clock at 0")
        }

    @Test
    fun `the re-check's screen is the call's own, and a write that then runs hides it`() =
        runTest {
            // look's result shows only text: the policy still judges on the screen it recorded,
            // which can be newer than what the model saw (latestSnapshot). The re-check reads s2
            // under c1 and judges c1 on it; send then starts, and as it reads nothing back, c2 is
            // judged on no screen, neither s2 nor c0's s1 (a refused call keeps its S', below).
            val policy = ScriptedPolicy(Verdict.Allow, Verdict.Confirm(sendCard))
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"), ScriptedScreen.snapshotOf("s2"))
            val look =
                FakeTool("look") { _, context ->
                    context.screen!!.snapshot()
                    ToolResult.ok("seen")
                }
            val model =
                FakeChatModel()
                    .calls(call("c0", "look"))
                    .calls(call("c1", "send"))
                    .calls(call("c2", "look-later"))
                    .answers("ok")
            val tools = listOf(look, FakeTool("send", risk = RiskClass.OUTWARD), FakeTool("look-later"))
            val harness = LoopHarness(this, model, tools = tools, policy = policy, screen = screen)
            val loop = harness.start()
            loop.followup(text("给小王发消息"))
            loop.approveNext()
            loop.awaitPhase(Phase.Done)

            assertEquals(
                listOf(null, "s1", "s2", null),
                policy.contexts.map { it.screen?.id?.value },
                "nothing recorded yet; what c0 recorded; the re-check's own read; none after send started",
            )
            assertEquals(listOf(null, null, sendCard, null), policy.contexts.map { it.approved })
            val observed = harness.events().only<ScreenObserved>()
            assertEquals(listOf(ToolCallId("c0"), ToolCallId("c1")), observed.map { it.callId })
        }

    @Test
    fun `a call refused on its re-check still leaves the screen it read for the next call`() =
        runTest {
            val policy =
                ScriptedPolicy(Verdict.Confirm(sendCard), Verdict.Refuse("换了联系人", "联系人变了，先 phone_observe"))
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s2"))
            val model =
                FakeChatModel()
                    .calls(call("c1", "send"))
                    .calls(call("c2", "look-later"))
                    .answers("ok")
            val send = FakeTool("send", risk = RiskClass.OUTWARD)
            val tools = listOf(send, FakeTool("look-later"))
            val harness = LoopHarness(this, model, tools = tools, policy = policy, screen = screen)
            val loop = harness.start()
            loop.followup(text("给小王发消息"))
            loop.approveNext()
            loop.awaitPhase(Phase.Done)

            assertTrue(send.calls.isEmpty())
            assertEquals(listOf(null, "s2", "s2"), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a screen that changed under the approval is confirmed again and then runs`() =
        runTest {
            val changed = sendCard.copy(amount = null, target = "小王 (新)")
            val policy = ScriptedPolicy(Verdict.Confirm(sendCard), Verdict.Confirm(changed), Verdict.Allow)
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"), ScriptedScreen.snapshotOf("s2"))
            val run = send(policy, screen = screen)
            val first = run.loop.approveNext()
            run.loop.approveNext(after = first)
            run.loop.awaitPhase(Phase.Done)

            assertEquals(1, run.tool.calls.size)
            assertEquals(
                listOf(sendCard, changed),
                run.harness
                    .events()
                    .only<ApprovalAsked>()
                    .map { it.card },
                "the second card is the re-check's",
            )
            assertEquals(listOf(null, sendCard, changed), policy.contexts.map { it.approved })
            assertEquals(listOf(null, "s1", "s2"), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a call is confirmed again at most once and then refused with the reason`() =
        runTest {
            val again = Verdict.Confirm(sendCard)
            val run = send(ScriptedPolicy(again, again, again, Verdict.Allow))
            val first = run.loop.approveNext()
            run.loop.approveNext(after = first)
            run.loop.awaitPhase(Phase.Done)

            assertTrue(run.tool.calls.isEmpty(), "never runs on a third mismatch, even if a fourth check allowed it")
            assertEquals(ToolStatus.REFUSED, run.result().status)
            assertEquals(LoopTexts().changedSinceApproval, run.result().text())
            assertEquals(
                2,
                run.harness
                    .events()
                    .only<ApprovalAsked>()
                    .size,
            )
            assertEquals(
                3,
                run.harness
                    .events()
                    .only<PolicyVerdictRecorded>()
                    .size,
            )
        }

    @Test
    fun `the re-confirmation limit is configurable`() =
        runTest {
            val again = Verdict.Confirm(sendCard)
            val never = send(ScriptedPolicy(again, again), config = LoopConfig(maxReconfirmations = 0))
            never.loop.approveNext()
            never.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().changedSinceApproval, never.result().text())
            assertEquals(
                1,
                never.harness
                    .events()
                    .only<ApprovalAsked>()
                    .size,
            )

            val twice = send(ScriptedPolicy(again, again, again), config = LoopConfig(maxReconfirmations = 2))
            var prompt = twice.loop.approveNext()
            prompt = twice.loop.approveNext(after = prompt)
            twice.loop.approveNext(after = prompt)
            twice.loop.awaitPhase(Phase.Done)
            assertEquals(1, twice.tool.calls.size)
            assertEquals(
                3,
                twice.harness
                    .events()
                    .only<ApprovalAsked>()
                    .size,
            )
        }

    @Test
    fun `a page that needs a hand-off after approval is handed off`() =
        runTest {
            val run = send(ScriptedPolicy(Verdict.Confirm(sendCard), handOff))
            val confirmed = run.loop.approveNext()
            val handing = run.loop.awaitPrompt(after = confirmed)
            assertEquals(WaitReason.HANDOFF, handing.reason)
            assertTrue(run.loop.answer(handing.promptId!!, UserReply.HandoffDone))
            run.loop.awaitPhase(Phase.Done)

            assertTrue(run.tool.calls.isEmpty(), "the user did it; the call itself never runs")
            assertEquals(LoopTexts().handoffDone, run.result().text())
            assertEquals(
                handOff.kind,
                run.harness
                    .events()
                    .only<HandoffStarted>()
                    .single()
                    .kind,
            )
        }

    @Test
    fun `the re-check may refuse with its own hint`() =
        runTest {
            val run = send(ScriptedPolicy(Verdict.Confirm(sendCard), refuse))
            run.loop.answer(run.loop.awaitPrompt().promptId!!, UserReply.Approve)
            run.loop.awaitPhase(Phase.Done)
            assertEquals(refuse.hintForModel, run.result().text())
        }

    /**
     * Refuses without a screen and allows everything else: a stand-in for the rules' `call.no_screen`
     * (WP05 `RulesEngine`), so this test is about the runtime alone. `AgentLoopRealPolicyTest` runs
     * the re-check on no screen with the real rules.
     */
    private val needsScreen =
        PolicyEngine { _, _, ctx -> if (ctx.screen == null) refuse else Verdict.Allow }

    @Test
    fun `an unreadable screen at the re-check is judged as no screen, and the policy decides`() =
        runTest {
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
            val policy = ScriptedPolicy(Verdict.Confirm(sendCard))
            val run = send(policy, screen = screen)
            val waiting = run.loop.awaitPrompt()
            screen.unavailable = "secure window"
            run.loop.answer(waiting.promptId!!, UserReply.Approve)
            run.loop.awaitPhase(Phase.Done)

            assertNull(policy.contexts[1].screen)
            assertEquals(1, run.tool.calls.size, "this policy allows it")
            assertEquals(ToolStatus.OK, run.result().status)
            val observed = run.harness.events().only<ScreenObserved>()
            assertEquals(emptyList<ScreenObserved>(), observed.filter { it.callId == null }, "nothing was read")
        }

    @Test
    fun `a screen read slower than the re-check timeout is judged as no screen`() =
        runTest {
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
            var hang = false
            val slow =
                object : ScreenPort by screen {
                    override suspend fun snapshot(options: SnapshotOptions): ScreenSnapshot =
                        if (hang) awaitCancellation() else screen.snapshot(options)
                }
            var asked = false
            val policy =
                PolicyEngine { call, intent, ctx ->
                    if (asked) {
                        needsScreen.evaluate(call, intent, ctx)
                    } else {
                        asked = true
                        Verdict.Confirm(sendCard)
                    }
                }
            val run = send(policy, config = LoopConfig(recheckScreenTimeoutMs = 2_000), slowScreen = slow)
            val waiting = run.loop.awaitPrompt()
            hang = true
            val approvedAt = currentTime
            run.loop.answer(waiting.promptId!!, UserReply.Approve)
            advanceUntilIdle()

            assertEquals(Phase.Done, run.loop.phase.value)
            assertEquals(2_000, currentTime - approvedAt)
            assertTrue(run.tool.calls.isEmpty())
            assertEquals(ToolStatus.REFUSED, run.result().status)
            assertEquals(refuse.hintForModel, run.result().text())
        }

    @Test
    fun `without a screen port the re-check judges without a screen, and the policy decides`() =
        runTest {
            val allowing = ScriptedPolicy(Verdict.Confirm(sendCard))
            val allowed = send(allowing, screen = null)
            allowed.loop.answer(allowed.loop.awaitPrompt().promptId!!, UserReply.Approve)
            allowed.loop.awaitPhase(Phase.Done)
            assertNull(allowing.contexts[1].screen)
            assertEquals(1, allowed.tool.calls.size, "this policy allows it")

            val refusing = ScriptedPolicy(Verdict.Confirm(sendCard), refuse)
            val refused = send(refusing, screen = null)
            refused.loop.answer(refused.loop.awaitPrompt().promptId!!, UserReply.Approve)
            refused.loop.awaitPhase(Phase.Done)
            assertNull(refusing.contexts[1].screen)
            assertTrue(refused.tool.calls.isEmpty(), "this one refuses")
            assertEquals(refuse.hintForModel, refused.result().text())
        }
}
