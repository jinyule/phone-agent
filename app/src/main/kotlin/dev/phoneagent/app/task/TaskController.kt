package dev.phoneagent.app.task

import android.util.Log
import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.TaskRuntime
import dev.phoneagent.core.agent.UserInput
import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StopCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.TreeMap

/**
 * One task: the way to open the next run of it after a stop (phase1 §3.7: the task goes on in a
 * new runtime on the same log), and its log.
 */
internal interface TaskSession {
    /** Opens the next run of the task on its log, running in [scope]; it waits for the next command. */
    suspend fun resume(scope: CoroutineScope): TaskRuntime

    /** The task's log as written so far. Throws [IOException] when it cannot be read. */
    suspend fun log(): SessionLog

    /** The cards of the task's tools. */
    val cards: StepCards
}

/** A started task: its session and the runtime of its first run. */
internal class StartedTask(
    val session: TaskSession,
    val runtime: TaskRuntime,
)

/**
 * Starts tasks. Throws [TaskUnavailableException] when no task can start for a reason the user can
 * act on, [IOException] when the session log cannot be created.
 */
internal fun interface TaskLauncher {
    /** Starts a new task whose runtime runs in [scope]. */
    suspend fun start(scope: CoroutineScope): StartedTask
}

/** No task can start, for [reason]. */
internal class TaskUnavailableException(
    val reason: TaskProblem,
) : Exception("task unavailable: $reason")

/** What kept a command from reaching a task. */
internal enum class TaskProblem {
    /** No main model is set up, or its settings cannot be used. */
    NO_MODEL,

    /** The main model's key must be entered again. */
    KEY_MISSING,

    /** The session log could not be created, opened or read. */
    STORAGE,

    /** Anything else; only its class was logged. */
    UNEXPECTED,
}

/**
 * What the chat shows and what keeps the task's service up.
 *
 * @property chat the current task, `null` before its first command.
 * @property starting whether a task or its next run is being opened.
 * @property problem what kept the last command from reaching a task, until the next command.
 * @property queued commands sent and not yet handed to a runtime.
 * @property handed commands handed to the current runtime that it has not yet taken into a turn
 *   (`user/message`); a stop drops them (`TaskRuntime.stop` clears the inbox).
 */
internal data class TaskView(
    val chat: ChatState? = null,
    val starting: Boolean = false,
    val problem: TaskProblem? = null,
    val queued: Int = 0,
    val handed: Int = 0,
) {
    /** Whether the task has work: a command on its way, a turn open, or a run that runs or waits. */
    val busy: Boolean
        get() = starting || queued > 0 || handed > 0 || chat?.busy == true
}

/**
 * The app's task (one at a time, M1): starts it with the first command, gives it the next ones,
 * opens its next run after a stop, and folds its records and phase into [view].
 *
 * Commands are handled one after the other under one lock, so two quick sends cannot start two
 * tasks, and a command sent while [stop] takes effect waits for the stop and goes to the next run.
 * A stop drops every command sent before it: those handed to the run (the runtime clears its
 * inbox) and those not handed yet. A stop that comes while a run is being opened is kept, and the
 * run is stopped as soon as it is open, before anything is handed to it.
 * A view that missed events still shows the truth (`TaskRuntime.events`): when a record comes after
 * a gap in seq, or the task waits for a prompt the view does not know, the view is rebuilt from the
 * log.
 *
 * Threading: [scope] runs on one thread (the main thread in the app), and every method is called
 * there; the controller's state is touched nowhere else. Runtimes run where [launcher] and the
 * [TaskSession] put them.
 */
