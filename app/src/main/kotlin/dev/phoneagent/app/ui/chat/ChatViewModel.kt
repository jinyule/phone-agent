package dev.phoneagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.phoneagent.app.service.TaskService
import dev.phoneagent.app.task.PendingPrompt
import dev.phoneagent.app.task.TaskController
import dev.phoneagent.app.task.TaskView
import dev.phoneagent.app.task.Tasks
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.PromptId
import kotlinx.coroutines.flow.StateFlow

/** What the chat screen can ask for. */
internal interface ChatActions {
    /**
     * The composer's [text]: the answer to the question the task waits for, else a command for the
     * task (a new one, the next one, or the start of the next run after a stop). Blank is ignored.
     */
    fun send(text: String)

    /** Answers prompt [id] with [reply]. */
    fun answer(
        id: PromptId,
        reply: UserReply,
    )

    /** Stops the task's run. */
    fun stop()

    /** Starts over with an empty chat (only when the task has no work). */
    fun newTask()
}

/**
 * The chat screen's state: the process's task ([Tasks]), which outlives the screen. A command
 * starts [TaskService] first ([startService]), from the visible screen, the only place it may
 * start, so the task keeps the CPU up and has its notification while it works.
 */
internal class ChatViewModel(
    private val tasks: TaskController,
    private val startService: () -> Unit,
) : ViewModel(),
    ChatActions {
    /** The task as the chat shows it. */
    val view: StateFlow<TaskView> = tasks.view

    override fun send(text: String) {
        if (text.isBlank()) return
        val question = view.value.chat?.waitingFor as? PendingPrompt.Ask
        if (question != null) {
            tasks.answer(question.id, UserReply.Answer(text.trim()))
            return
        }
        tasks.send(text)
        startService()
    }

    override fun answer(
        id: PromptId,
        reply: UserReply,
    ) {
        tasks.answer(id, reply)
    }

    override fun stop() {
        tasks.stop()
    }

    override fun newTask() {
        tasks.newTask()
    }

    companion object {
        /** Builds the view model on the process's task. */
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    ChatViewModel(Tasks.of(app)) { TaskService.start(app) }
                }
            }
    }
}
