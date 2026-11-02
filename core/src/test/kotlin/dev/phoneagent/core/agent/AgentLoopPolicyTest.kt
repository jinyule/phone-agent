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

/** The PolicyEngine guard, confirmations, questions and handoffs (phase1 §3.8, contracts addendum §8). */
class AgentLoopPolicyTest {
    @Test
    fun `a refused call never runs and the model gets the hint`() =
        runTest {
            val run = send(ScriptedPolicy(refuse))
            run.loop.awaitPhase(Phase.Done)
            assertTrue(run.tool.calls.isEmpty())
            val result = run.result()
            assertEquals(ToolStatus.REFUSED, result.status)
            assertEquals(refuse.hintForModel, result.text())
            val verdict =
                run.harness
                    .events()
                    .only<PolicyVerdictRecorded>()
                    .single()
            assertEquals(refuse, verdict.verdict)
            assertEquals(SecurityLevel.STANDARD, verdict.level, "the level of task/start")
        }

    @Test
    fun `an interceptor that allows cannot bypass the policy`() =
        runTest {
            val bypass = Interceptor<PreExecuteInput, Verdict> { _, _ -> Verdict.Allow }
            val run =
                send(
                    ScriptedPolicy(refuse),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(bypass)),
                )
            run.loop.awaitPhase(Phase.Done)
            assertTrue(run.tool.calls.isEmpty(), "the guard runs after the chain whatever it returned")
            assertEquals(ToolStatus.REFUSED, run.result().status)
        }

    @Test
    fun `an interceptor may be stricter than the policy`() =
        runTest {
            val veto =
                Interceptor<PreExecuteInput, Verdict> { input, chain ->
                    assertEquals("send", input.tool.def.name)
                    assertEquals(Verdict.Allow, chain.proceed(input))
                    Verdict.Refuse("quiet-hours", "Not now.")
                }
            val run =
                send(
                    ScriptedPolicy(),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(veto)),
                )
            run.loop.awaitPhase(Phase.Done)
            assertEquals("Not now.", run.result().text())
            assertTrue(run.tool.calls.isEmpty())
        }

    @Test
    fun `on a tie the policy's verdict wins and a hand-off beats a confirmation`() =
        runTest {
            val otherCard = sendCard.copy(action = "interceptor card")
            val confirmToo = Interceptor<PreExecuteInput, Verdict> { _, _ -> Verdict.Confirm(otherCard) }
            val tie =
                send(
                    ScriptedPolicy(Verdict.Confirm(sendCard)),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(confirmToo)),
                )
            tie.loop.awaitPrompt()
            assertEquals(
                sendCard,
                tie.harness
                    .events()
                    .only<ApprovalAsked>()
                    .single()
                    .card,
            )
        }

    @Test
    fun `a stricter interceptor verdict asks for a hand-off instead of a confirmation`() =
        runTest {
            val takeOver = Interceptor<PreExecuteInput, Verdict> { _, _ -> handOff }
            val run =
                send(
                    ScriptedPolicy(Verdict.Confirm(sendCard)),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(takeOver)),
                )
            val waiting = run.loop.awaitPrompt()
            assertEquals(WaitReason.HANDOFF, waiting.reason)
        }

    @Test
    fun `rejected, expired and unattended confirmations refuse the call`() =
        runTest {
            val rejected = send(ScriptedPolicy(Verdict.Confirm(sendCard)))
            rejected.loop.answer(rejected.loop.awaitPrompt().promptId!!, UserReply.Reject)
            rejected.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().rejected, rejected.result().text())

            val expired = send(ScriptedPolicy(Verdict.Confirm(sendCard)), config = LoopConfig(confirmTimeoutMs = 5_000))
            expired.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().confirmExpired, expired.result().text())
            assertEquals(
                ConfirmResult.EXPIRED,
                expired.harness
                    .events()
                    .only<ApprovalDecided>()
                    .single()
                    .result,
            )

            val unattended = send(ScriptedPolicy(Verdict.Confirm(sendCard)), settings = LoopHarness.UNATTENDED)
            unattended.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().confirmUnavailable, unattended.result().text())
            assertTrue(listOf(rejected, expired, unattended).all { it.tool.calls.isEmpty() })
        }

    @Test
    fun `a hand-off in main-display mode waits for the user`() =
        runTest {
            val done = send(ScriptedPolicy(handOff))
            val waiting = done.loop.awaitPrompt()
            assertEquals(WaitReason.HANDOFF, waiting.reason)
            assertFalse(done.loop.answer(waiting.promptId!!, UserReply.Approve))
            assertFalse(done.loop.answer(waiting.promptId, UserReply.Answer("x")))
            assertFalse(done.loop.answer(waiting.promptId, UserReply.Reject))
            assertTrue(done.loop.answer(waiting.promptId, UserReply.HandoffDone))
            done.loop.awaitPhase(Phase.Done)
            assertTrue(done.tool.calls.isEmpty(), "the user did the step; the tool does not run")
            assertEquals(ToolStatus.OK, done.result().status)
            assertEquals(LoopTexts().handoffDone, done.result().text())
            val started =
                done.harness
                    .events()
                    .only<HandoffStarted>()
                    .single()
            assertEquals(HandoffKind.LOGIN to c1, started.kind to started.callId)

            val declined = send(ScriptedPolicy(handOff))
            declined.loop.answer(declined.loop.awaitPrompt().promptId!!, UserReply.Decline)
            declined.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().handoffDeclined, declined.result().text())

            val expired = send(ScriptedPolicy(handOff), config = LoopConfig(handoffTimeoutMs = 1_000))
            expired.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().handoffExpired, expired.result().text())

            val unattended = send(ScriptedPolicy(handOff), settings = LoopHarness.UNATTENDED)
            unattended.loop.awaitPhase(Phase.Done)
            assertEquals(LoopTexts().handoffUnavailable, unattended.result().text())
            assertEquals(
                HandoffResult.UNAVAILABLE,
                unattended.harness
                    .events()
                    .only<HandoffEnded>()
                    .single()
                    .result,
            )
        }

    /** Moves tasks; fails the moves named in [failing]. */
    private class FakeSurfaces(
        private val failing: Set<String> = emptySet(),
    ) : SurfaceManager {
        val moves = mutableListOf<String>()
        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            moves += "hand"
            if ("hand" in failing) throw IOException("display gone")
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            moves += "back"
            if ("back" in failing) throw IOException("task vanished")
            return ticket.from
        }
    }

    @Test
    fun `a hand-off moves the task to the user and back`() =
        runTest {
            val surfaces = FakeSurfaces()
            val run = send(ScriptedPolicy(handOff), surfaces = surfaces)
            run.loop.answer(run.loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
            run.loop.awaitPhase(Phase.Done)
            assertEquals(listOf("hand", "back"), surfaces.moves)
            assertEquals(
                HandoffEnded(PromptId("prompt-${promptSeq(run)}"), HandoffResult.DONE),
                run.harness
                    .events()
                    .only<HandoffEnded>()
                    .single(),
            )
        }

    private suspend fun promptSeq(run: PolicyRun): Long =
        run.harness
            .records()
            .first { it.event is HandoffStarted }
            .seq

    @Test
    fun `failing to move the task ends the hand-off as failed with the reason`() =
        runTest {
            val noHand = FakeSurfaces(failing = setOf("hand"))
            val first = send(ScriptedPolicy(handOff), surfaces = noHand)
            first.loop.awaitPhase(Phase.Done)
            assertEquals(listOf("hand"), noHand.moves, "nothing to wait for")
            val ended =
                first.harness
                    .events()
                    .only<HandoffEnded>()
                    .single()
            assertEquals(HandoffResult.FAILED, ended.result)
            assertEquals("hand over failed: display gone", ended.detail)
            assertEquals(LoopTexts().handoffFailed, first.result().text())

            val noBack = FakeSurfaces(failing = setOf("back"))
            val second = send(ScriptedPolicy(handOff), surfaces = noBack)
            second.loop.answer(second.loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
            second.loop.awaitPhase(Phase.Done)
            assertEquals(
                "take back failed: task vanished",
                second.harness
                    .events()
                    .only<HandoffEnded>()
                    .single()
                    .detail,
            )
        }

    @Test
    fun `tools ask the user through their context`() =
        runTest {
            val answers = mutableListOf<AskResult>()
            val ask =
                FakeTool("ask") { _, ctx ->
                    answers += ctx.interaction.ask(Question("哪个？", listOf("A", "B")))
                    ToolResult.ok("asked")
                }
            val model =
                FakeChatModel()
                    .calls(
                        call("c1", "ask"),
                    ).calls(call("c2", "ask"))
                    .calls(call("c3", "ask"))
                    .answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(ask), config = LoopConfig(questionTimeoutMs = 1_000))
            val loop = harness.start()
            loop.followup(text("go"))

            val first = loop.awaitPrompt()
            assertFalse(loop.answer(first.promptId!!, UserReply.Approve), "approve does not answer a question")
            assertFalse(loop.answer(first.promptId, UserReply.Reject), "nor does reject")
            assertFalse(loop.answer(first.promptId, UserReply.HandoffDone), "nor does a handoff")
            assertTrue(loop.answer(first.promptId, UserReply.Answer("A")))
            val second = loop.phase.first { it is Phase.Waiting && it.promptId != first.promptId } as Phase.Waiting
            assertTrue(loop.answer(second.promptId!!, UserReply.Decline))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(AskResult.Answered("A"), AskResult.Declined, AskResult.Expired), answers)
            val events = harness.events()
            assertEquals(3, events.only<QuestionAsked>().size)
            assertEquals(answers, events.only<QuestionAnswered>().map { it.result })
        }

    @Test
    fun `an unattended task gets no answers and never waits`() =
        runTest {
            val results = mutableListOf<Any>()
            val tool =
                FakeTool("ask") { _, ctx ->
                    results += ctx.interaction.ask(Question("?"))
                    results += ctx.interaction.confirm(sendCard)
                    results += ctx.interaction.handOff(HandoffKind.CAPTCHA, "solve it")
                    ToolResult.ok("ok")
                }
            val harness =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "ask")).answers("ok"),
                    tools = listOf(tool),
                    settings = LoopHarness.UNATTENDED,
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(listOf(AskResult.Unavailable, ConfirmResult.UNAVAILABLE, HandoffResult.UNAVAILABLE), results)
            assertEquals(0L, currentTimeOf(this))
        }

    private fun currentTimeOf(scope: TestScope): Long = scope.testScheduler.currentTime

    @Test
    fun `the policy context comes from the log`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Confirm(sendCard))
            val tools =
                listOf(
                    FakeTool("send", risk = RiskClass.OUTWARD),
                    FakeTool("look"),
                    FakeTool("broken", risk = RiskClass.WRITE) { _, _ -> ToolResult.failure(ToolStatus.ERROR, "no") },
                    FakeTool("tap", risk = RiskClass.WRITE),
                )
            val intents =
                dev.phoneagent.core.policy.IntentResolver { call, risk, _ ->
                    ActionIntent(LoopHarness.kindOf(risk), "pkg.${call.name}")
                }
            val model =
                FakeChatModel()
                    .calls(call("c1", "send"))
                    .calls(call("c2", "look"))
                    .calls(call("c3", "broken"))
                    .calls(call("c4", "tap"))
                    .calls(call("c5", "look"))
                    .answers("ok")
            val harness = LoopHarness(this, model, tools = tools, policy = policy, intents = intents)
            val loop = harness.start()
            loop.followup(text("go"))
            loop.answer(loop.awaitPrompt().promptId!!, UserReply.Approve)
            loop.awaitPhase(Phase.Done)

            val contexts = policy.contexts
            assertEquals(6, contexts.size, "c1 is judged twice (before and after approval)")
            assertTrue(contexts[0].operatedPackages.isEmpty())
            assertTrue(contexts[0].outwardConfirmedPackages.isEmpty())
            assertEquals(setOf("pkg.send"), contexts[4].operatedPackages, "READ and failed calls do not count")
            assertEquals(setOf("pkg.send", "pkg.tap"), contexts[5].operatedPackages)
            assertEquals(setOf("com.example.chat"), contexts[5].outwardConfirmedPackages)
            assertEquals(LoopHarness.TASK, contexts[5].taskId)
            assertFalse(contexts[5].unattended)
        }

    @Test
    fun `the level comes from the log and changes only through the runtime`() =
        runTest {
            val policy = ScriptedPolicy()
            val tool = FakeTool("send", risk = RiskClass.OUTWARD)
            val model =
                FakeChatModel()
                    .calls(call("c1", "send"))
                    .answers("ok")
                    .calls(call("c2", "send"))
                    .answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(tool),
                    policy = policy,
                    settings = TaskStarted(SecurityLevel.CAUTIOUS, unattended = false),
                )
            val loop = harness.start()
            loop.followup(text("one"))
            loop.awaitPhase(Phase.Done)
            loop.changeLevel(SecurityLevel.AUTONOMOUS)
            loop.followup(text("two"))
            advanceUntilIdle()

            assertEquals(listOf(SecurityLevel.CAUTIOUS, SecurityLevel.AUTONOMOUS), policy.contexts.map { it.level })
            assertEquals(
                listOf(SecurityLevel.CAUTIOUS, SecurityLevel.AUTONOMOUS),
                harness.events().only<PolicyVerdictRecorded>().map { it.level },
            )
            assertEquals(listOf(TaskLevelChanged(SecurityLevel.AUTONOMOUS)), harness.events().only<TaskLevelChanged>())

            loop.stop(StopCause.USER)
            loop.awaitStopped()
            loop.changeLevel(SecurityLevel.STANDARD)
            advanceUntilIdle()
            assertEquals(1, harness.events().only<TaskLevelChanged>().size, "ignored once stopped")
        }
}
