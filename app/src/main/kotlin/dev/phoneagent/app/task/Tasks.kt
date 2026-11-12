package dev.phoneagent.app.task

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.phoneagent.app.model.ModelServices
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process's [TaskController]: the chat and [dev.phoneagent.app.service.TaskService] share it.
 * It lives as long as the process, on the main thread; a failure nobody handled is logged by class
 * only (a message may quote a screen or the user's words) and ends nothing else.
 */
internal object Tasks {
    private const val TAG = "PhoneAgentTask"

    @Volatile
    private var instance: Pair<Context, TaskController>? = null

    /** The process-wide controller (one per application object, so tests get fresh ones). */
    fun of(context: Context): TaskController {
        val app = context.applicationContext
        return instance?.takeIf { it.first === app }?.second
            ?: synchronized(this) {
                instance?.takeIf { it.first === app }?.second
                    ?: TaskController(
                        scope(),
                        AppTaskLauncher(app, ModelServices.of(app)),
                    ).also { instance = app to it }
            }
    }

    /** Makes [controller] the controller of [context]'s application. */
    @VisibleForTesting
    fun install(
        context: Context,
        controller: TaskController,
    ) {
        synchronized(this) { instance = context.applicationContext to controller }
    }

    private fun scope(): CoroutineScope {
        val failures = CoroutineExceptionHandler { _, failure -> logFailure(failure) }
        return CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failures)
    }

    private fun logFailure(failure: Throwable) {
        Log.w(TAG, "task work failed (${failure.javaClass.name})")
    }
}
