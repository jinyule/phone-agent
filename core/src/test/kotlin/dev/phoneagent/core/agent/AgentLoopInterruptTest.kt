@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.EndReason
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Steers, stops and timeouts (phase1 §3.7, §7.2; D12–D14). All waits are barriers or virtual time. */
class AgentLoopInterruptTest {
    @Test
    fun `a steer cancels the request in flight and the step goes on with it`() =
        runTest {
            val model = FakeChatModel().hangs(delivered = "我先打开").answers("好的，改用微信")
            val harness = LoopHarness(this, model)
            val loop = harness.start()

            loop.followup(text("发消息"))
            model.awaitStarted(1)
            loop.steer(text("改用微信"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(1, events.only<StepStarted>().size, "the steer reopens the same step")
            val replies = events.only<AssistantMessage>()
            assertEquals(listOf(true, false), replies.map { it.interrupted })
            assertEquals(listOf(1, 2), replies.map { it.attempt })
            assertEquals(listOf(Part.Text("我先打开")), replies.first().parts)
            assertEquals(
                listOf(
                    Message(Role.USER, listOf(Part.Text("发消息"))),
                    Message(Role.ASSISTANT, listOf(Part.Text("我先打开"))),
                    Message(Role.USER, listOf(Part.Text("改用微信"))),
                ),
                model.requests[1].messages,
            )
        }

    @Test
    fun `a steer queued while the request is assembled keeps it from being sent`() =
        runTest {
            lateinit var loop: AgentLoop
            var first = true
            val steerNow =
                Interceptor<RequestPlan, RequestPlan> { plan, chain ->
                    if (first) {
                        first = false
                        loop.steer(text("等等"))
                        delay(1) // lets the steer be recorded
                    }
                    chain.proceed(plan)
                }
            val model = FakeChatModel().answers("ok")
            val harness =
                LoopHarness(
                    this,
                    model,
                    interceptors = Interceptors(requestError = emptyList(), request = listOf(steerNow)),
                )
            loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            assertEquals(1, model.started.value, "the first attempt was never sent")
            assertTrue(harness.events().only<AssistantMessage>().none { it.interrupted }, "nothing was delivered")
            assertEquals(
                listOf("go", "等等"),
                model.requests
                    .single()
                    .messages
                    .map { (it.parts.single() as Part.Text).text },
            )
        }

    @Test
    fun `a steer cuts a retry wait short`() =
        runTest {
            val retryLater =
                RetryInterceptor(baseDelayMs = 60_000, maxDelayMs = 60_000, random = kotlin.random.Random(1))
            val model = FakeChatModel().fails(ModelError.Network("reset")).answers("ok")
            val harness = LoopHarness(this, model, interceptors = Interceptors(requestError = listOf(retryLater)))
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitRecorded<RequestRetry>()
            loop.steer(text("快点"))
            loop.awaitPhase(Phase.Done)

            assertTrue(currentTime < 30_000, "the steer did not wait for the backoff (t=$currentTime)")
            assertEquals(
                "快点",
                (
                    model.requests[1]
                        .messages
                        .last()
                        .parts
                        .single() as Part.Text
                ).text,
            )
        }

    @Test
    fun `a running tool is never interrupted by a steer and the steer comes next`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slow =
                FakeTool("slow", risk = RiskClass.WRITE) { _, _ ->
                    release.await()
                    ToolResult.ok("typed")
                }
            val model = FakeChatModel().calls(call("c1", "slow")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(slow))
            val loop = harness.start()

            loop.followup(text("go"))
            runCurrent()
            loop.steer(text("也发给小王"))
            runCurrent()
            assertEquals(1, model.started.value)
            release.complete(Unit)
            loop.awaitPhase(Phase.Done)

            assertEquals(
                ToolStatus.OK,
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
            assertEquals(
                "也发给小王",
                (
                    model.requests[1]
                        .messages
                        .last()
                        .parts
                        .single() as Part.Text
                ).text,
            )
        }

    @Test
    fun `an explicit end still ends the turn while a steer is queued, and the steer starts the next`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val giveUp =
                FakeTool("give_up") { _, _ ->
                    release.await()
                    ToolResult(
                        ToolStatus.OK,
                        listOf(Part.Text("ended")),
                        control = ToolControl.EndTurn(EndReason.BLOCKED, "卡住了"),
                    )
                }
            val model = FakeChatModel().calls(call("c1", "give_up")).answers("好的")
            val harness = LoopHarness(this, model, tools = listOf(giveUp))
            val loop = harness.start()

            loop.followup(text("go"))
            runCurrent()
            loop.steer(text("换个办法"))
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(
                listOf(TurnEnded(1, TurnEndReason.END_NOT_DONE, "卡住了"), TurnEnded(2, TurnEndReason.COMPLETED)),
                events.only<TurnEnded>(),
            )
            assertEquals(
                "换个办法",
                (
                    model.requests[1]
                        .messages
                        .last()
                        .parts
                        .single() as Part.Text
                ).text,
            )
        }

    @Test
    fun `a final answer does not end the turn while a steer is queued`() =
        runTest {
            lateinit var loop: AgentLoop
            var armed = false
            val model =
                FakeChatModel()
                    .then { _, _ ->
                        armed = true
                        ChatResponse(listOf(Part.Text("done?")), StopReason.END_TURN)
                    }.answers("now done")
            val harness = LoopHarness(this, model)
            // The steer lands while the final answer is being recorded, after the request finished.
            harness.storage.beforeAppend = {
                if (armed) {
                    armed = false
                    loop.steer(text("还有一件事"))
                    delay(1)
                }
            }
            loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(1, events.only<TurnStarted>().size)
            assertEquals(listOf(1, 2), events.only<StepEnded>().map { it.step })
            assertTrue(events.only<AssistantMessage>().none { it.interrupted })
            assertEquals(TurnEnded(1, TurnEndReason.COMPLETED), events.last())
        }

    @Test
    fun `stop during a request keeps the delivered prefix and ends the turn stopped`() =
        runTest {
            val model = FakeChatModel().hangs(delivered = "正在")
            val harness = LoopHarness(this, model)
            val loop = harness.start()

            loop.followup(text("go"))
            model.awaitStarted(1)
            loop.followup(text("queued"))
            advanceUntilIdle()
            loop.stop(StopCause.USER)
            assertEquals(Phase.Stopped(StopCause.USER), loop.awaitStopped())

            val events = harness.events()
            val prefix = events.only<AssistantMessage>().single()
            assertTrue(prefix.interrupted)
            assertEquals(listOf(Part.Text("正在")), prefix.parts)
            assertEquals(StepEnded(1, 1, StepOutcome.CANCELLED), events.only<StepEnded>().single())
            assertEquals(TurnEnded(1, TurnEndReason.STOPPED), events.only<TurnEnded>().single())
            assertEquals(1, events.last().let { (it as InboxSpliced).removed }, "the queued followup is cleared")
        }

    @Test
    fun `a stopped task ignores everything after`() =
        runTest {
            val model = FakeChatModel()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.stop(StopCause.USER)
            loop.awaitStopped()
            val before = harness.events()

            loop.followup(text("go"))
            loop.steer(text("go"))
            loop.inject(ContextInput("timer", listOf(Part.Text("x"))))
            loop.stop(StopCause.SHUTDOWN)
            advanceUntilIdle()

            assertEquals(Phase.Stopped(StopCause.USER), loop.phase.value)
            assertFalse(loop.answer(PromptId("prompt-1"), UserReply.Approve))
            assertEquals(before, harness.events())
            assertEquals(listOf(LoopHarness.ATTENDED), before, "an idle task has no turn to close")
            assertEquals(0, model.started.value)
        }

    @Test
    fun `stop before anything was delivered records no prefix`() =
        runTest {
            val model = FakeChatModel().hangs()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            loop.stop(StopCause.USER)
            loop.awaitStopped()
            assertTrue(harness.events().only<AssistantMessage>().isEmpty())
            assertEquals(TurnEnded(1, TurnEndReason.STOPPED), harness.events().only<TurnEnded>().single())
        }

    @Test
    fun `stop while a tool runs cancels it and says it may have taken effect`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val tap =
                FakeTool("tap", risk = RiskClass.WRITE) { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }
            val model = FakeChatModel().calls(call("c1", "tap"), call("c2", "tap"))
            val harness = LoopHarness(this, model, tools = listOf(tap))
            val loop = harness.start()

            loop.followup(text("go"))
            started.await()
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            val results = harness.events().only<ToolResultRecorded>()
            assertEquals(listOf("c1", "c2"), results.map { it.callId.value })
            assertTrue(results.all { it.result.status == ToolStatus.CANCELLED })
            val texts = LoopTexts()
            assertEquals(
                "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}",
                (results[0].result.content.single() as Part.Text).text,
            )
            assertEquals(texts.stoppedBeforeRun, (results[1].result.content.single() as Part.Text).text)
        }

