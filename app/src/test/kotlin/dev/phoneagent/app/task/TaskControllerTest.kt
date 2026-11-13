package dev.phoneagent.app.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** The app's task: starting, commands, stops and the next run, and a view that never misses a record. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
@RunWith(AndroidJUnit4::class)
class TaskControllerTest {
    private val task = FakeTask()
    private var starts = 0
    private var startFailure: Exception? = null
    private var startGate: CompletableDeferred<Unit>? = null
    private val prompt = PromptId("prompt-1")

    private fun TestScope.controller(): TaskController =
        TaskController(backgroundScope) {
            starts++
            startGate?.await()
            startFailure?.let { failure -> throw failure }
            task.started
        }

    /**
     * A stop for [cause] while a run is opened (held by [gate]): the run is stopped once open and
     * nothing is handed to it; the task stays busy until the run is [Phase.Stopped], then has no
     * work left for the service to keep up.
     */
    private fun TestScope.stoppedWhileOpening(
        tasks: TaskController,
        gate: CompletableDeferred<Unit>,
        cause: StopCause,
    ) {
        assertTrue("opening", tasks.view.value.starting)
        tasks.stop(cause)
        gate.complete(Unit)
        runCurrent()
        val opened = task.run
        assertEquals(listOf(cause), opened.stops)
        assertTrue("busy until the run has stopped", tasks.view.value.busy)

        opened.phase.value = Phase.Stopped(cause)
        runCurrent()
        assertEquals(emptyList<String>(), opened.followups)
        assertFalse(tasks.view.value.busy)
        assertEquals(
            Phase.Stopped(cause),
            tasks.view.value.chat
                ?.phase,
        )
    }

    private fun reply(text: String) =
        AssistantMessage(1, ProviderId("p"), ModelId("m"), listOf(Part.Text(text)), StopReason.END_TURN)

    /** The turn of a command, from its start to its end, and the run done. */
    private fun finishTurn(text: String) {
        task.append(TurnStarted(1))
        task.append(command(text))
        task.append(TurnEnded(1, TurnEndReason.COMPLETED))
        task.run.phase.value = Phase.Done
    }

    @Test
    fun theFirstCommandStartsATaskAndGivesItTheCommand() =
        runTest {
            val tasks = controller()
            tasks.send("  open settings  ")
            assertTrue("busy from the send on", tasks.view.value.busy)
            runCurrent()

            assertEquals(1, starts)
            assertEquals(listOf("open settings"), task.run.followups)
            assertEquals(TaskView(ChatState.of(task.log, Phase.Idle), handed = 1), tasks.view.value)

            task.append(TurnStarted(1))
            task.append(command("open settings"))
            runCurrent()
            assertEquals(0, tasks.view.value.handed)
            assertTrue("the turn is open", tasks.view.value.busy)

            task.append(TurnEnded(1, TurnEndReason.COMPLETED))
            task.run.phase.value = Phase.Done
            runCurrent()
            assertFalse(tasks.view.value.busy)
            assertEquals(
                listOf(ChatEntry.UserText("open settings")),
                tasks.view.value.chat
                    ?.entries,
            )
        }

    @Test
    fun aBlankCommandIsIgnored() =
        runTest {
            val tasks = controller()
            tasks.send(" \n ")
            runCurrent()
            assertEquals(0, starts)
            assertEquals(TaskView(), tasks.view.value)
        }

    @Test
    fun twoQuickCommandsStartOneTaskAndStayBusyUntilBothAreTaken() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            tasks.send("b")
            assertEquals(2, tasks.view.value.queued)
            runCurrent()
            assertEquals(1, starts)
            assertEquals(listOf("a", "b"), task.run.followups)
            assertEquals(2, tasks.view.value.handed)

