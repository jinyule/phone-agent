package dev.phoneagent.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.phoneagent.app.MainActivity
import dev.phoneagent.app.R
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.app.task.Tasks
import dev.phoneagent.core.agent.LoopConfig
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process and the CPU up while the app's task has work ([TaskView.busy]): a foreground
 * service of type `specialUse` (an agent operating the phone for the user fits no other type) with
 * a notification that says what the task does, opens the chat and offers Stop, and a partial wake
 * lock.
 *
 * The chat starts it ([start]) right after it hands a command to [Tasks]; it stops itself once
 * the task has no work. A [ACTION_STOP] start stops the task's run ([TaskController.stop]). Should
 * the system destroy it while the task still has work, the run stops as a shutdown.
 *
 * The wake lock is renewed on every change of the task, each time for [WAKE_MS], longer than any
 * wait for the user ([LoopConfig] defaults), so it lapses only for a task that shows no change at
 * all for that long. The notification is posted again only when its text changes: the system drops
 * a package's notifications beyond a few a second, and a run writes records faster than that.
 */
class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    /** The text of the notification last posted, by [onStartCommand] or by [shown]. */
    private var posted: Int? = null

    override fun onCreate() {
        super.onCreate()
        val power = checkNotNull(getSystemService(PowerManager::class.java)) { "no PowerManager" }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.task_channel))
                .build(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val tasks = Tasks.of(this)
        if (intent?.action == ACTION_STOP) tasks.stop()
        // Every start through startForegroundService must reach startForeground, even a stop.
        val view = tasks.view.value
        val text = textOf(view.chat?.phase)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), FOREGROUND_TYPE)
        posted = text
        if (watching == null) watching = scope.launch { tasks.view.collect(::shown) }
        return START_NOT_STICKY
    }

    private fun shown(view: TaskView) {
        if (!view.busy) {
            if (wakeLock.isHeld) wakeLock.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        wakeLock.acquire(WAKE_MS)
        val text = textOf(view.chat?.phase)
        if (text == posted) return
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission") // Checked just above; without it the service runs unseen.
            manager.notify(NOTIFICATION_ID, notification(text))
            posted = text
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        val tasks = Tasks.of(this)
        if (tasks.view.value.busy) tasks.stop(StopCause.SHUTDOWN)
        super.onDestroy()
    }

    private fun notification(
        @StringRes text: Int,
    ): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java,
                ).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, TaskService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_task_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.task_stop), stop)
            .build()
    }

    companion object {
        /** Stops the task's run. */
        const val ACTION_STOP: String = "dev.phoneagent.action.STOP_TASK"

        private const val CHANNEL = "task"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_TAG = "PhoneAgent:task"
        private const val MARGIN_MS = 10 * 60_000L
        private val WAKE_MS =
            maxOf(
                LoopConfig.DEFAULT_CONFIRM_TIMEOUT_MS,
                LoopConfig.DEFAULT_QUESTION_TIMEOUT_MS,
                LoopConfig.DEFAULT_HANDOFF_TIMEOUT_MS,
            ) + MARGIN_MS

        private val FOREGROUND_TYPE =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }

        /** Starts the service from a visible screen of the app (the only place a foreground service may start). */
        internal fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java))
        }

        /** What the notification says about a task in [phase]. */
        internal fun textOf(phase: Phase?): Int =
            when ((phase as? Phase.Waiting)?.reason) {
                WaitReason.USER -> R.string.task_waiting_user
                WaitReason.HANDOFF -> R.string.task_waiting_handoff
                WaitReason.LOCK -> R.string.task_waiting_lock
                null -> R.string.task_running
            }
    }
}