    @Test
    fun `stop while a confirmation is pending withdraws it and the call never runs`() =
        runTest {
            val send = FakeTool("send", risk = RiskClass.OUTWARD)
            val confirm =
                dev.phoneagent.core.policy.PolicyEngine { call, _, _ ->
                    Verdict.Confirm(ConfirmCard(call.id, ConfirmReason.OUTWARD, "send"))
                }
            val model = FakeChatModel().calls(call("c1", "send"))
            val harness = LoopHarness(this, model, tools = listOf(send), policy = confirm)
            val loop = harness.start()

            loop.followup(text("go"))
            val waiting = loop.phase.first { it is Phase.Waiting } as Phase.Waiting
            assertEquals(WaitReason.USER, waiting.reason)
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            assertTrue(send.calls.isEmpty())
            val events = harness.events()
            assertEquals(PromptWithdrawn(waiting.promptId!!), events.only<PromptWithdrawn>().single())
            val result = events.only<ToolResultRecorded>().single().result
            assertEquals(ToolStatus.CANCELLED, result.status)
            assertEquals(LoopTexts().stoppedBeforeRun, (result.content.single() as Part.Text).text)
            assertFalse(loop.answer(waiting.promptId, UserReply.Approve), "the withdrawn card cannot be approved")
        }

