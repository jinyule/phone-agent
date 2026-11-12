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
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import dev.phoneagent.core.tools.PhaseOneTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The card of the real hand_to_user (WP06) as the loop shows it: a result hand_to_user reported
 * itself is settled (FAILED for a decline, even when no screen could be read afterwards), and the
 * ERROR the runtime writes when handOff threw instead (a failed take-back: where the app is is
 * unknown) shows UNKNOWN, not FAILED.
 */
class AgentLoopHandToUserCardTest {
    private val c1 = ToolCallId("c1")
    private val texts = LoopTexts()
    private val handToUser = PhaseOneTools.create(ScreenFormatter()).single { it.def.name == "hand_to_user" }

    /** Hands over and back; [takeBack] runs before a take-back returns, and may throw. */
    private class Surfaces(
        private val takeBack: () -> Unit = {},
    ) : SurfaceManager {
        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket = HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            takeBack()
            return ticket.from
        }
    }

    private class Played(
        val result: ToolResult,
        val card: StepState,
    )

    /**
     * Runs c1, hand_to_user(login), on [surfaces]; the user answers with [reply] while handed
     * over, then [handedOver] runs. Returns c1's result and the state of its last card.
     */
    private suspend fun TestScope.played(
        surfaces: Surfaces,
        reply: UserReply,
        screen: ScriptedScreen = ScriptedScreen(ScriptedScreen.snapshotOf("s1")),
        handedOver: () -> Unit = {},
    ): Played {
        val model =
            FakeChatModel()
                .calls(call("c1", "hand_to_user", """{"kind":"login","reason":"请登录"}"""))
                .answers("好了")
        val harness = LoopHarness(this, model, tools = listOf(handToUser), screen = screen, surfaces = surfaces)
        val loop = harness.start()
        val events = mutableListOf<LoopEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { loop.events.toList(events) }
        runCurrent()
        loop.followup(text("登录"))
        val prompt = loop.awaitPrompt().promptId!!
        handedOver()
        loop.answer(prompt, reply)
        advanceUntilIdle()
        val result =
            harness
                .events()
                .only<ToolResultRecorded>()
                .single { it.callId == c1 }
                .result
        val card = events.filterIsInstance<LoopEvent.Card>().last { it.callId == c1 }.card
        return Played(result, card.state)
    }

    @Test
    fun `a failed take-back of hand_to_user shows unknown`() =
        runTest {
            // Not an IOException, so the take-back does not settle: handOff throws, the runtime answers.
            val played = played(Surfaces { throw IllegalStateException("binder gone") }, UserReply.HandoffDone)

            assertEquals(ToolStatus.ERROR, played.result.status)
            assertEquals(
                "${texts.toolError}${ScreenUnavailableException::class.java.name}\n${texts.screenUnknown}",
                played.result.text(),
            )
            assertEquals(StepState.UNKNOWN, played.card, "the app may be with the user")
        }

    @Test
    fun `a decline hand_to_user reported itself shows failed, with or without a screen afterwards`() =
        runTest {
            val read = played(Surfaces(), UserReply.Decline)
            assertEquals(ToolStatus.ERROR, read.result.status)
            assertEquals(StepState.FAILED, read.card)

            // The app was taken back, but the look afterwards fails: the result gets the screen note.
            val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
            val unread = played(Surfaces(), UserReply.Decline, screen) { screen.unavailable = "gone" }
            assertEquals(ToolStatus.ERROR, unread.result.status)
            assertEquals(
                texts.screenUnknown,
                unread.result
                    .text()
                    .lines()
                    .last(),
            )
            assertEquals(StepState.FAILED, unread.card, "taken back: the user declined, nothing uncertain")
        }
}
