package dev.phoneagent.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.R
import dev.phoneagent.app.task.FakeTask
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.app.task.Tasks
import dev.phoneagent.app.task.command
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager

/** The foreground service that keeps a task with work running, and lets go once it has none. */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val task = FakeTask()
    private val tasks = TaskController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) { task.started }

    @Before
    fun install() {
        Tasks.install(context, tasks)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun started(intent: Intent = Intent(context, TaskService::class.java)): ServiceController<TaskService> =
        Robolectric
            .buildService(TaskService::class.java, intent)
            .create()
            .startCommand(0, 1)
            .also { idle() }

    private fun Notification.text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun shown(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(1)

    private fun finishTurn() {
        task.append(TurnStarted(1))
        task.append(command("a"))
        task.append(TurnEnded(1, TurnEndReason.COMPLETED))
        task.run.phase.value = Phase.Done
        idle()
    }

    @Test
    fun aTaskWithWorkRunsInTheForegroundHoldingAWakeLockUntilItHasNone() {
        tasks.send("a")
        idle()
        val service = started().get()
        val shadow = shadowOf(service)

        val foreground = checkNotNull(shadow.lastForegroundNotification) { "not in the foreground" }
        assertEquals(context.getString(R.string.task_running), foreground.text())
        assertEquals(listOf(context.getString(R.string.task_stop)), foreground.actions.map { it.title.toString() })
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        assertFalse(shadow.isStoppedBySelf)

        task.run.phase.value = Phase.Waiting(WaitReason.USER, PromptId("prompt-1"))
        idle()
        assertEquals(context.getString(R.string.task_waiting_user), shown()?.text())
        task.run.phase.value = Phase.Waiting(WaitReason.HANDOFF, PromptId("prompt-1"))
        idle()
        assertEquals(context.getString(R.string.task_waiting_handoff), shown()?.text())

        finishTurn()
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(wake.isHeld)
    }

    @Test
    fun theNotificationsStopActionStopsTheRun() {
        tasks.send("a")
        idle()
        started()
        started(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_STOP))
        assertEquals(listOf(StopCause.USER), task.run.stops)
    }

    @Test
    fun destroyedWhileTheTaskHasWorkStopsTheRunAsAShutdown() {
        tasks.send("a")
        idle()
        started().destroy()
        assertEquals(listOf(StopCause.SHUTDOWN), task.run.stops)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun startedWithoutWorkItStopsAtOnceAndStopsNothing() {
        tasks.send("a")
        idle()
        finishTurn()
        val controller = started()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
        assertEquals(emptyList<StopCause>(), task.run.stops)
    }

    @Test
    fun theNotificationSaysWhatTheTaskWaitsFor() {
        assertEquals(R.string.task_running, TaskService.textOf(null))
        assertEquals(R.string.task_running, TaskService.textOf(Phase.Running(1, 1)))
        assertEquals(R.string.task_waiting_user, TaskService.textOf(Phase.Waiting(WaitReason.USER, PromptId("p"))))
        assertEquals(
            R.string.task_waiting_handoff,
            TaskService.textOf(Phase.Waiting(WaitReason.HANDOFF, PromptId("p"))),
        )
        assertEquals(R.string.task_waiting_lock, TaskService.textOf(Phase.Waiting(WaitReason.LOCK, null)))
    }
}
