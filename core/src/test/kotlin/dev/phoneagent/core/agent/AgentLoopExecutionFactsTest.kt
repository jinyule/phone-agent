@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.c1
import dev.phoneagent.core.agent.PolicySamples.handOff
import dev.phoneagent.core.agent.PolicySamples.refuse
import dev.phoneagent.core.agent.PolicySamples.sendCard
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Execution facts (`tool/started`, `tool/executed`): written at the real end of the `toolExecute`
 * chain only, and what repair and the policy's history are derived from (phase1 §3.7).
 */
class AgentLoopExecutionFactsTest {
    private fun List<SessionEvent>.facts(): List<SessionEvent> = filter { it is ToolStarted || it is ToolExecuted }

    private suspend fun verdictSeq(harness: LoopHarness): Long =
        harness
            .records()
            .first {
                it.event is PolicyVerdictRecorded
            }.seq

    @Test
    fun `an allowed call starts on its verdict, flushed before it runs, and its own status is recorded`() =
        runTest {
            lateinit var harness: LoopHarness
            var syncsAtWrapper = -1
            var syncsInTool = -1
            var lastInTool: SessionEvent? = null
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, _ ->
                    syncsInTool = harness.storage.syncCount
                    lastInTool = harness.records().last().event
                    ToolResult.ok("点了")
                }
            val wrapper =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    syncsAtWrapper = harness.storage.syncCount
                    chain.proceed(input)
                }
            val rewrite =
                Interceptor<PostExecuteInput, PostExecuteResult> { _, _ ->
                    PostExecuteResult(ToolResult.failure(ToolStatus.ERROR, "改写了"))
                }
            harness =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "tap")).answers("好了"),
                    tools = listOf(tap),
                    interceptors =
                        Interceptors(
                            requestError = emptyList(),
                            toolExecute = listOf(wrapper),
                            toolPostExecute = listOf(rewrite),
                        ),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val records = harness.records()
            val verdict = records.single { it.event is PolicyVerdictRecorded }
            assertEquals(ToolStarted(c1, verdict.seq), lastInTool, "tool/started is the last record when the tool runs")
            assertEquals(1, syncsInTool - syncsAtWrapper, "and it is flushed first")
            val events = records.map { it.event }
            assertEquals(listOf(ToolStarted(c1, verdict.seq), ToolExecuted(c1, ToolStatus.OK)), events.facts())
            assertEquals(
                ToolStatus.ERROR,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
                "rewritten after",
            )
        }

    @Test
    fun `a READ call is judged and started like any other`() =
        runTest {
            val harness =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "look")).answers("好了"),
                    tools = listOf(FakeTool("look")),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val records = harness.records()
            val verdict = records.single { it.event is PolicyVerdictRecorded }
            assertEquals(Verdict.Allow, (verdict.event as PolicyVerdictRecorded).verdict)
            assertEquals(
                listOf(ToolStarted(c1, verdict.seq), ToolExecuted(c1, ToolStatus.OK)),
                records.map { it.event }.facts(),
            )
        }

    @Test
    fun `an approved call starts on the verdict of its re-check`() =
        runTest {
            val run = send(ScriptedPolicy(Verdict.Confirm(sendCard)))
            run.loop.approveNext()
            run.loop.awaitPhase(Phase.Done)

            val records = run.harness.records()
            val verdicts = records.filter { it.event is PolicyVerdictRecorded }
            assertEquals(2, verdicts.size)
            assertEquals(ToolStarted(c1, verdicts.last().seq), records.map { it.event }.facts().first())
        }

    @Test
    fun `a tool that throws executed ERROR, a timeout and a short-circuit leave no tool-executed`() =
        runTest {
            fun TestScope.ran(
                tool: FakeTool,
                interceptors: Interceptors = Interceptors(requestError = emptyList()),
            ): LoopHarness {
                val harness =
                    LoopHarness(
                        this,
                        FakeChatModel().calls(call("c1", tool.def.name)).answers("好了"),
                        listOf(tool),
                        interceptors = interceptors,
                    )
                return harness
            }

            val throwing = ran(FakeTool("tap", risk = RiskClass.WRITE) { _, _ -> error("boom") })
            val slow = ran(FakeTool("tap", risk = RiskClass.WRITE, timeoutMs = 1_000) { _, _ -> awaitCancellation() })
            val shortCut = Interceptor<ExecuteInput, ToolResult> { _, _ -> ToolResult.ok("缓存的结果") }
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val cut = ran(tap, Interceptors(requestError = emptyList(), toolExecute = listOf(shortCut)))
            for (harness in listOf(throwing, slow, cut)) {
                harness.start().followup(text("go"))
            }
            advanceUntilIdle()

            assertEquals(
                listOf(ToolStarted(c1, verdictSeq(throwing)), ToolExecuted(c1, ToolStatus.ERROR)),
                throwing.events().facts(),
            )
            assertEquals(listOf(ToolStarted(c1, verdictSeq(slow))), slow.events().facts())
            assertEquals(
                ToolStatus.TIMEOUT,
                slow
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertEquals(emptyList<SessionEvent>(), cut.events().facts(), "the tool never ran")
            assertEquals(
                ToolStatus.OK,
                cut
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertTrue(tap.calls.isEmpty())
        }

    @Test
    fun `refused, handed-off and rejected calls have no execution facts`() =
        runTest {
            val refused = send(ScriptedPolicy(refuse))
            val handedOff = send(ScriptedPolicy(handOff))
            handedOff.loop.answer(handedOff.loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
            val rejected = send(ScriptedPolicy(Verdict.Confirm(sendCard)))
            rejected.loop.answer(rejected.loop.awaitPrompt().promptId!!, UserReply.Reject)
            advanceUntilIdle()

            for (run in listOf(refused, handedOff, rejected)) {
                assertEquals(Phase.Done, run.loop.phase.value)
                assertEquals(emptyList<SessionEvent>(), run.harness.events().facts())
                assertTrue(run.tool.calls.isEmpty())
            }
        }

    @Test
    fun `a flush that fails after tool-started stops the task before the tool runs`() =
        runTest {
            lateinit var harness: LoopHarness
            val failNextFlush =
                Interceptor<PreExecuteInput, Verdict> { input, chain ->
                    harness.storage.syncFailures = 1
                    chain.proceed(input)
                }
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            harness =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "tap")),
                    tools = listOf(tap),
                    interceptors = Interceptors(requestError = emptyList(), toolPreExecute = listOf(failNextFlush)),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitStopped()

            assertTrue(tap.calls.isEmpty())
            val events = harness.events()
            assertEquals(listOf(ToolStarted::class), events.facts().map { it::class })
            assertEquals(
                ToolStatus.OUTCOME_UNKNOWN,
                events
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
                "started: as far as the log knows, it may have run",
            )
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.phase.value)
        }

    /**
     * A task whose model calls [first] (as `c1`), then `next` (`c2`, WRITE), then answers; packages
     * are `pkg.<tool>`.
     */
    private fun TestScope.twoCalls(
        first: FakeTool,
        policy: ScriptedPolicy,
        interceptors: Interceptors = Interceptors(requestError = emptyList()),
    ): LoopHarness {
        val model = FakeChatModel().calls(call("c1", first.def.name)).calls(call("c2", "next")).answers("好了")
        return LoopHarness(
            this,
            model,
            tools = listOf(first, FakeTool("next", risk = RiskClass.WRITE)),
            policy = policy,
            intents = IntentResolver { call, risk, _ -> ActionIntent(LoopHarness.kindOf(risk), "pkg.${call.name}") },
            interceptors = interceptors,
        )
    }

    @Test
    fun `a call the user did after a policy hand-off is no operated package`() =
        runTest {
            val policy = ScriptedPolicy(handOff)
            val harness = twoCalls(FakeTool("tap", risk = RiskClass.WRITE), policy)
            val loop = harness.start()
            loop.followup(text("go"))
            loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
            loop.awaitPhase(Phase.Done)

            assertEquals(emptySet<String>(), policy.contexts.last().operatedPackages)
        }

    @Test
    fun `a call an interceptor answered is no operated package`() =
        runTest {
            val policy = ScriptedPolicy()
            val shortCut =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    if (input.call.id == c1) ToolResult.ok("缓存的结果") else chain.proceed(input)
                }
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val harness =
                twoCalls(tap, policy, Interceptors(requestError = emptyList(), toolExecute = listOf(shortCut)))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            assertTrue(tap.calls.isEmpty())
            assertEquals(emptySet<String>(), policy.contexts.last().operatedPackages)
        }

    @Test
    fun `a tool that ran and then handed off is an operated package, and stopping it says it may have taken effect`() =
        runTest {
            val policy = ScriptedPolicy()
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.CAPTCHA, "请完成验证")
                    ToolResult.ok("验证好了")
                }
            val harness = twoCalls(tap, policy)
            val loop = harness.start()
            loop.followup(text("go"))
            loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone)
            loop.awaitPhase(Phase.Done)
            assertEquals(setOf("pkg.tap"), policy.contexts.last().operatedPackages)

            val again = twoCalls(tap, ScriptedPolicy())
            val stopped = again.start()
            stopped.followup(text("go"))
            stopped.awaitPrompt()
            stopped.stop(StopCause.USER)
            stopped.awaitStopped()
            val texts = LoopTexts()
            val result =
                again
                    .events()
                    .only<ToolResultRecorded>()
                    .single { it.callId == ToolCallId("c1") }
                    .result
            assertEquals(
                ToolResult.failure(
                    ToolStatus.CANCELLED,
                    "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}\n${texts.screenUnknown}",
                ),
                result,
                "stopped while handed over: no screen after the handoff either",
            )
        }
}
