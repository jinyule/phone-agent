@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffKind
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
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A prompt whose wait the call cut off (its budget, a cancellation inside the tool) while the turn
 * goes on gets `prompt/withdrawn` before the call's `tool/result`, the record the repair writes for
 * a stopped task: so no view rebuilt from the log keeps it open. The screen side of a cut handoff
 * is [AgentLoopHandoffScreenTest]'s; a stop or a failed turn is left to the repair.
 */
class AgentLoopCutPromptTest {
    private val c1 = ToolCallId("c1")
    private val tap = FakeTool("tap", RiskClass.WRITE)

    /** Moves the task; taking it back takes [takeBackMs]; handing it over throws [handFailure]. */
    private class SlowSurfaces(
        private val takeBackMs: Long = 0,
        private val handFailure: RuntimeException? = null,
    ) : SurfaceManager {
        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            handFailure?.let { throw it }
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            delay(takeBackMs)
            return ticket.from
        }
    }

    /** [cutting] as c1, then tap as c2 in the same turn. */
    private fun TestScope.harness(
        cutting: FakeTool,
        surfaces: SurfaceManager? = null,
    ): LoopHarness {
        val model =
            FakeChatModel()
                .calls(call("c1", cutting.def.name))
                .calls(call("c2", "tap"))
                .answers("好了")
        return LoopHarness(this, model, tools = listOf(cutting, tap), surfaces = surfaces)
    }

    /**
     * [opened] (asked by c1) is withdrawn once, after it was asked and before c1's result, and
     * nothing else closed it; the turn went on to tap.
     */
    private fun assertWithdrawnBeforeResult(
        events: List<SessionEvent>,
        opened: PromptId,
        loop: AgentLoop,
    ) {
        val asked =
            events.indexOfFirst {
                (it as? HandoffStarted)?.promptId == opened || (it as? QuestionAsked)?.promptId == opened
            }
        val withdrawn = events.indexOf(PromptWithdrawn(opened))
        val result = events.indexOfFirst { it is ToolResultRecorded && it.callId == c1 }
        assertTrue(asked in 0..<withdrawn, "prompt/withdrawn follows the prompt: $events")
        assertTrue(withdrawn < result, "and comes before the call's result: $events")
        assertEquals(1, events.only<PromptWithdrawn>().size)
        assertTrue(events.none { it is HandoffEnded || it is QuestionAnswered }, "nothing else closed it")
        assertEquals(1, tap.calls.size, "the turn went on")
        assertEquals(Phase.Done, loop.phase.value)
    }

    @Test
    fun `taking the task back past the tool's budget withdraws the handoff, and the turn goes on`() =
        runTest {
            val hand =
                FakeTool("hand", RiskClass.WRITE, timeoutMs = 5_000) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val harness = harness(hand, SlowSurfaces(takeBackMs = 60_000))
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            val waiting = loop.awaitPrompt().promptId!!
            assertTrue(loop.answer(waiting, UserReply.HandoffDone))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(
                ToolStatus.TIMEOUT,
                events
                    .only<ToolResultRecorded>()
                    .first()
                    .result.status,
            )
            assertWithdrawnBeforeResult(events, waiting, loop)
        }

    @Test
    fun `a tool that stops waiting for its handoff withdraws it, and the turn goes on`() =
        runTest {
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    withTimeoutOrNull(1_000) { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                    ToolResult.ok("没等到用户")
                }
            val harness = harness(hand)
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            val waiting = loop.awaitPrompt().promptId!!
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(
                ToolStatus.OK,
                events
                    .only<ToolResultRecorded>()
                    .first()
                    .result.status,
            )
            assertWithdrawnBeforeResult(events, waiting, loop)
        }

    @Test
    fun `a tool whose handoff breaks unexpectedly withdraws it, and the turn goes on`() =
        runTest {
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val harness = harness(hand, SlowSurfaces(handFailure = IllegalStateException("surface bug")))
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(
                ToolStatus.ERROR,
                events
                    .only<ToolResultRecorded>()
                    .first()
                    .result.status,
            )
            assertWithdrawnBeforeResult(events, events.only<HandoffStarted>().single().promptId, loop)
        }

    @Test
    fun `a tool that stops waiting for its question withdraws it, and the turn goes on`() =
        runTest {
            val ask =
                FakeTool("ask", RiskClass.READ) { _, ctx ->
                    withTimeoutOrNull(1_000) { ctx.interaction.ask(Question("哪个账号？")) }
                    ToolResult.ok("没等到用户")
                }
            val harness = harness(ask)
            val loop = harness.start()
            loop.followup(text("登录"))
            val waiting = loop.awaitPrompt().promptId!!
            advanceUntilIdle()

            assertWithdrawnBeforeResult(harness.events(), waiting, loop)
        }

    @Test
    fun `control - a handoff that ends needs no withdrawal`() =
        runTest {
            val hand =
                FakeTool("hand", RiskClass.WRITE) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val harness = harness(hand)
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            assertTrue(loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            assertEquals(1, events.only<HandoffEnded>().size)
            assertTrue(events.only<PromptWithdrawn>().isEmpty())
        }
}