    @Test
    fun `cancelling the scope stops the task as a shutdown`() =
        runTest {
            val model = FakeChatModel().hangs()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            harness.loopScope.cancel()
            assertEquals(Phase.Stopped(StopCause.SHUTDOWN), loop.awaitStopped())
            assertEquals(TurnEnded(1, TurnEndReason.STOPPED), harness.events().only<TurnEnded>().single())
        }

    @Test
    fun `a tool over its timeout is cancelled and a write tool is reported as possibly done`() =
        runTest {
            val read = FakeTool("look", timeoutMs = 1_000) { _, _ -> awaitCancellation() }
            val write = FakeTool("tap", risk = RiskClass.WRITE, timeoutMs = 1_000) { _, _ -> awaitCancellation() }
            val model = FakeChatModel().calls(call("c1", "look")).calls(call("c2", "tap")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(read, write))
            val loop = harness.start()

            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val texts = LoopTexts()
            val results = harness.events().only<ToolResultRecorded>().map { it.result }
            assertEquals(listOf(ToolStatus.TIMEOUT, ToolStatus.TIMEOUT), results.map { it.status })
            assertEquals(texts.timeout, (results[0].content.single() as Part.Text).text)
            assertEquals(
                "${texts.timeout}${texts.mayHaveTakenEffect}",
                (results[1].content.single() as Part.Text).text,
            )
            assertEquals(2_000, currentTime, "each call had exactly its own timeout")
        }

    private suspend fun kotlinx.coroutines.test.TestScope.runAskingTool(workAfterMs: Long): ToolStatus {
        val asking =
            FakeTool("ask", timeoutMs = 1_000) { _, ctx ->
                delay(400)
                val answer = ctx.interaction.ask(Question("哪个联系人？"))
                delay(workAfterMs)
                ToolResult.ok(answer.toString())
            }
        val harness = LoopHarness(this, FakeChatModel().calls(call("c1", "ask")).answers("ok"), tools = listOf(asking))
        val loop = harness.start()
        loop.followup(text("go"))
        val waiting = loop.phase.first { it is Phase.Waiting } as Phase.Waiting
        delay(10_000)
        assertTrue(loop.answer(waiting.promptId!!, UserReply.Answer("小王")))
        loop.awaitPhase(Phase.Done)
        return harness
            .events()
            .only<ToolResultRecorded>()
            .single()
            .result.status
    }

    @Test
    fun `time waiting for the user does not count toward the timeout`() =
        runTest {
            assertEquals(
                ToolStatus.OK,
                runAskingTool(workAfterMs = 400),
                "400 + 400 ms of work fits; the 10 s wait is excluded",
            )
        }

    @Test
    fun `work before and after a user wait adds up`() =
        runTest {
            assertEquals(ToolStatus.TIMEOUT, runAskingTool(workAfterMs = 700), "400 + 700 ms of work does not fit")
        }

    @Test
    fun `without a user wait the work times out`() =
        runTest {
            val busy =
                FakeTool("busy", timeoutMs = 1_000) { _, _ ->
                    delay(1_200)
                    ToolResult.ok("late")
                }
            val harness =
                LoopHarness(this, FakeChatModel().calls(call("c1", "busy")).answers("ok"), tools = listOf(busy))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(
                ToolStatus.TIMEOUT,
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
        }

    @Test
    fun `a result that arrives exactly at the deadline is kept`() =
        runTest {
            val punctual =
                FakeTool("punctual", timeoutMs = 1_000) { _, _ ->
                    delay(1_000)
                    ToolResult.ok("just in time")
                }
            val harness =
                LoopHarness(this, FakeChatModel().calls(call("c1", "punctual")).answers("ok"), tools = listOf(punctual))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(
                ToolStatus.OK,
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result.status,
            )
        }

    @Test
    fun `a tool that throws reports an error and the turn goes on`() =
        runTest {
            val broken = FakeTool("broken") { _, _ -> error("boom") }
            val stray = FakeTool("stray") { _, _ -> throw kotlinx.coroutines.CancellationException("not a stop") }
            val model = FakeChatModel().calls(call("c1", "broken")).calls(call("c2", "stray")).answers("ok")
            val harness = LoopHarness(this, model, tools = listOf(broken, stray))
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            val results = harness.events().only<ToolResultRecorded>().map { it.result }
            assertEquals(listOf(ToolStatus.ERROR, ToolStatus.ERROR), results.map { it.status })
            assertTrue((results[0].content.single() as Part.Text).text.contains("boom"))
            assertTrue((results[1].content.single() as Part.Text).text.contains("not a stop"))
        }
}
