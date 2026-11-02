package dev.phoneagent.core.agent

import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionLogException
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The driver coroutine's work: wait for a followup or steer, then run turns while the inbox has
 * turn work (phase1 §3.7). A waking input is needed first, also after a resume, so queued input
 * never continues on its own (D14).
 *
 * Endings: a normal end records `turn/end` and settles the phase ([phaseAfter]) unless more turn
 * work is queued; an interceptor (or dispatch) failure repairs the turn as `FAILED` and the task
 * goes [Phase.Idle]; a session log failure stops the task with [StopCause.ERROR]; a stop cancels
 * the driver and [finish] repairs the turn as `STOPPED`. Repairs are derived from the log, so
 * running one again after a partial repair only adds what is still missing.
 */
internal class Driver(
    private val loop: LoopContext,
    prompts: Prompts,
    private val intake: Intake,
    private var turn: Int,
) {
    private val steps = Steps(loop, prompts)
    private val endings = TurnEndings(loop)
    private val stopLock = Any()

    @Volatile
    private var stopCause: StopCause? = null
    private var current: TurnState? = null

    /** Whether the task was stopped (input is then ignored). */
    val stopped: Boolean get() = stopCause != null

    /** Records [cause] as the reason to stop; `false` when the task was already stopping. */
    fun claimStop(cause: StopCause): Boolean =
        synchronized(stopLock) {
            val first = stopCause == null
            if (first) stopCause = cause
            first
        }

    /** Runs until stopped (by cancellation or a log failure); then call [finish]. */
    suspend fun drive() {
        while (!stopped) {
            intake.awaitWake()
            while (!stopped && loop.inbox.hasTurnWork()) runTurn()
        }
    }

    /** Why the task stopped: the claimed cause, else [StopCause.SHUTDOWN] (the scope was cancelled). */
    fun finalCause(): StopCause = synchronized(stopLock) { stopCause ?: StopCause.SHUTDOWN.also { stopCause = it } }

    /** Closes the turn a stop interrupted, if any. */
    suspend fun finish() {
        current?.let { endings.afterStop(it) }
    }

    @Suppress("TooGenericExceptionCaught") // An interceptor failure ends this turn only, never the task (phase1 §3.7).
    private suspend fun runTurn() {
        turn += 1
        val state = TurnState(turn)
        current = state
        try {
            playTurn(state)
        } catch (logFailure: SessionLogException) {
            stopFor(logFailure, state)
        } catch (failure: Exception) {
            // A stop propagates and [finish] records it; any other failure ends this turn only.
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            fail(state, failure)
        }
        current = null
    }

    private suspend fun playTurn(state: TurnState) {
        loop.log.append(TurnStarted(state.turn))
        val end = steps.run(state)
        loop.log.append(TurnEnded(state.turn, end.reason, end.detail))
        settle(phaseAfter(end.reason))
    }

    private suspend fun fail(
        state: TurnState,
        failure: Exception,
    ) {
        try {
            endings.afterFailure(state, failure)
        } catch (logFailure: SessionLogException) {
            return stopFor(logFailure, state)
        }
        settle(Phase.Idle)
    }

    private suspend fun stopFor(
        logFailure: SessionLogException,
        state: TurnState,
    ) {
        claimStop(StopCause.ERROR)
        withContext(NonCancellable) { endings.afterLogFailure(state, logFailure) }
    }

    /** Shows [phase] unless the next turn is already queued (it shows its own). */
    private suspend fun settle(phase: Phase) {
        if (!loop.inbox.hasTurnWork()) loop.phase.value = phase
    }
}