internal class TaskController(
    private val scope: CoroutineScope,
    private val launcher: TaskLauncher,
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(TaskView())

    /** The chat as it is now. */
    val view: StateFlow<TaskView> = current.asStateFlow()

    private val shownProblem: (TaskProblem) -> Unit = { problem -> current.update { it.copy(problem = problem) } }

    private var session: TaskSession? = null
    private var runtime: TaskRuntime? = null
    private var stopping: TaskRuntime? = null

    /** Counts the stops; a command sent before the latest one is dropped. */
    private var stops = 0

    /** Whether a run is being opened; [pendingStop] is the stop to give it once it is open. */
    private var opening = false
    private var pendingStop: StopCause? = null
    private var watching: Job? = null
    private val heard = TreeMap<Long, SessionRecord>()
    private var reading = 0

    /**
     * Gives [text] (trimmed; ignored when blank) to the task: starts one first if there is none,
     * opens the next run after a stop. [TaskView.busy] holds from this call on.
     */
    fun send(text: String) {
        val command = text.trim()
        if (command.isEmpty()) return
        val sentAfter = stops
        current.update { it.copy(queued = it.queued + 1, problem = null) }
        scope.launch {
            lock.withLock {
                try {
                    val target = runtimeForCommand(sentAfter)
                    if (target != null) {
                        current.update { it.copy(handed = it.handed + 1) }
                        target.followup(UserInput(listOf(Part.Text(command))))
                    }
                } finally {
                    current.update { it.copy(queued = it.queued - 1) }
                }
            }
        }
    }

    /** Answers prompt [id] with [reply]; `false` when the prompt no longer takes it. */
    fun answer(
        id: PromptId,
        reply: UserReply,
    ): Boolean = runtime?.answer(id, reply) ?: false

    /**
     * Stops the task's current run for [cause]: [StopCause.USER] by default, [StopCause.SHUTDOWN]
     * when the app's service goes away. Commands sent before it are dropped; a run being opened is
     * stopped once it is open. The next command continues the task.
     */
    fun stop(cause: StopCause = StopCause.USER) {
        stops++
        if (opening) {
            pendingStop = cause
            return
        }
        val running = runtime ?: return
        stopping = running
        running.stop(cause)
    }

    /** Forgets the task unless it is busy, so the next command starts a new one. */
    fun newTask() {
        scope.launch {
            lock.withLock {
                if (current.value.busy) return@withLock
                watching?.cancel()
                watching = null
                session = null
                runtime = null
                stopping = null
                heard.clear()
                current.value = TaskView()
            }
        }
    }

    /**
     * The run that takes a command sent after stop number [sentAfter]: the current one, or the next
     * one opened; `null` when none opens or a stop came after the command. Waits for a stop to take
     * effect first.
     */
    private suspend fun runtimeForCommand(sentAfter: Int): TaskRuntime? {
        val running = runtime
        if (running != null && running === stopping) running.phase.first { it is Phase.Stopped }
        val target =
            when {
                sentAfter != stops -> null
                running != null && running.phase.value !is Phase.Stopped -> running
                else -> opened()
            }
        return target?.takeIf { sentAfter == stops }
    }

    /**
     * Opens the task's next run (its first one when there is no task) and shows it; a stop that
     * came meanwhile stops it, and this returns once it is [Phase.Stopped].
     */
    private suspend fun opened(): TaskRuntime? {
        val known = session
        opening = true
        current.update { it.copy(starting = true) }
        try {
            val next =
                guarded(shownProblem) {
                    if (known != null) StartedTask(known, known.resume(scope)) else launcher.start(scope)
                }
            next?.let { watch(it.session, it.runtime) }
            val cause = pendingStop
            if (next != null && cause != null) {
                stopping = next.runtime
                next.runtime.stop(cause)
                next.runtime.phase.first { it is Phase.Stopped }
            }
            return next?.runtime
        } finally {
            opening = false
            pendingStop = null
            current.update { it.copy(starting = false) }
        }
    }

    /**
     * Shows [next], the current run of [task]: subscribes to its events first, then reads the log,
     * so every record reaches the view one way or the other (seq keeps one of each).
     */
    private suspend fun watch(
        task: TaskSession,
        next: TaskRuntime,
    ) {
        watching?.cancel()
        session = task
        runtime = next
        current.update { it.copy(handed = 0) }
        val job = Job(scope.coroutineContext[Job])
        watching = job
        val shown = CoroutineScope(scope.coroutineContext + job)
        shown.launch(start = CoroutineStart.UNDISPATCHED) { next.events.collect { show(task, it) } }
        rebuild(task)
        shown.launch { next.phase.collect { phased(task, it) } }
    }

    private suspend fun phased(
        task: TaskSession,
        phase: Phase,
    ) {
        val chat = current.value.chat ?: ChatState(cards = task.cards)
        val unknown = phase is Phase.Waiting && phase.promptId != null && phase.promptId !in chat.prompts
        if (unknown) {
            rebuild(task)
        } else {
            showChat(chat.phase(phase))
        }
    }

    /** Folds [event]'s record in (cards come from the records); one after a gap in seq rebuilds the view instead. */
    private suspend fun show(
        task: TaskSession,
        event: LoopEvent,
    ) {
        if (event !is LoopEvent.Recorded) return
        if (reading > 0) heard[event.record.seq] = event.record
        val chat = current.value.chat ?: ChatState(cards = task.cards)
        if (chat.follows(event.record)) showChat(chat.recorded(event.record)) else rebuild(task)
    }

    /**
     * Rebuilds the view from [task]'s log and the records heard while it was read. A record is in
     * the file before its event is sent, so only a record appended during the read can be missing
     * from it (cut off as a torn last line).
     */
    private suspend fun rebuild(task: TaskSession) {
        reading++
        val log =
            try {
                guarded(shownProblem) { task.log() }
            } finally {
                reading--
            }
        if (log != null) {
            log.records.forEach { heard.putIfAbsent(it.seq, it) }
            val phase = runtime?.phase?.value ?: Phase.Idle
            showChat(ChatState.of(heard.values, phase, task.cards).through(log.lastSeq))
        }
        if (reading == 0) heard.clear() else log?.let { heard.headMap(it.lastSeq, true).clear() }
    }

    /**
     * Shows [chat]: the commands it took since the view before are no longer handed, and a stopped
     * run takes none of those left.
     */
    private fun showChat(chat: ChatState) {
        current.update { view ->
            val taken = (chat.commands - (view.chat?.commands ?: 0)).coerceAtLeast(0)
            val handed = if (chat.phase is Phase.Stopped) 0 else (view.handed - taken).coerceAtLeast(0)
            view.copy(chat = chat, handed = handed)
        }
    }
}

private const val TAG = "PhoneAgentTask"

/** [action]'s result, or `null` with the [problem] reported. Cancellation propagates. */
@Suppress("TooGenericExceptionCaught") // Any failure becomes a problem shown; only its class is logged.
private suspend fun <T : Any> guarded(
    problem: (TaskProblem) -> Unit,
    action: suspend () -> T,
): T? =
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: TaskUnavailableException) {
        problem(unavailable.reason)
        null
    } catch (io: IOException) {
        Log.w(TAG, "task log unusable (${io.javaClass.name})")
        problem(TaskProblem.STORAGE)
        null
    } catch (failure: Exception) {
        Log.w(TAG, "task failed (${failure.javaClass.name})")
        problem(TaskProblem.UNEXPECTED)
        null
    }