            finishTurn("a")
            runCurrent()
            assertEquals(1, tasks.view.value.handed)
            assertTrue(tasks.view.value.busy)
        }

    @Test
    fun aStopDropsTheCommandsHandedToTheRun() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            assertTrue(tasks.view.value.busy)

            task.run.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()
            assertEquals(0, tasks.view.value.handed)
            assertFalse(tasks.view.value.busy)
        }

    @Test
    fun theNextCommandAfterAStopGoesToTheNextRunOfTheSameTask() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            val first = task.run
            first.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()

            tasks.send("b")
            runCurrent()
            assertEquals(1, starts)
            assertEquals(2, task.runs.size)
            assertEquals(listOf("a"), first.followups)
            assertEquals(listOf("b"), task.run.followups)
            assertEquals(1, tasks.view.value.handed)
        }

    @Test
    fun aCommandSentWhileTheStopTakesEffectWaitsForItAndGoesToTheNextRun() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            val first = task.run
            first.phase.value = Phase.Running(1, 1)
            runCurrent()

            tasks.stop()
            assertEquals(listOf(StopCause.USER), first.stops)
            tasks.send("b")
            runCurrent()
            assertEquals(listOf("a"), first.followups)
            assertEquals(1, task.runs.size)

            first.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()
            assertEquals(2, task.runs.size)
            assertEquals(listOf("b"), task.run.followups)
        }

    @Test
    fun theNextRunHasNoneOfTheStoppedRunsCommandsEvenIfTheViewMissedTheStop() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            val first = task.run
            // The view is reading the log for a prompt it does not know when the run stops, and the
            // next run replaces it before it sees the stop.
            val gate = CompletableDeferred<Unit>()
            task.gate = gate
            first.phase.value = Phase.Waiting(WaitReason.USER, prompt)
            runCurrent()
            tasks.stop()
            first.phase.value = Phase.Stopped(StopCause.USER)
            tasks.send("b")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, task.runs.size)
            assertEquals("only b is handed", 1, tasks.view.value.handed)

            finishTurn("b")
            runCurrent()
            assertFalse(tasks.view.value.busy)
        }

    @Test
    fun aStopWhileTheTaskIsCreatedStopsItsFirstRunAndDropsTheCommand() =
        runTest {
            startGate = CompletableDeferred()
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            stoppedWhileOpening(tasks, checkNotNull(startGate), StopCause.USER)
        }

    @Test
    fun aShutdownWhileTheTaskIsCreatedStopsItsFirstRunAsAShutdown() =
        runTest {
            startGate = CompletableDeferred()
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            stoppedWhileOpening(tasks, checkNotNull(startGate), StopCause.SHUTDOWN)
        }

    @Test
    fun aStopWhileTheNextRunIsOpenedStopsItAndDropsTheCommand() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            task.run.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            task.resumeGate = gate
            tasks.send("b")
            runCurrent()
            stoppedWhileOpening(tasks, gate, StopCause.USER)
            assertEquals(2, task.runs.size)
        }

    @Test
    fun aStopDropsTheCommandsSentBeforeItThatWereNotHandedYet() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            tasks.stop()
            runCurrent()
            assertEquals("no task opened for a dropped command", 0, starts)
            assertFalse(tasks.view.value.busy)

            tasks.send("b")
            runCurrent()
            val first = task.run
            first.phase.value = Phase.Running(1, 1)
            runCurrent()
            tasks.send("c")
            tasks.stop()
            runCurrent()
            assertEquals(listOf("b"), first.followups)
            first.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()
            assertEquals("no run opened for c", 1, task.runs.size)
            assertFalse(tasks.view.value.busy)
        }

    @Test
    fun aShutdownStopsTheRunAsAShutdown() =
        runTest {
            val tasks = controller()
            tasks.stop(StopCause.SHUTDOWN)
            tasks.stop()
            tasks.send("a")
            runCurrent()
            tasks.stop(StopCause.SHUTDOWN)
            assertEquals(listOf(StopCause.SHUTDOWN), task.run.stops)
        }

    @Test
    fun aTaskThatCannotStartSaysWhy() =
        runTest {
            val tasks = controller()
            for ((failure, problem) in listOf(
                TaskUnavailableException(TaskProblem.NO_MODEL) to TaskProblem.NO_MODEL,
                TaskUnavailableException(TaskProblem.KEY_MISSING) to TaskProblem.KEY_MISSING,
                IOException("disk full") to TaskProblem.STORAGE,
                IllegalStateException("bug") to TaskProblem.UNEXPECTED,
            )) {
                startFailure = failure
                tasks.send("a")
                runCurrent()
                assertEquals(TaskView(problem = problem), tasks.view.value)
            }

            startFailure = null
            tasks.send("a")
            assertNull("a new command clears the problem", tasks.view.value.problem)
            runCurrent()
            assertEquals(listOf("a"), task.run.followups)
        }

    @Test
    fun aLogThatCannotBeReadIsShownAndTheCommandStillGoes() =
        runTest {
            task.readFailure = IOException("unreadable")
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            assertEquals(TaskProblem.STORAGE, tasks.view.value.problem)
            assertEquals(listOf("a"), task.run.followups)
        }

    @Test
    fun aRecordAfterAGapRebuildsTheViewFromTheLog() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            assertEquals(1, task.reads)

            task.append(TurnStarted(1), silently = true)
            task.append(command("a"))
            runCurrent()
            assertEquals(2, task.reads)
            val chat = tasks.view.value.chat
            assertEquals(1, chat?.commands)
            assertEquals(true, chat?.openTurn)
            assertEquals(0, tasks.view.value.handed)
        }

    @Test
    fun aPromptTheViewNeverSawIsReadFromTheLog() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            val card = ConfirmCard(ToolCallId("call-1"), ConfirmReason.SPEND, "pay")
            task.append(ApprovalAsked(prompt, card, 100), silently = true)
            task.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
            runCurrent()
            assertEquals(
                PendingPrompt.Confirm(prompt, card),
                tasks.view.value.chat
                    ?.waitingFor,
            )
        }

    @Test
    fun recordsAppendedWhileTheLogIsReadStayInTheView() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()

            val gate = CompletableDeferred<Unit>()
            task.gate = gate
            val asked =
                task.append(
                    ApprovalAsked(prompt, ConfirmCard(ToolCallId("call-1"), ConfirmReason.SPEND, "pay"), 100),
                    silently = true,
                )
            task.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
            runCurrent()
            // The read began before these two: it will not return them.
            task.run.events.tryEmit(LoopEvent.Recorded(asked))
            task.append(reply("paying"))
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            val chat = tasks.view.value.chat
            assertEquals(ChatEntry.AssistantText("paying"), chat?.entries?.last())
            assertEquals(3L, chat?.lastSeq)
        }

    @Test
    fun anOlderLogReadEndingLastDoesNotTakeBackANewerPrompt() =
        runTest {
            val tasks = controller()
            val slow = CompletableDeferred<Unit>()
            task.readGates[2] = slow
            tasks.send("a")
            runCurrent()
            task.append(TurnStarted(1), silently = true)
            task.append(command("a"))
            runCurrent()
            assertEquals("the gap starts a read that ends at seq 3", 2, task.reads)

            val card = ConfirmCard(ToolCallId("call-1"), ConfirmReason.SPEND, "pay")
            task.append(ApprovalAsked(prompt, card, 1000), silently = true)
            task.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
            runCurrent()
            assertEquals(
                PendingPrompt.Confirm(prompt, card),
                tasks.view.value.chat
                    ?.waitingFor,
            )

            slow.complete(Unit)
            runCurrent()
            assertEquals(
                "still answerable",
                PendingPrompt.Confirm(prompt, card),
                tasks.view.value.chat
                    ?.waitingFor,
            )
            assertEquals(ChatState.of(task.log, task.run.phase.value), tasks.view.value.chat)
        }

    @Test
    fun overlappingReadsThatBothHeardRecordsEndInTheWholeLogWhicheverEndsFirst() =
        runTest {
            // The phase's read (2) ends at seq 5 and the events' read (3) at seq 6; both hear seq 6,
            // and read 2 also hears seq 7, folded in once read 3 is shown.
            val wrong =
                listOf(listOf(3, 2), listOf(2, 3)).filter { order ->
                    val log = FakeTask()
                    val tasks = TaskController(backgroundScope) { log.started }
                    val gates = (2..3).associateWith { CompletableDeferred<Unit>() }
                    log.readGates += gates
                    tasks.send("a")
                    runCurrent()
                    log.append(TurnStarted(1))
                    log.append(command("a"))
                    log.append(reply("one"))
                    runCurrent()
                    val card = ConfirmCard(ToolCallId("c"), ConfirmReason.SPEND, "pay")
                    log.append(ApprovalAsked(prompt, card, 1000), silently = true)
                    log.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
                    runCurrent()
                    log.append(reply("two"))
                    runCurrent()
                    log.append(reply("three"))
                    runCurrent()
                    check(log.reads == 3) { "reads ${log.reads}" }
                    for (read in order) {
                        gates.getValue(read).complete(Unit)
                        runCurrent()
                    }
                    tasks.view.value.chat != ChatState.of(log.log, log.run.phase.value)
                }
            assertEquals("orders whose view is not the whole log", emptyList<List<Int>>(), wrong)
        }

    @Test
    fun theNewTasksLogIsShownThoughItIsShorterThanTheOldOnes() =
        runTest {
            val second = FakeTask()
            var opened = 0
            val tasks = TaskController(backgroundScope) { if (opened++ == 0) task.started else second.started }
            tasks.send("a")
            runCurrent()
            finishTurn("a")
            task.append(reply("done"), silently = true)
            task.append(reply("again"))
            runCurrent()
            assertEquals("a gap: the old task's log was read to seq 6", 2, task.reads)
            tasks.newTask()
            runCurrent()

            tasks.send("b")
            runCurrent()
            assertEquals(
                "a read of a 1-record log after one of 6",
                ChatState.of(second.log, Phase.Idle),
                tasks.view.value.chat,
            )
        }

    @Test
    fun aNewTaskForgetsTheOldOneOnceItHasNoWork() =
        runTest {
            val tasks = controller()
            tasks.send("a")
            runCurrent()
            tasks.newTask()
            runCurrent()
            assertTrue("busy: kept", tasks.view.value.chat != null)

            finishTurn("a")
            runCurrent()
            tasks.newTask()
            runCurrent()
            assertEquals(TaskView(), tasks.view.value)
            tasks.send("b")
            runCurrent()
            assertEquals(2, starts)
        }

    @Test
    fun answersGoToTheCurrentRun() =
        runTest {
            val tasks = controller()
            assertFalse(tasks.answer(prompt, UserReply.Approve))
            tasks.send("a")
            runCurrent()

            assertTrue(tasks.answer(prompt, UserReply.Approve))
            task.run.answerTaken = false
            assertFalse(tasks.answer(prompt, UserReply.Reject))
            assertEquals(listOf(prompt to UserReply.Approve, prompt to UserReply.Reject), task.run.answers)
        }
}
