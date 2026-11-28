package dev.phoneagent.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.task.FakeTask
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The chat's commands: to the task, or to the question it waits for. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {
    private val task = FakeTask()
    private val prompt = PromptId("prompt-1")

    @Test
    fun aCommandGoesToTheTaskAndStartsTheServiceOnceTheTaskIsBusy() =
        runTest {
            val tasks = TaskController(backgroundScope) { task.started }
            val busyAtStart = mutableListOf<Boolean>()
            val chat = ChatViewModel(tasks) { busyAtStart += tasks.view.value.busy }

            chat.send("  ")
            assertEquals(emptyList<Boolean>(), busyAtStart)
            chat.send("open settings")
            runCurrent()
            assertEquals(listOf(true), busyAtStart)
            assertEquals(listOf("open settings"), task.run.followups)
        }

    @Test
    fun whileTheTaskWaitsForAQuestionTheComposerAnswersIt() =
        runTest {
            val tasks = TaskController(backgroundScope) { task.started }
            var services = 0
            val chat = ChatViewModel(tasks) { services++ }
            chat.send("book a table")
            runCurrent()
            task.append(QuestionAsked(prompt, ToolCallId("call-1"), Question("for how many?")))
            task.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
            runCurrent()

            chat.send(" four ")
            chat.answer(prompt, UserReply.Decline)
            assertEquals(listOf(prompt to UserReply.Answer("four"), prompt to UserReply.Decline), task.run.answers)
            assertEquals(listOf("book a table"), task.run.followups)
            assertEquals(1, services)

            chat.stop()
            assertEquals(listOf(StopCause.USER), task.run.stops)
        }

    @Test
    fun aNewTaskStartsOverOnceTheOldOneHasNoWork() =
        runTest {
            val tasks = TaskController(backgroundScope) { task.started }
            val chat = ChatViewModel(tasks) {}
            chat.send("a")
            runCurrent()
            task.run.phase.value = Phase.Stopped(StopCause.USER)
            runCurrent()
            chat.newTask()
            runCurrent()
            assertTrue(chat.view.value.chat == null)
        }
}
