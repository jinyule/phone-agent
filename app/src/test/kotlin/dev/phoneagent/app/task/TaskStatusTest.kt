package dev.phoneagent.app.task

import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.session.StopCause
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the task is doing, as the chat's status row and the notification tell it, and whether it can be stopped. */
class TaskStatusTest {
    private val prompt = PromptId("prompt-1")

    private fun inPhase(phase: Phase) = ChatState().phase(phase)

    @Test
    fun aWaitIsToldAsWhatTheTaskWaitsForAndNotAsWork() {
        val cases =
            listOf(
                TaskView() to TaskStatus.IDLE,
                TaskView(chat = inPhase(Phase.Done)) to TaskStatus.IDLE,
                TaskView(chat = inPhase(Phase.Stopped(StopCause.USER))) to TaskStatus.IDLE,
                TaskView(queued = 1) to TaskStatus.WORKING,
                TaskView(chat = inPhase(Phase.Done), handed = 1) to TaskStatus.WORKING,
                TaskView(chat = inPhase(Phase.Idle), handed = 1) to TaskStatus.WORKING,
                TaskView(chat = ChatState(openTurn = true)) to TaskStatus.WORKING,
                TaskView(chat = inPhase(Phase.Running(1, 1))) to TaskStatus.WORKING,
                TaskView(starting = true, queued = 1) to TaskStatus.STARTING,
                TaskView(chat = inPhase(Phase.Stopped(StopCause.USER)), starting = true) to TaskStatus.STARTING,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.USER, prompt))) to TaskStatus.WAITING_ANSWER,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.USER, prompt)), queued = 1) to
                    TaskStatus.WAITING_ANSWER,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.HANDOFF, prompt))) to TaskStatus.WAITING_HANDOFF,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.LOCK, null))) to TaskStatus.WAITING_UNLOCK,
            )
        val wrong = cases.filter { (view, status) -> view.status != status }.map { (view, _) -> view to view.status }
        assertEquals(emptyList<Pair<TaskView, TaskStatus>>(), wrong)
    }

    @Test
    fun aCommandOnItsWayAndARunBeingOpenedRunningOrWaitingCanBeStopped() {
        val cases =
            listOf(
                TaskView() to false,
                TaskView(chat = inPhase(Phase.Idle)) to false,
                TaskView(chat = inPhase(Phase.Done)) to false,
                TaskView(chat = inPhase(Phase.Stopped(StopCause.USER))) to false,
                TaskView(starting = true) to true,
                TaskView(queued = 1) to true,
                // Handed to the run and not yet taken into a turn: the run's stop drops it.
                TaskView(chat = inPhase(Phase.Idle), handed = 1) to true,
                TaskView(chat = inPhase(Phase.Done), handed = 1) to true,
                TaskView(chat = inPhase(Phase.Running(1, 1))) to true,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.USER, prompt))) to true,
                TaskView(chat = inPhase(Phase.Waiting(WaitReason.LOCK, null))) to true,
            )
        val wrong = cases.filter { (view, can) -> view.stoppable != can }.map { (view, _) -> view to view.stoppable }
        assertEquals(emptyList<Pair<TaskView, Boolean>>(), wrong)
    }
}
