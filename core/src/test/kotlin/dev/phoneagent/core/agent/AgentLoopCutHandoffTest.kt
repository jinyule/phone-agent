@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A call that cuts its own prompt off (a timeout inside the tool) closes it at once, before the
 * next prompt may open: `prompt/withdrawn`, and for a handoff the task is taken back (once, within
 * [LoopConfig.takeBackTimeoutMs]); then the cancellation goes on to the tool. A failed take-back
 * ends the call with an error naming the exception's class only (TIMEOUT when the budget cut it
 * off). Only a stop leaves the app with the user, and the withdrawal to the repair.
 */
class AgentLoopCutHandoffTest {
    private val c1 = ToolCallId("c1")
    private val texts = LoopTexts()

    /**
     * Counts the moves; handing over takes [handMs], to its end despite a cancellation; taking back
     * takes [takeBackMs] (to its end despite a cancellation when [finishesBack]), then throws
     * [backFailure].
     */
    private class CountingSurfaces(
        private val takeBackMs: Long = 0,
        private val backFailure: IOException? = null,
        private val finishesBack: Boolean = false,
        private val handMs: Long = 0,
    ) : SurfaceManager {
        var handed = 0
        var takenBack = 0

        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            handed++
            withContext(NonCancellable) { delay(handMs) }
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            takenBack++
            if (finishesBack) withContext(NonCancellable) { delay(takeBackMs) } else delay(takeBackMs)
            backFailure?.let { throw it }
            return ticket.from
        }
    }

    /** What the cutting tool's own timeout returned, and how many take-backs tap saw when it ran. */
    private class Seen {
        var handoff: HandoffResult? = HandoffResult.DONE
        var takenBackAtTap = -1
    }

    /** c1 hands off and gives up after 1 s; c2 is tap. */
    private fun TestScope.harness(
        surfaces: CountingSurfaces,
        seen: Seen,
    ): LoopHarness {
        val hand =
            FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                seen.handoff = withTimeoutOrNull(1_000) { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                ToolResult.ok("没等到用户")
            }
        val tap =
            FakeTool("tap", RiskClass.WRITE) { _, _ ->
                seen.takenBackAtTap = surfaces.takenBack
                ToolResult.ok("点了")
            }
        val model = FakeChatModel().calls(call("c1", "hand")).calls(call("c2", "tap")).answers("好了")
        return LoopHarness(this, model, tools = listOf(hand, tap), surfaces = surfaces)
    }

    private suspend fun LoopHarness.resultOf(id: ToolCallId): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId == id }.result

    @Test
    fun `a handoff the tool cuts off is withdrawn and taken back before the next call runs`() =
        runTest {
            val surfaces = CountingSurfaces()
            val seen = Seen()
            val harness = harness(surfaces, seen)
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            advanceUntilIdle()

            assertNull(seen.handoff, "the cancellation reached the tool's own timeout")
            assertEquals(1 to 1, surfaces.handed to surfaces.takenBack)
            assertEquals(1, seen.takenBackAtTap, "tap ran with the app back on the agent display")
            val events = harness.events()
            val started = events.only<HandoffStarted>().single()
            assertEquals(PromptWithdrawn(started.promptId), events[events.indexOf(started) + 1])
            assertTrue(events.only<HandoffEnded>().isEmpty())
            assertEquals(1, events.only<PromptWithdrawn>().size)
            assertEquals(ToolStatus.OK, harness.resultOf(c1).status)
            assertEquals(Phase.Done, loop.phase.value)
        }

    @Test
    fun `a question the tool cuts off is withdrawn before its next question opens`() =
        runTest {
            val ask =
                FakeTool("ask") { _, ctx ->
                    withTimeoutOrNull(1_000) { ctx.interaction.ask(Question("哪个账号？")) }
                    ctx.interaction.ask(Question("还是用默认账号？"))
                    ToolResult.ok("问好了")
                }
            val model = FakeChatModel().calls(call("c1", "ask")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(ask))
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            advanceTimeBy(1_500)
            val second = loop.awaitPrompt().promptId!!
            assertTrue(second != first, "the second question is waiting")
            val openWhileAsking = openPrompts(harness.records())
            assertTrue(loop.answer(second, UserReply.Answer("是")))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(second), openWhileAsking, "one prompt open at a time, in the log too")
            val events = harness.events()
            val next = events.only<QuestionAsked>().last()
            assertTrue(events.indexOf(PromptWithdrawn(first)) in 0..<events.indexOf(next), "$events")
        }

    @Test
    fun `a handoff whose take-back ends only after the cut is withdrawn before the next prompt opens`() =
        runTest {
            // The app comes back despite the cut, so the wait returns; its end can no longer be recorded.
            val surfaces = CountingSurfaces(takeBackMs = 2_000, finishesBack = true)
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    withTimeoutOrNull(1_000) { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                    ctx.interaction.ask(Question("登录好了吗？"))
                    ToolResult.ok("问好了")
                }
            val model = FakeChatModel().calls(call("c1", "hand")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(hand), surfaces = surfaces)
            val loop = harness.start()
            loop.followup(text("登录"))
            val handoff = loop.awaitPrompt().promptId!!
            assertTrue(loop.answer(handoff, UserReply.HandoffDone))
            val question = loop.awaitPrompt(after = handoff).promptId!!
            val openWhileAsking = openPrompts(harness.records())
            assertTrue(loop.answer(question, UserReply.Answer("好了")))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(question), openWhileAsking, "one prompt open at a time, in the log too")
            val events = harness.events()
            val started = events.only<HandoffStarted>().single()
            assertEquals(PromptWithdrawn(handoff), events[events.indexOf(started) + 1])
            assertTrue(events.only<HandoffEnded>().isEmpty())
            assertEquals(1, surfaces.takenBack, "taken back once, not again")
        }

    @Test
    fun `control - a stop or a shutdown during a tool's handoff leaves the app with the user`() =
        runTest {
            for (stop in listOf<(LoopHarness, AgentLoop) -> Unit>(
                { _, loop -> loop.stop(StopCause.USER) },
                { harness, _ -> harness.loopScope.cancel() },
            )) {
                val surfaces = CountingSurfaces()
                val harness = harness(surfaces, Seen())
                val loop = harness.start()
                loop.followup(text("登录以后点一下"))
                loop.awaitPrompt()
                stop(harness, loop)
                loop.awaitStopped()

                assertEquals(1 to 0, surfaces.handed to surfaces.takenBack)
                assertEquals(1, harness.events().only<PromptWithdrawn>().size, "withdrawn once, by the repair")
            }
        }

    @Test
    fun `a take-back that fails after a cut ends the call with the exception's class only`() =
        runTest {
            val surfaces = CountingSurfaces(backFailure = IOException("display 7 token sk-test-not-a-real-key"))
            val seen = Seen()
            val harness = harness(surfaces, seen)
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            advanceUntilIdle()

            val result = harness.resultOf(c1)
            assertEquals(ToolStatus.ERROR, result.status)
            assertEquals(
                "${texts.toolError}dev.phoneagent.core.ports.ScreenUnavailableException\n${texts.screenUnknown}",
                (result.content.single() as Part.Text).text,
            )
            assertEquals(1, surfaces.takenBack, "taken back once, not again")
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
            val log =
                harness.storage.files.values
                    .joinToString("\n") { it.decodeToString() }
            assertTrue("sk-test-not-a-real-key" !in log)
        }

    @Test
    fun `a take-back that hangs after a cut fails within its limit`() =
        runTest {
            val surfaces = CountingSurfaces(takeBackMs = 60_000)
            val harness = harness(surfaces, Seen())
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            advanceUntilIdle()

            assertEquals(ToolStatus.ERROR, harness.resultOf(c1).status)
            val failedAt = harness.records().single { it.event is ToolResultRecorded && it.event.callId == c1 }.time
            assertEquals(1_000 + LoopConfig.DEFAULT_TAKE_BACK_TIMEOUT_MS, failedAt)
        }

    @Test
    fun `a take-back that fails after the budget cut ends the call as TIMEOUT`() =
        runTest {
            // The budget runs out while the app is being handed over; taking it back then fails.
            val surfaces = CountingSurfaces(handMs = 3_000, backFailure = IOException("display 7 gone"))
            val hand =
                FakeTool("hand", RiskClass.WRITE, timeoutMs = 1_000) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val model = FakeChatModel().calls(call("c1", "hand")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(hand), surfaces = surfaces)
            val loop = harness.start()
            loop.followup(text("登录"))
            loop.awaitPhase(Phase.Done)

            val result = harness.resultOf(c1)
            assertEquals(ToolStatus.TIMEOUT, result.status, "the budget decides, whatever the take-back threw")
            assertEquals("${texts.timeout}${texts.mayHaveTakenEffect}\n${texts.screenUnknown}", result.text())
            assertTrue(harness.events().none { it is ToolExecuted }, "the cut-off call records no execution end")
            assertEquals(1 to 1, surfaces.handed to surfaces.takenBack)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        }

    @Test
    fun `a take-back cut off by the budget is not started again`() =
        runTest {
            val surfaces = CountingSurfaces(takeBackMs = 60_000)
            val hand =
                FakeTool("hand", RiskClass.WRITE, timeoutMs = 5_000) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val model = FakeChatModel().calls(call("c1", "hand")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(hand), surfaces = surfaces)
            val loop = harness.start()
            loop.followup(text("登录"))
            assertTrue(loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone))
            advanceUntilIdle()

            assertEquals(ToolStatus.TIMEOUT, harness.resultOf(c1).status)
            assertEquals(1, surfaces.takenBack, "its ticket is spent")
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        }
}
