@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.sendCard
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.session.CompactionSummary
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Every request the loop sends is rebuilt exactly from the log as it was then, serialized and read
 * back (phase1 §3.9 rule 1), in the scenarios that write the most around a request.
 */
class AgentLoopReplayTest {
    private fun LoopHarness.assertReplayed(requests: Int) {
        assertEquals(emptyList<String>(), replay.mismatches)
        assertEquals(requests, replay.checked)
    }

    @Test
    fun `a failed attempt and its retry`() =
        runTest {
            val model = FakeChatModel().fails(ModelError.Server(503, "busy"), delivered = "正在").answers("好了")
            val retrying = Interceptors(requestError = listOf(RetryInterceptor(random = Random(0))))
            val harness = LoopHarness(this, model, interceptors = retrying, strictReplay = false)
            harness.start().followup(text("go"))
            advanceUntilIdle()
            harness.assertReplayed(2)
        }

    @Test
    fun `a confirmation and its re-check on a fresh screen`() =
        runTest {
            val run = send(ScriptedPolicy(Verdict.Confirm(sendCard)))
            run.loop.approveNext()
            advanceUntilIdle()
            assertEquals(Phase.Done, run.loop.phase.value)
            run.harness.assertReplayed(2)
        }

    @Test
    fun `a result rewritten by toolPostExecute and the context it attaches`() =
        runTest {
            val rewrite =
                Interceptor<PostExecuteInput, PostExecuteResult> { _, _ ->
                    PostExecuteResult(ToolResult.ok("改写后的结果"), listOf(ContextInput("memo", listOf(Part.Text("附加的提示")))))
                }
            val model = FakeChatModel().calls(call("c1", "look")).answers("好了")
            val interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(rewrite))
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(FakeTool("look")),
                    interceptors = interceptors,
                    strictReplay = false,
                )
            harness.start().followup(text("go"))
            advanceUntilIdle()
            harness.assertReplayed(2)
            val texts =
                model.requests[1]
                    .messages
                    .flatMap { it.parts }
                    .toString()
            assertEquals(true, "改写后的结果" in texts && "附加的提示" in texts, texts)
        }

    @Test
    fun `a steer that cuts a request short`() =
        runTest {
            val model = FakeChatModel().hangs(delivered = "正在").answers("好了")
            val harness = LoopHarness(this, model, strictReplay = false)
            val loop = harness.start()
            loop.followup(text("go"))
            runCurrent()
            loop.steer(text("换一个"))
            advanceUntilIdle()
            assertEquals(Phase.Done, loop.phase.value)
            harness.assertReplayed(2)
        }

    @Test
    fun `a stop, a resume and the next request`() =
        runTest {
            val first = LoopHarness(this, FakeChatModel().hangs(delivered = "正在"), strictReplay = false)
            val loop = first.start()
            loop.followup(text("go"))
            runCurrent()
            loop.stop(StopCause.SHUTDOWN)
            advanceUntilIdle()

            val second = LoopHarness(this, FakeChatModel().answers("好了"), storage = first.storage, strictReplay = false)
            val resumed = second.resume()
            resumed.followup(text("继续"))
            advanceUntilIdle()
            assertEquals(Phase.Done, resumed.phase.value)
            first.assertReplayed(1)
            second.assertReplayed(1)
        }

    @Test
    fun `a compaction while the request switches protocol`() =
        runTest {
            val chat =
                FakeChatModel(
                    FakeChatModel.INFO.copy(protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS),
                ).answers("一")
            val anthropic = FakeChatModel(FakeChatModel.INFO.copy(model = ModelId("other"))).answers("二")
            lateinit var harness: LoopHarness
            val route =
                Interceptor<RequestPlan, RequestPlan> { plan, chain ->
                    val turn2 = harness.events().any { it is UserMessage && it.parts == listOf(Part.Text("b")) }
                    chain.proceed(if (turn2) plan.copy(model = harness.replay.wrap(anthropic)) else plan)
                }
            val compact =
                Interceptor<PreStepInput, PreStepResult> { input, chain ->
                    if (input.turn == 2) {
                        val firstTurn =
                            input.log.records().filter {
                                it.event is UserMessage ||
                                    it.event is dev.phoneagent.core.session.AssistantMessage
                            }
                        val range = SurfaceOp.Replace(firstTurn.first().seq, firstTurn[1].seq)
                        input.log.append(CompactionSummary(listOf(Part.Text("用户说过 a"))), range)
                    }
                    chain.proceed(input)
                }
            val interceptors =
                Interceptors(requestError = emptyList(), request = listOf(route), preStep = listOf(compact))
            harness = LoopHarness(this, chat, interceptors = interceptors, strictReplay = false)
            val loop = harness.start()
            loop.followup(text("a"))
            advanceUntilIdle()
            loop.followup(text("b"))
            advanceUntilIdle()

            assertEquals(Phase.Done, loop.phase.value)
            harness.assertReplayed(2)
            assertEquals(
                3,
                anthropic.requests
                    .single()
                    .messages.size,
                "Anthropic: history stays, the summary is UI-only",
            )
        }
}
