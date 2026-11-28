@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stop order (phase1 §3.7): no coroutine but the finisher writes any more, the inbox is
 * cleared, the turn is closed (`turn/end(STOPPED)`), and `task/stopped` comes right after it, last.
 */
class AgentLoopStopTest {
    private fun cleared(count: Int): InboxSpliced = InboxSpliced(InboxQueue.NEXT_TURN, removed = count)

    private fun added(value: String): InboxSpliced =
        InboxSpliced(
            InboxQueue.NEXT_TURN,
            removed = 0,
            added = listOf(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text(value)))),
        )

    /** Every call of a reply in [events] has a `tool/result` before the `task/stopped`. */
    private fun assertAllAnsweredBeforeStop(events: List<SessionEvent>) {
        val stoppedAt = events.indexOfFirst { it is TaskStopped }
        val calls = events.only<AssistantMessage>().flatMap { it.parts.filterIsInstance<Part.ToolCall>() }
        val answered = events.take(stoppedAt).only<ToolResultRecorded>().map { it.callId }
        assertEquals(calls.map { it.id }, answered)
    }

    @Test
    fun `a stop clears the inbox, closes the turn, then writes task-stopped last`() =
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

            val tail = harness.events().takeLast(5)
            assertEquals(cleared(1), tail[0])
            assertTrue((tail[1] as AssistantMessage).interrupted, "the delivered prefix")
            assertEquals(
                listOf(
                    StepEnded(1, 1, StepOutcome.CANCELLED),
                    TurnEnded(1, TurnEndReason.STOPPED),
                    TaskStopped(StopCause.USER),
                ),
                tail.drop(2),
            )
        }

    @Test
    fun `input in flight at the stop is recorded and cleared before the turn is closed`() =
        runTest {
            val model = FakeChatModel().hangs()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            harness.storage.beforeAppend = {
                harness.storage.beforeAppend = {}
                gate.await()
            }
            loop.followup(text("a"))
            loop.followup(text("b"))
            runCurrent()
            loop.stop(StopCause.USER)
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            val events = harness.events()
            val closing = events.dropWhile { it !is StepStarted }.drop(1)
            assertEquals(
                listOf(
                    added("a"),
                    added("b"),
                    cleared(2),
                    StepEnded(1, 1, StepOutcome.CANCELLED),
                    TurnEnded(1, TurnEndReason.STOPPED),
                    TaskStopped(StopCause.USER),
                ),
                closing.filterNot { it is UserMessage || it is RequestHeader },
            )
            assertEquals(emptyList<Throwable>(), harness.uncaught)
        }

    @Test
    fun `a stop while idle writes task-stopped only`() =
        runTest {
            val harness = LoopHarness(this)
            val loop = harness.start()
            advanceUntilIdle()
            loop.stop(StopCause.USER)
            loop.awaitStopped()
            assertEquals(listOf(LoopHarness.ATTENDED, TaskStopped(StopCause.USER)), harness.events())
        }

    @Test
    fun `when task-stopped is written every call has its result`() =
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

            val events = harness.events()
            assertAllAnsweredBeforeStop(events)
            assertEquals(listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(StopCause.USER)), events.takeLast(2))
        }

    @Test
    fun `an app-level limit stops the task with LIMIT and the turn ends STOPPED`() =
        runTest {
            val model = FakeChatModel().hangs()
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            loop.stop(StopCause.LIMIT)
            assertEquals(Phase.Stopped(StopCause.LIMIT), loop.awaitStopped())
            assertEquals(
                listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(StopCause.LIMIT)),
                harness.events().takeLast(2),
            )
        }

    @Test
    fun `the per-turn step limit ends the turn with LIMIT and stops nothing`() =
        runTest {
            val probe = FakeTool("probe")
            val model = FakeChatModel().calls(call("c1", "probe")).calls(call("c2", "probe"))
            val harness = LoopHarness(this, model, tools = listOf(probe), config = LoopConfig(maxStepsPerTurn = 1))
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(TurnEnded(1, TurnEndReason.LIMIT, "step limit 1 reached"), events.last())
            assertEquals(emptyList<TaskStopped>(), events.only<TaskStopped>())
            assertEquals(Phase.Idle, loop.phase.value)
        }

    @Test
    fun `a log failure ends the turn FAILED, then task-stopped ERROR`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel())
            val loop = harness.start()
            harness.storage.syncFailures = 1
            loop.followup(text("go"))
            assertEquals(Phase.Stopped(StopCause.ERROR), loop.awaitStopped())

            val tail = harness.events().takeLast(2)
            assertEquals(TurnEndReason.FAILED, (tail[0] as TurnEnded).reason)
            assertEquals(TaskStopped(StopCause.ERROR), tail[1])
        }

    @Test
    fun `after a shutdown the resumed task waits, and its next turn starts with the new input`() =
        runTest {
            val first = LoopHarness(this, FakeChatModel().hangs(delivered = "正在"))
            val loop = first.start()
            loop.followup(text("go"))
            first.model.awaitStarted(1)
            advanceUntilIdle()
            first.loopScope.cancel()
            assertEquals(Phase.Stopped(StopCause.SHUTDOWN), loop.awaitStopped())
            assertEquals(
                listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(StopCause.SHUTDOWN)),
                first.events().takeLast(2),
            )

            val model = FakeChatModel().answers("好了")
            val second = LoopHarness(this, model, storage = first.storage)
            val resumed = second.resume()
            advanceUntilIdle()
            assertEquals(Phase.Idle, resumed.phase.value)
            assertEquals(0, model.started.value, "nothing continues on its own")
            assertTrue(second.events().last() is SessionResumed)

            resumed.followup(text("again"))
            resumed.awaitPhase(Phase.Done)
            val turn = second.events().dropWhile { it !is SessionResumed }
            assertEquals(TurnStarted(2), turn.first { it is TurnStarted })
            val visible = turn.first { it is UserMessage || it is AssistantMessage }
            assertEquals(UserMessage(UserMessageKind.USER, listOf(Part.Text("again"))), visible)
        }
}
