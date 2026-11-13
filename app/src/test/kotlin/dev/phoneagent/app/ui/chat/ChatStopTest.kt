package dev.phoneagent.app.ui.chat

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.task.FakeTask
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.CompletableDeferred
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

/**
 * The chat's Stop while a run is opened (the task's first one, or the next one after a stop): it
 * shows, and a tap is the stop the task keeps until the run is open, which then takes no command.
 */
@RunWith(AndroidJUnit4::class)
class ChatStopTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val task = FakeTask()
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler) + Job())
    private val startGate = CompletableDeferred<Unit>()
    private val tasks =
        TaskController(scope) {
            startGate.await()
            task.started
        }
    private val chat = ChatViewModel(tasks) {}

    @After
    fun cancel() = scope.cancel()

    private fun showChat() {
        compose.setContent {
            val view by tasks.view.collectAsState()
            ChatScreen(view, chat, onModels = {})
        }
    }

    /** Runs the task's work, then lets the chat catch up. */
    private fun settle() {
        scheduler.runCurrent()
        compose.waitForIdle()
    }

    private fun stop() = compose.onNodeWithTag(ChatTags.STOP)

    private fun newTask() = compose.onNodeWithText(context.getString(R.string.chat_new_task))

    @Test
    fun stopShowsWhileTheTaskIsCreatedAndATapStopsItsFirstRunBeforeItTakesTheCommand() {
        showChat()
        stop().assertDoesNotExist()
        chat.send("open settings")
        settle()
        compose.onNodeWithText(context.getString(R.string.chat_starting)).assertExists()

        stop().performClick()
        startGate.complete(Unit)
        settle()
        assertEquals(listOf(StopCause.USER), task.run.stops)
        assertEquals(emptyList<String>(), task.run.followups)

        task.run.phase.value = Phase.Stopped(StopCause.USER)
        settle()
        stop().assertDoesNotExist()
        newTask().assertExists()
    }

    @Test
    fun stopShowsWhileTheNextRunIsOpenedAndATapStopsItBeforeItTakesTheCommand() {
        startGate.complete(Unit)
        showChat()
        chat.send("open settings")
        settle()
        task.run.phase.value = Phase.Stopped(StopCause.USER)
        settle()
        stop().assertDoesNotExist()

        val resumeGate = CompletableDeferred<Unit>()
        task.resumeGate = resumeGate
        chat.send("go on")
        settle()
        newTask().assertDoesNotExist()
        stop().performClick()
        resumeGate.complete(Unit)
        settle()
        val next = task.runs.last()
        assertEquals(2, task.runs.size)
        assertEquals(listOf(StopCause.USER), next.stops)
        assertEquals(emptyList<String>(), next.followups)

        next.phase.value = Phase.Stopped(StopCause.USER)
        settle()
        stop().assertDoesNotExist()
        newTask().assertExists()
    }
}
