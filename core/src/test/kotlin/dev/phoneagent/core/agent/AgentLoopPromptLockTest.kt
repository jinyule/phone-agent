@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The single-prompt lock is released only once the log closed the prompt, judged by the log
 * (phase1 §3.2): its answer or end, else `prompt/withdrawn` written before the next prompt may
 * open, whatever cut the call off between the wait and that record; a handoff the call cut off is
 * taken back, or the call ends with a failed take-back (one cut off counts as failed). Only a stop
 * (left to the repair) and a failed log are exempt.
 *
 * Mostly c1 runs [first] (its own timeout of 1 s around a prompt, or a failure it catches) and
 * then asks a second question; the test answers that one and checks what the log held open
 * meanwhile. A prompt the log never opened is not closed; a prompt waiting for the lock opens
 * only once the one before it is closed in the log, and for a handoff back.
 */
class AgentLoopPromptLockTest {
    private val texts = LoopTexts()

    /**
     * Moves the task, in [moves]; taking it back takes [takeBackMs] (counted in [takenBack] when it
     * begins).
     */
    private class Surfaces(
        private val takeBackMs: Long = 0,
        private val handFailure: RuntimeException? = null,
    ) : SurfaceManager {
        val moves = mutableListOf<String>()
        val takenBack get() = moves.count { it == "back" }

        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            handFailure?.let { throw it }
            moves += "hand"
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            moves += "back"
            delay(takeBackMs)
            moves += "back done"
            return ticket.from
        }
    }

    /** c1 does [first], then asks a second question; [tap] as c2. */
    private fun TestScope.harness(
        surfaces: SurfaceManager? = null,
        first: suspend (ToolContext) -> Unit,
    ): LoopHarness {
        val ask =
            FakeTool("ask", RiskClass.WRITE) { _, ctx ->
                first(ctx)
                ctx.interaction.ask(Question("还要继续吗？"))
                ToolResult.ok("问好了")
            }
        val model =
            FakeChatModel()
                .calls(call("c1", "ask"))
                .calls(call("c2", "tap"))
                .answers("好了")
        return LoopHarness(this, model, tools = listOf(ask, FakeTool("tap", RiskClass.WRITE)), surfaces = surfaces)
    }

    /**
     * Holds the log from the next append on (the intake recording a steer) until the returned
     * deferred completes; returns once the log is held.
     */
    private suspend fun LoopHarness.holdLog(loop: AgentLoop): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        storage.beforeAppend = {
            storage.beforeAppend = {}
            held.complete(Unit)
            release.await()
        }
        loop.steer(text("快一点"))
        held.await()
        return release
    }

    /** Answers the second question, waits for the end; the prompts open while it waited. */
    private suspend fun LoopHarness.answerSecond(
        loop: AgentLoop,
        after: PromptId,
    ): Pair<PromptId, List<PromptId>> {
        val second = loop.awaitPrompt(after).promptId!!
        val open = openPrompts(records())
        assertTrue(loop.answer(second, UserReply.Answer("继续")))
        loop.awaitPhase(Phase.Done)
        return second to open
    }

    private fun List<SessionEvent>.closing(id: PromptId): List<SessionEvent> =
        filter {
            it == PromptWithdrawn(id) ||
                (it as? QuestionAnswered)?.promptId == id ||
                (it as? HandoffEnded)?.promptId == id
        }

    @Test
    fun `an answer cut off while it waits for the log is withdrawn before the next question opens`() =
        runTest {
            val harness = harness { withTimeoutOrNull(1_000) { it.interaction.ask(Question("哪个账号？")) } }
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            val release = harness.holdLog(loop)
            assertTrue(loop.answer(first, UserReply.Answer("默认账号")))
            advanceTimeBy(1_500)
            release.complete(Unit)
            val (second, open) = harness.answerSecond(loop, first)

            assertEquals(listOf(second), open, "one prompt open at a time, in the log too")
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(first)), harness.events().closing(first))
        }

    @Test
    fun `a handoff end cut off while it waits for the log is withdrawn before the next question opens`() =
        runTest {
            val surfaces = Surfaces()
            val harness =
                harness(surfaces) { withTimeoutOrNull(1_000) { it.interaction.handOff(HandoffKind.LOGIN, "请登录") } }
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            val release = harness.holdLog(loop)
            assertTrue(loop.answer(first, UserReply.HandoffDone))
            advanceTimeBy(1_500)
            release.complete(Unit)
            val (second, open) = harness.answerSecond(loop, first)

            assertEquals(listOf(second), open, "one prompt open at a time, in the log too")
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(first)), harness.events().closing(first))
            assertEquals(1, surfaces.takenBack, "taken back once, before the cut")
        }

    @Test
    fun `a take-back the tool cuts off ends the call with a failed take-back`() =
        runTest {
            val surfaces = Surfaces(takeBackMs = 1_000)
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    withTimeoutOrNull(1_000) { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                    ToolResult.ok("没等到用户")
                }
            val model = FakeChatModel().calls(call("c1", "hand")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(hand), surfaces = surfaces)
            val loop = harness.start()
            loop.followup(text("登录"))
            val handoff = loop.awaitPrompt().promptId!!
            advanceTimeBy(500)
            assertTrue(loop.answer(handoff, UserReply.HandoffDone))
            loop.awaitPhase(Phase.Done)

            val result =
                harness
                    .events()
                    .only<ToolResultRecorded>()
                    .single()
                    .result
            assertEquals(ToolStatus.ERROR, result.status, "not OK as if the app were back")
            assertEquals(
                "${texts.toolError}${ScreenUnavailableException::class.java.name}\n${texts.screenUnknown}",
                (result.content.single() as Part.Text).text,
            )
            assertEquals(1, surfaces.takenBack, "its ticket is spent: not taken back again")
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(handoff)), harness.events().closing(handoff))
        }

    @Test
    fun `a handoff waiting for the lock is handed over only once the one cut off before it is back`() =
        runTest {
            val surfaces = Surfaces(takeBackMs = 500)
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    coroutineScope {
                        launch { withTimeoutOrNull(1_000) { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") } }
                        launch { ctx.interaction.handOff(HandoffKind.CAPTCHA, "请验证") }
                    }
                    ToolResult.ok("都处理了")
                }
            val model = FakeChatModel().calls(call("c1", "hand")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(hand), surfaces = surfaces)
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            val second = loop.awaitPrompt(after = first).promptId!!
            val open = openPrompts(harness.records())
            val moved = surfaces.moves.toList()
            assertTrue(loop.answer(second, UserReply.HandoffDone))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(second), open)
            assertEquals(listOf("hand", "back", "back done", "hand"), moved, "the first is back before the second")
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(first)), harness.events().closing(first))
        }

    @Test
    fun `a question waiting for the lock is asked only once the one cut off before it is withdrawn`() =
        runTest {
            val ask =
                FakeTool("ask", RiskClass.WRITE) { _, ctx ->
                    coroutineScope {
                        launch { withTimeoutOrNull(1_000) { ctx.interaction.ask(Question("哪个账号？")) } }
                        launch { ctx.interaction.ask(Question("还要继续吗？")) }
                    }
                    ToolResult.ok("问好了")
                }
            val model = FakeChatModel().calls(call("c1", "ask")).answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(ask))
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            // The log is busy when the first question is cut off, so closing it waits for the log.
            val release = harness.holdLog(loop)
            advanceTimeBy(1_500)
            release.complete(Unit)
            val (second, _) = harness.answerSecond(loop, first)

            val events = harness.events()
            val asked = events.indexOfFirst { (it as? QuestionAsked)?.promptId == second }
            assertTrue(events.indexOf(PromptWithdrawn(first)) in 0..<asked, "$events")
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(first)), events.closing(first))
        }

    @Test
    fun `a cut that comes after the answer was recorded withdraws nothing`() =
        runTest {
            val harness = harness { withTimeoutOrNull(1_000) { it.interaction.ask(Question("哪个账号？")) } }
            val loop = harness.start()
            loop.followup(text("登录"))
            val first = loop.awaitPrompt().promptId!!
            // The answer's write has begun when the tool's timeout cuts in; it runs to its end.
            val written = CompletableDeferred<Unit>()
            harness.storage.beforeAppend = {
                harness.storage.beforeAppend = {}
                written.complete(Unit)
                delay(2_000)
            }
            assertTrue(loop.answer(first, UserReply.Answer("默认账号")))
            written.await()
            val (second, open) = harness.answerSecond(loop, first)

            assertEquals(listOf(second), open)
            val closing = harness.events().closing(first)
            assertEquals(1, closing.size, "closed once: $closing")
            assertTrue(closing.single() is QuestionAnswered)
        }

    @Test
    fun `a cut while the prompt's first record waits for the log closes nothing`() =
        runTest {
            val ready = CompletableDeferred<Unit>()
            val go = CompletableDeferred<Unit>()
            val harness =
                harness {
                    ready.complete(Unit)
                    go.await()
                    withTimeoutOrNull(1_000) { it.interaction.ask(Question("哪个账号？")) }
                }
            val loop = harness.start()
            loop.followup(text("登录"))
            ready.await()
            val release = harness.holdLog(loop)
            go.complete(Unit)
            advanceTimeBy(1_500)
            release.complete(Unit)
            val (second, open) = harness.answerSecond(loop, after = PromptId("none"))

            assertEquals(listOf(second), open)
            assertEquals(listOf(second), harness.events().only<QuestionAsked>().map { it.promptId }, "never asked")
            assertTrue(harness.events().none { it is PromptWithdrawn }, "nothing to close")
        }

    @Test
    fun `a prompt an unexpected failure left open is closed before the next question opens`() =
        runTest {
            val surfaces = Surfaces(handFailure = IllegalStateException("surface bug"))
            val harness =
                harness(surfaces) {
                    try {
                        it.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (expected: IllegalStateException) {
                        // The tool goes on after the surface's bug, and asks again.
                    }
                }
            val loop = harness.start()
            loop.followup(text("登录"))
            val (second, open) = harness.answerSecond(loop, after = PromptId("none"))

            assertEquals(listOf(second), open, "one prompt open at a time, in the log too")
            val handoff =
                harness
                    .events()
                    .only<HandoffStarted>()
                    .single()
                    .promptId
            assertEquals(listOf<SessionEvent>(PromptWithdrawn(handoff)), harness.events().closing(handoff))
            val events = harness.events()
            assertTrue(events.indexOf(PromptWithdrawn(handoff)) < events.indexOfFirst { it is QuestionAsked })
        }
}