/** How a turn that did not end normally is closed in the log (see [repairOf]). Each repair is flushed. */
internal class TurnEndings(
    private val loop: LoopContext,
) {
    /** The task was stopped during [state]; a failing log is given up (the next open repairs it). */
    suspend fun afterStop(state: TurnState) {
        try {
            repair(state, RepairCause.STOPPED, detail = null)
        } catch (ignored: SessionLogException) {
            // Stopping anyway; whatever could not be written is repaired when the log is reopened.
        }
    }

    /**
     * An interceptor or the dispatch failed with [failure]. The detail names its class only:
     * message, cause and `toString` may quote the screen, input or credentials. Throws when the
     * log fails meanwhile.
     */
    suspend fun afterFailure(
        state: TurnState,
        failure: Exception,
    ) {
        repair(state, RepairCause.FAILED, "failed with ${failure.javaClass.name}")
    }

    /** The log failed with [failure]: record the end on a reopened log when storage allows it. */
    suspend fun afterLogFailure(
        state: TurnState,
        failure: SessionLogException,
    ) {
        if (!loop.log.reopen()) return
        try {
            repair(state, RepairCause.FAILED, "session log failure: ${failure.message}")
        } catch (ignored: SessionLogException) {
            // Storage keeps failing; the next open repairs what is missing.
        }
    }

    /** Records the prefix of an attempt cut off by the end, then what [repairOf] says is missing. */
    private suspend fun repair(
        state: TurnState,
        cause: RepairCause,
        detail: String?,
    ) {
        val prefix = state.streaming?.interrupted()
        state.streaming = null
        prefix?.let { loop.log.append(it) }
        for (repair in repairOf(loop.log.records(), cause, detail, loop.texts)) {
            loop.log.append(repair.event, sources = repair.sources)
        }
        loop.log.flush()
    }
}

/**
 * Records the input methods' entries and level changes in arrival order on its own coroutine, so
 * that [TaskRuntime.followup] and friends never suspend. A log failure stops the task
 * ([onLogFailure]).
 *
 * A command is accepted when it is queued before [close]. Each is recorded whole, even when the
 * intake is cancelled meanwhile, and only then removed: so after the intake's coroutine ended,
 * [drain] records exactly the accepted commands it had not recorded yet. The shutdown is that
 * single finisher (see [AgentLoop]).
 */
internal class Intake(
    private val inbox: Inbox,
    private val record: suspend (SessionEvent) -> Unit,
    private val onLogFailure: () -> Unit,
) {
    private val lock = Any()
    private val accepted = ArrayDeque<Command>()
    private var closed = false
    private val arrivals = Channel<Unit>(Channel.CONFLATED)
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    /** Queues [entry] for [queue]; ignored once [close]d. */
    fun submit(
        queue: InboxQueue,
        entry: InboxEntry,
        wake: Boolean,
    ) {
        accept(Command.Add(queue, entry, wake))
    }

    /** Queues a change of the task's security level; ignored once [close]d. */
    fun changeLevel(level: SecurityLevel) {
        accept(Command.Level(level))
    }

    /** Records accepted commands until cancelled, waking the driver for followups and steers. */
    suspend fun run() {
        while (true) {
            arrivals.receive()
            if (!drain()) return onLogFailure()
        }
    }

    /**
     * Records the accepted commands not recorded yet, in order; `false` when the log refused one
     * (it stays accepted, unrecorded). Only one caller at a time: the intake's coroutine, then,
     * once it ended, the shutdown.
     */
    suspend fun drain(): Boolean {
        var next = recordNext()
        while (next == Next.RECORDED) next = recordNext()
        return next == Next.EMPTY
    }

    /** Stops accepting commands; those accepted before stay to be recorded. */
    fun close() {
        synchronized(lock) { closed = true }
    }

    /** Suspends until a followup or steer was recorded since the last call. */
    suspend fun awaitWake() {
        wakes.receive()
    }

    private fun accept(command: Command) {
        val open = synchronized(lock) { !closed && accepted.add(command) }
        if (open) arrivals.trySend(Unit)
    }

    /** Records the oldest accepted command, then removes it; neither step is cut by a cancellation. */
    private suspend fun recordNext(): Next =
        withContext(NonCancellable) {
            val command = synchronized(lock) { accepted.firstOrNull() }
            when {
                command == null -> {
                    Next.EMPTY
                }

                !recorded(command) -> {
                    Next.FAILED
                }

                else -> {
                    synchronized(lock) { accepted.removeFirst() }
                    Next.RECORDED
                }
            }
        }

    private suspend fun recorded(command: Command): Boolean =
        try {
            handle(command)
            true
        } catch (ignored: SessionLogException) {
            // The store refuses writes from now on, so the task cannot record anything.
            false
        }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Add -> {
                inbox.add(command.queue, command.entry)
                if (command.wake) wakes.trySend(Unit)
            }

            is Command.Level -> {
                record(TaskLevelChanged(command.level))
            }
        }
    }

    private enum class Next { RECORDED, EMPTY, FAILED }

    private sealed interface Command {
        class Add(
            val queue: InboxQueue,
            val entry: InboxEntry,
            val wake: Boolean,
        ) : Command

        class Level(
            val level: SecurityLevel,
        ) : Command
    }
}
