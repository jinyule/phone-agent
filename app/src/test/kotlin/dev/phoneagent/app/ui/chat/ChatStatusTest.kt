package dev.phoneagent.app.ui.chat

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.service.TaskService
import dev.phoneagent.app.task.FakeTask
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.app.task.TaskStatus
import dev.phoneagent.app.task.command
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The chat's status row says what the task does: a wait is told as the notification tells it, not as work. */
@RunWith(AndroidJUnit4::class)
class ChatStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val task = FakeTask()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler) + Job())
    private val tasks = TaskController(scope) { task.started }
    private val chat = ChatViewModel(tasks) {}

    @After
    fun cancel() = scope.cancel()

    /** Runs the task's work, then lets the chat catch up. */
    private fun settle() {
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    private fun said(text: Int) = compose.onNodeWithText(context.getString(text))

    @Test
    fun whileTheTaskWaitsForTheUserTheStatusRowSaysSoAndNotThatItWorks() {
        compose.setContent {
            val view by tasks.view.collectAsState()
            ChatScreen(view, chat, onModels = {})
        }
        chat.send("pay the bill")
        settle()
        task.append(TurnStarted(1))
        task.append(command("pay the bill"))
        task.run.phase.value = Phase.Running(1, 1)
        settle()
        said(R.string.chat_working).assertExists()

        val prompt = PromptId("prompt-1")
        task.append(ApprovalAsked(prompt, ConfirmCard(ToolCallId("call-1"), ConfirmReason.SPEND, "pay"), 1000))
        task.run.phase.value = Phase.Waiting(WaitReason.USER, prompt)
        settle()
        said(R.string.task_waiting_user).assertExists()
        said(R.string.chat_working).assertDoesNotExist()
        compose.onNodeWithTag(ChatTags.STOP).assertExists()

        task.run.phase.value = Phase.Waiting(WaitReason.LOCK, null)
        settle()
        said(R.string.task_waiting_lock).assertExists()
        said(R.string.chat_working).assertDoesNotExist()

        task.run.phase.value = Phase.Running(1, 2)
        settle()
        said(R.string.chat_working).assertExists()
        said(R.string.task_waiting_lock).assertDoesNotExist()
    }

    @Test
    fun theStatusRowAndTheNotificationTellEveryWaitInTheSameWords() {
        val waits = listOf(TaskStatus.WAITING_ANSWER, TaskStatus.WAITING_HANDOFF, TaskStatus.WAITING_UNLOCK)
        assertEquals(waits.map { TaskService.textOf(it) }, waits.map(::taskStatusText))
    }
}
