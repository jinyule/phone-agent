@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.testing.ScriptedScreen.Companion.snapshotOf
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An action or a launch that throws or is cancelled may still have reached the device (ScreenPort
 * contract), so it is recorded too, as `Failed` (naming the exception's class only, or "did not
 * finish" when cut off), with no read-back: a boundary like any other (phase1 §3.8). The screen
 * the call read before it is then no `ctx.screen`. In each case c0 (look) reads s1, c1 (tap,
 * WRITE) reads s2 and then moves, and c2 (press) is judged on no screen unless the move read back.
 */
class AgentLoopFailedActionTest {
    private val back = UiAction.Key(SystemKey.BACK)
    private val chat = LaunchTarget.App("com.example.chat")

    /** s1, then s2; every action or launch counts in [reached] and then does [move] instead. */
    private class ScriptedMoves(
        private val move: suspend () -> ActionOutcome,
    ) : ScreenPort by ScriptedScreen(snapshotOf("s1"), snapshotOf("s2")) {
        var reached = 0

        override suspend fun act(
            action: UiAction,
            on: SnapshotId?,
        ): ActionOutcome {
            reached++
            return move()
        }

        override suspend fun launch(target: LaunchTarget): ActionOutcome {
            reached++
            return move()
        }
    }

    private val look =
        FakeTool("look") { _, ctx ->
            ctx.screen!!.snapshot()
            ToolResult.ok("看到了")
        }

    /** tap reads its screen, then does [moves] on it; its budget is [timeoutMs]. */
    private fun tap(
        timeoutMs: Long = 10_000,
        moves: suspend (ScreenPort) -> Unit,
    ) = FakeTool("tap", RiskClass.WRITE, timeoutMs) { _, ctx ->
        val screen = ctx.screen!!
        screen.snapshot()
        moves(screen)
        ToolResult.ok("点了")
    }

    private fun TestScope.harness(
        tap: FakeTool,
        screen: ScreenPort,
        policy: ScriptedPolicy,
    ): LoopHarness {
        val model =
            FakeChatModel()
                .calls(call("c0", "look"))
                .calls(call("c1", "tap"))
                .calls(call("c2", "press"))
                .answers("好了")
        val tools = listOf(look, tap, FakeTool("press", RiskClass.WRITE))
        return LoopHarness(this, model, tools, policy, screen = screen)
    }

    /** The record of a move that [reason] ended: failed, effect unknown, no read-back. */
    private fun failed(reason: String) =
        ActionOutcome(ActionOutcome.Status.Failed(reason), ActionOutcome.Effect.UNKNOWN)

    private suspend fun LoopHarness.statusOf(id: String): ToolStatus =
        events()
            .only<ToolResultRecorded>()
            .single { it.callId.value == id }
            .result.status

    @Test
    fun `an action or a launch the budget cut off hides the screen its call read first`() =
        runTest {
            for (move in listOf<suspend (ScreenPort) -> Unit>({ it.act(back, on = null) }, { it.launch(chat) })) {
                val policy = ScriptedPolicy()
                val screen = ScriptedMoves { awaitCancellation() }
                val harness = harness(tap(timeoutMs = 1_000, moves = move), screen, policy)
                val loop = harness.start()
                loop.followup(text("看了再点"))
                loop.awaitPhase(Phase.Done)

                assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
                assertEquals(1, screen.reached)
                assertEquals(ToolStatus.TIMEOUT, harness.statusOf("c1"))
                val moved = harness.events().filter { it is ScreenActed || it is ScreenLaunched }
                val cut = failed(UNFINISHED)
                val either = listOf(ScreenActed(c1, null, back, cut), ScreenLaunched(c1, chat, cut))
                assertTrue(moved.size == 1 && moved.single() in either, "$moved")
            }
        }

    @Test
    fun `an action that throws hides the screen its call read first, naming the class only`() =
        runTest {
            val policy = ScriptedPolicy()
            val screen = ScriptedMoves { throw ScreenUnavailableException("display 7 gone, sk-test-not-a-real-key") }
            val harness = harness(tap { it.act(back, on = null) }, screen, policy)
            val loop = harness.start()
            loop.followup(text("看了再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
            assertEquals(ToolStatus.ERROR, harness.statusOf("c1"), "the tool let the exception through")
            val acted = harness.events().only<ScreenActed>().single()
            assertEquals(failed("threw ${ScreenUnavailableException::class.java.name}"), acted.outcome)
            val log =
                harness.storage.files.values
                    .joinToString("\n") { it.decodeToString() }
            assertTrue("sk-test-not-a-real-key" !in log)
        }

    @Test
    fun `an action the tool cut off itself hides the screen its call read first`() =
        runTest {
            val policy = ScriptedPolicy()
            val screen = ScriptedMoves { awaitCancellation() }
            val harness = harness(tap { withTimeoutOrNull(500) { it.act(back, on = null) } }, screen, policy)
            val loop = harness.start()
            loop.followup(text("看了再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
            assertEquals(ToolStatus.OK, harness.statusOf("c1"), "the tool went on after its own timeout")
            assertEquals(
                failed(UNFINISHED),
                harness
                    .events()
                    .only<ScreenActed>()
                    .single()
                    .outcome,
            )
        }

    @Test
    fun `an action that ends only after the budget cut its call off is recorded with its read-back`() =
        runTest {
            val policy = ScriptedPolicy()
            val done = ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED, after = snapshotOf("s3"))
            // The gesture runs to its end despite the cancellation, and then reads the screen back.
            val screen =
                ScriptedMoves {
                    withContext(NonCancellable) { delay(2_000) }
                    done
                }
            val harness = harness(tap(timeoutMs = 1_000) { it.act(back, on = null) }, screen, policy)
            val loop = harness.start()
            loop.followup(text("看了再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(ToolStatus.TIMEOUT, harness.statusOf("c1"))
            assertEquals(listOf(null, "s1", "s3"), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a cut-off action waits for a busy log, then is recorded`() =
        runTest {
            val policy = ScriptedPolicy()
            val acting = CompletableDeferred<Unit>()
            val screen =
                ScriptedMoves {
                    acting.complete(Unit)
                    awaitCancellation()
                }
            val harness = harness(tap(timeoutMs = 1_000) { it.act(back, on = null) }, screen, policy)
            val loop = harness.start()
            loop.followup(text("看了再点"))
            acting.await()
            // The intake holds the log while it records a steer, past the budget's end.
            val release = CompletableDeferred<Unit>()
            harness.storage.beforeAppend = {
                harness.storage.beforeAppend = {}
                release.await()
            }
            loop.steer(text("快一点"))
            advanceTimeBy(4_000)
            assertTrue(harness.events().only<ScreenActed>().isEmpty(), "still waiting for the log")
            release.complete(Unit)
            loop.awaitPhase(Phase.Done)

            val acted = harness.records().single { it.event is ScreenActed }
            assertEquals(failed(UNFINISHED), (acted.event as ScreenActed).outcome)
            assertEquals(4_000, acted.time, "written once the log was free")
            assertEquals(ToolStatus.TIMEOUT, harness.statusOf("c1"))
            assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a cut-off action on a failing log stops the task with ERROR`() =
        runTest {
            val acting = CompletableDeferred<Unit>()
            val screen =
                ScriptedMoves {
                    acting.complete(Unit)
                    awaitCancellation()
                }
            val harness = harness(tap(timeoutMs = 1_000) { it.act(back, on = null) }, screen, ScriptedPolicy())
            val loop = harness.start()
            loop.followup(text("看了再点"))
            acting.await()
            harness.storage.appendFailures = 1
            advanceUntilIdle()

            assertEquals(Phase.Stopped(StopCause.ERROR), loop.phase.value)
            assertEquals(ToolStatus.OUTCOME_UNKNOWN, harness.statusOf("c1"), "repaired on the reopened log")
        }

    private companion object {
        val c1 = ToolCallId("c1")

        /** Why a move a cancellation cut off failed. */
        const val UNFINISHED = "did not finish"
    }
}
