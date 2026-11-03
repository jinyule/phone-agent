@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A take-back is done only when the port returns: after a cut, any other end of it (an
 * exception, a cancellation of the port's own, the port's own timeout) is a failed take-back, so
 * handOff throws ScreenUnavailableException and the tool does not go on; an Error propagates as
 * it is, the failed take-back suppressed on it. The same holds for a take-back in the normal
 * flow, and a policy hand-off answers its call so (ERROR, the turn going on).
 */
class AgentLoopUnsettledTakeBackTest {
    private val c1 = ToolCallId("c1")
    private val texts = LoopTexts()
    private val failedTakeBack =
        "${texts.toolError}${ScreenUnavailableException::class.java.name}\n${texts.screenUnknown}"

    /** Hands over at once; taking back runs [back] first, which may take time or throw. */
    private class Surfaces(
        private val back: suspend () -> Unit = {},
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
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            takenBack++
            back()
            return ticket.from
        }
    }

    /** The hand tool: hands off through [cut], then sets [wentOn]. */
    private class Hand(
        cut: suspend (suspend () -> Unit) -> Unit = { handOff -> withTimeoutOrNull(1_000) { handOff() } },
    ) {
        var wentOn = false
        val tool =
            FakeTool("hand", RiskClass.WRITE, timeoutMs = 10_000) { _, ctx ->
                cut { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                wentOn = true
                ToolResult.ok("接着做")
            }
    }

    /** c1 is [hand]'s call; nobody answers its handoff, so its own cut (1 s) takes the app back. */
    private suspend fun TestScope.played(
        hand: Hand,
        surfaces: Surfaces,
    ): LoopHarness {
        val harness =
            LoopHarness(
                this,
                FakeChatModel().calls(call("c1", "hand")).answers("好了"),
                tools = listOf(hand.tool),
                screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
                surfaces = surfaces,
            )
        val loop = harness.start()
        loop.followup(text("登录"))
        advanceUntilIdle()
        return harness
    }

    private suspend fun LoopHarness.resultOf(id: ToolCallId): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId == id }.result

    /** c1 ended with a failed take-back after one attempt to take the app back; the tool did not go on. */
    private suspend fun assertFailedTakeBack(
        harness: LoopHarness,
        hand: Hand,
        surfaces: Surfaces,
    ) {
        val result = harness.resultOf(c1)
        assertEquals(ToolStatus.ERROR, result.status, "not as if the app were back")
        assertEquals(failedTakeBack, result.text())
        assertFalse(hand.wentOn, "the tool did not go on acting")
        assertEquals(1 to 1, surfaces.handed to surfaces.takenBack)
        assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        assertTrue(harness.events().only<HandoffEnded>().isEmpty())
    }

    @Test
    fun `a take-back after a cut that throws a cancellation of the port's own is a failed take-back`() =
        runTest {
            // E.g. the port's own scope was cancelled, or a binder death cancelled its continuation.
            val surfaces = Surfaces { throw CancellationException("port scope cancelled") }
            val hand = Hand()
            val harness = played(hand, surfaces)

            assertFailedTakeBack(harness, hand, surfaces)
        }

    @Test
    fun `a take-back after a cut that the port's own timeout ends is a failed take-back`() =
        runTest {
            val surfaces = Surfaces { withTimeout(500) { delay(1_000) } }
            val hand = Hand()
            val harness = played(hand, surfaces)

            assertFailedTakeBack(harness, hand, surfaces)
        }

    @Test
    fun `a take-back after a cut that throws an IllegalStateException is a failed take-back`() =
        runTest {
            val surfaces = Surfaces { throw IllegalStateException("port bug") }
            val hand = Hand()
            val harness = played(hand, surfaces)

            assertFailedTakeBack(harness, hand, surfaces)
        }

    @Test
    fun `a take-back after a cut that throws an Error lets it propagate, the failed take-back attached`() =
        runTest {
            val bug = NotImplementedError("port bug")
            val surfaces = Surfaces { throw bug }
            val hand = Hand()
            val harness = played(hand, surfaces)

            // Coroutines may hand on a copy of the Error, its cause the original.
            val escaped = harness.uncaught.single()
            assertTrue(generateSequence(escaped) { it.cause }.any { it === bug }, "not replaced: $escaped")
            assertEquals(listOf(ScreenUnavailableException::class.java), bug.suppressed.map { it.javaClass })
            assertFalse(hand.wentOn)
            assertEquals(1 to 1, surfaces.handed to surfaces.takenBack)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        }

    @Test
    fun `control - a normal-flow take-back that throws the port's own cancellation is a failed take-back`() =
        runTest {
            val surfaces = Surfaces { throw CancellationException("port scope cancelled") }
            val hand = Hand { handOff -> handOff() }
            val harness =
                LoopHarness(
                    this,
                    FakeChatModel().calls(call("c1", "hand")).answers("好了"),
                    tools = listOf(hand.tool),
                    screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
                    surfaces = surfaces,
                )
            val loop = harness.start()
            loop.followup(text("登录"))
            assertTrue(loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone))
            advanceUntilIdle()

            assertFailedTakeBack(harness, hand, surfaces)
        }

    @Test
    fun `a policy hand-off whose take-back throws an IllegalStateException is a failed take-back`() =
        runTest {
            val policy = ScriptedPolicy(PolicySamples.handOff)
            val surfaces = Surfaces { throw IllegalStateException("port bug") }
            val send = FakeTool("send", RiskClass.OUTWARD)
            val tap = FakeTool("tap", RiskClass.WRITE)
            val model = FakeChatModel().calls(call("c1", "send")).calls(call("c2", "tap")).answers("好了")
            val harness =
                LoopHarness(
                    this,
                    model,
                    tools = listOf(send, tap),
                    policy = policy,
                    screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
                    surfaces = surfaces,
                )
            val loop = harness.start()
            loop.followup(text("发消息"))
            assertTrue(loop.answer(loop.awaitPrompt().promptId!!, UserReply.HandoffDone))
            loop.awaitPhase(Phase.Done)

            assertEquals(
                ToolResult.failure(ToolStatus.ERROR, "${texts.handoffFailed}\n${texts.screenUnknown}"),
                harness.resultOf(c1),
            )
            assertTrue(send.calls.isEmpty())
            assertEquals(1 to 1, surfaces.handed to surfaces.takenBack)
            assertEquals(1, tap.calls.size, "the turn goes on")
            assertNull(policy.contexts.last().screen, "the next call is judged on no screen")
        }
}
