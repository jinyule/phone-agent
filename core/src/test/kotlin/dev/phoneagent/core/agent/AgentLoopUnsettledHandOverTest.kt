@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Only the port says where the app is (`SurfaceManager.handToUser`): a ticket means it was handed
 * over, a ScreenUnavailableException that nothing moved. Any other end of the hand-over (another
 * exception, an Error, the port's own timeout, a cut) leaves that unknown, whatever the job's
 * state: the call then ends with a failed take-back (ScreenUnavailableException, the screen note,
 * no fresh look), a policy hand-off answers its call so too (the turn goes on), and an Error
 * propagates as it is, the failed take-back attached to it.
 */
class AgentLoopUnsettledHandOverTest {
    private val c1 = ToolCallId("c1")
    private val texts = LoopTexts()
    private val failedTakeBack =
        "${texts.toolError}${ScreenUnavailableException::class.java.name}\n${texts.screenUnknown}"

    /** Counts the moves; handing over runs [hand] first, which may take time or throw. */
    private class Surfaces(
        private val hand: suspend () -> Unit = {},
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
            hand()
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            takenBack++
            return ticket.from
        }
    }

    /** The hand tool: hands off through [cut], then sets [wentOn]; [handoff] is what handOff returned. */
    private class Hand(
        cut: suspend (suspend () -> HandoffResult) -> HandoffResult?,
    ) {
        var wentOn = false
        var handoff: HandoffResult? = null
        val tool =
            FakeTool("hand", RiskClass.WRITE, timeoutMs = 10_000) { _, ctx ->
                handoff = cut { ctx.interaction.handOff(HandoffKind.LOGIN, "请登录") }
                wentOn = true
                ToolResult.ok("接着做")
            }
    }

    /** c1 is [hand]'s call, on a screen port that can always be read. */
    private fun TestScope.harness(
        hand: Hand,
        surfaces: Surfaces,
    ): LoopHarness =
        LoopHarness(
            this,
            FakeChatModel().calls(call("c1", "hand")).answers("好了"),
            tools = listOf(hand.tool),
            screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
            surfaces = surfaces,
        )

    /** Starts [harness], asks it to log in and runs it until it waits. */
    private suspend fun TestScope.played(harness: LoopHarness): LoopHarness {
        val loop = harness.start()
        loop.followup(text("登录"))
        advanceUntilIdle()
        return harness
    }

    private suspend fun LoopHarness.resultOf(id: ToolCallId): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId == id }.result

    /** Whether a screen was recorded after the handoff's last record in [events]. */
    private fun lookedAfterHandoff(events: List<SessionEvent>): Boolean {
        val last = events.indexOfLast { it is HandoffStarted || it is HandoffEnded || it is PromptWithdrawn }
        return events.drop(last + 1).any { it is ScreenObserved }
    }

    /** The call ended with a failed take-back: not taken back, not gone on, no fresh look. */
    private suspend fun assertFailedTakeBack(
        harness: LoopHarness,
        hand: Hand,
        surfaces: Surfaces,
    ) {
        val result = harness.resultOf(c1)
        assertEquals(ToolStatus.ERROR, result.status, "not as if the app were back")
        assertEquals(failedTakeBack, result.text())
        assertFalse(hand.wentOn, "the tool did not go on acting")
        assertEquals(1 to 0, surfaces.handed to surfaces.takenBack)
        assertFalse(lookedAfterHandoff(harness.events()), "no fresh look while the app may be with the user")
    }

    @Test
    fun `a hand-over cut off that then throws a plain IOException ends the call with a failed take-back`() =
        runTest {
            // The app is moved despite the cut; then the receipt fails with a plain IOException.
            val surfaces =
                Surfaces {
                    withContext(NonCancellable) { delay(2_000) }
                    throw IOException("receipt lost")
                }
            val hand = Hand { handOff -> withTimeoutOrNull(1_000) { handOff() } }
            val harness = played(harness(hand, surfaces))

            assertFailedTakeBack(harness, hand, surfaces)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
            assertTrue(harness.events().only<HandoffEnded>().isEmpty())
        }

    @Test
    fun `a hand-over its port's own timeout ends, with the job active, ends the call with a failed take-back`() =
        runTest {
            val surfaces =
                Surfaces {
                    withTimeout(1_000) {
                        withContext(NonCancellable) { delay(2_000) }
                        ensureActive()
                    }
                }
            val hand = Hand { handOff -> handOff() }
            val harness = played(harness(hand, surfaces))

            assertFailedTakeBack(harness, hand, surfaces)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        }

    @Test
    fun `a tool that recovers from the port's own timeout still does not go on`() =
        runTest {
            val surfaces =
                Surfaces {
                    withTimeout(1_000) {
                        withContext(NonCancellable) { delay(2_000) }
                        ensureActive()
                    }
                }
            val hand =
                Hand { handOff ->
                    // The probe: it takes the port's own timeout for no answer, and goes on.
                    try {
                        handOff()
                    } catch (expected: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    }
                }
            val harness = played(harness(hand, surfaces))

            assertFailedTakeBack(harness, hand, surfaces)
        }

    @Test
    fun `without a cut, a hand-over that throws a plain IOException ends the call with a failed take-back`() =
        runTest {
            val surfaces = Surfaces { throw IOException("moved, then the binder died") }
            val hand = Hand { handOff -> handOff() }
            val harness = played(harness(hand, surfaces))

            assertFailedTakeBack(harness, hand, surfaces)
            val ended = harness.events().only<HandoffEnded>().single()
            assertEquals(
                HandoffEnded(ended.promptId, HandoffResult.FAILED, "hand over failed: java.io.IOException"),
                ended,
            )
            assertTrue(harness.events().only<PromptWithdrawn>().isEmpty())
        }

    @Test
    fun `a hand-over that throws an Error lets the Error propagate, with the failed take-back attached`() =
        runTest {
            val bug = NotImplementedError("port bug")
            val surfaces = Surfaces { throw bug }
            val hand = Hand { handOff -> handOff() }
            val harness = played(harness(hand, surfaces))

            // Coroutines may hand on a copy of the Error, its cause the original.
            val escaped = harness.uncaught.single()
            assertTrue(generateSequence(escaped) { it.cause }.any { it === bug }, "not replaced: $escaped")
            assertEquals(listOf(ScreenUnavailableException::class.java), bug.suppressed.map { it.javaClass })
            assertFalse(hand.wentOn)
            assertEquals(1 to 0, surfaces.handed to surfaces.takenBack)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
            assertFalse(lookedAfterHandoff(harness.events()))
        }

    @Test
    fun `control - a hand-over that throws ScreenUnavailableException moved nothing, and the tool goes on`() =
        runTest {
            val surfaces = Surfaces { throw ScreenUnavailableException("display gone") }
            val hand = Hand { handOff -> handOff() }
            val harness = played(harness(hand, surfaces))

            assertEquals(ToolStatus.OK, harness.resultOf(c1).status)
            assertEquals(HandoffResult.FAILED, hand.handoff)
            assertTrue(hand.wentOn)
            assertEquals(
                "hand over failed: ${ScreenUnavailableException::class.java.name}",
                harness
                    .events()
                    .only<HandoffEnded>()
                    .single()
                    .detail,
            )
            assertTrue(lookedAfterHandoff(harness.events()), "the app never left: the runtime looks again")
            assertEquals(1 to 0, surfaces.handed to surfaces.takenBack)
        }

    @Test
    fun `a cut that lands before the hand-over begins moves nothing and needs no take-back`() =
        runTest {
            var armed = false
            val surfaces = Surfaces()
            val hand =
                Hand { handOff ->
                    withTimeoutOrNull(1_000) {
                        armed = true
                        handOff()
                    }
                }
            val harness = harness(hand, surfaces)
            // The cut lands while handoff/start is being written, past the log's own cancellation check.
            harness.storage.beforeAppend = {
                if (armed) {
                    armed = false
                    delay(2_000)
                }
            }
            played(harness)

            assertEquals(0 to 0, surfaces.handed to surfaces.takenBack, "the app never moved")
            assertNull(hand.handoff)
            assertTrue(hand.wentOn, "nothing to take back: the cancellation reached the tool's own timeout")
            assertEquals(ToolStatus.OK, harness.resultOf(c1).status)
            assertEquals(1, harness.events().only<PromptWithdrawn>().size)
        }

    @Test
    fun `a policy hand-off whose hand-over throws a plain IOException answers its call as a failed take-back`() =
        runTest {
            val policy = ScriptedPolicy(PolicySamples.handOff)
            val surfaces = Surfaces { throw IOException("moved, then the binder died") }
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
            loop.awaitPhase(Phase.Done)

            val result = harness.resultOf(c1)
            assertEquals(ToolStatus.ERROR, result.status, "not refused as if nothing had moved")
            assertEquals("${texts.handoffFailed}\n${texts.screenUnknown}", result.text())
            assertTrue(send.calls.isEmpty())
            assertEquals(1 to 0, surfaces.handed to surfaces.takenBack)
            assertFalse(lookedAfterHandoff(harness.events()))
            assertEquals(1, tap.calls.size)
            assertNull(policy.contexts.last().screen, "the next call is judged on no screen")
        }
}
