package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.AppCatalog
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionLogException
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * What one task's loop runs with. A data class so variants are one `copy` away.
 *
 * @property taskId the task; every tool call carries it.
 * @property files the session directory (log and blobs).
 * @property plan the request plan every attempt starts from (`request` interceptors may change any
 *   of it; the runtime then asks the plan's model to `prepare` the settings). Its model is built
 *   for this session: the adapter's `BlobSource` reads [files]' blobs, so each session gets its
 *   own `ChatModel`.
 * @property registry the tools; only visible ones can be called.
 * @property policy the safety engine, the last guard of `toolPreExecute`.
 * @property intents resolves what a call would do, for the policy.
 * @property screen the task's surface, or `null` when it has none (phone tools then report errors).
 * @property surfaces moves the task to the user for handoffs; `null` in main-display mode, where the
 *   user acts on the screen the task already uses.
 * @property apps installed apps for `phone_apps` and launching by name, or `null` when missing.
 * @property clock timestamps of log records and card expiry.
 * @property config limits, timeouts and loop texts (the security level and attendance are not
 *   configuration: they come from the log, `task/start` and `task/level-changed`).
 * @property interceptors the extensions at the fixed points.
 * @property timeSource measures tool timeouts (virtual time in tests).
 */
public data class AgentSetup(
    val taskId: TaskId,
    val files: SessionFiles,
    val plan: RequestPlan,
    val registry: ToolRegistry,
    val policy: PolicyEngine,
    val intents: IntentResolver,
    val screen: ScreenPort? = null,
    val surfaces: SurfaceManager? = null,
    val apps: AppCatalog? = null,
    val clock: Clock = Clock.System,
    val config: LoopConfig = LoopConfig(),
    val interceptors: Interceptors = Interceptors(),
    val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
)

/**
 * The [TaskRuntime] of phase1 §3.7–§3.9: a driver coroutine runs turns from the inbox, each turn
 * runs steps (see [Steps]), and every fact goes to the session log first.
 *
 * Concurrency: input methods only enqueue; an intake coroutine records them (`inbox/spliced`) in
 * order, then wakes the driver (followup, steer) or not (inject); a steer also cancels an
 * in-flight model request. Everything else runs on the driver coroutine, launched in the scope
 * given to [start] or [resume]; cancelling that scope stops the task with [StopCause.SHUTDOWN].
 *
 * Failure and stop: see [Driver]. Stopping (or cancelling the scope) cancels the driver; the
 * shutdown, in `NonCancellable` code, stops accepting input, waits for the intake to finish the
 * record in progress, then records itself whatever input was accepted and not yet recorded and
 * clears the inbox (so accepted input is recorded, then recorded as removed, never lost). Only
 * then it closes the turn, writes `task/stopped` last and closes the log, and shows
 * [Phase.Stopped]. This runtime then ignores all input; the session itself may go on in a new
 * runtime from [resume] when `TaskStopped`'s contract allows it.
 */
public class AgentLoop private constructor(
    setup: AgentSetup,
    store: SessionStore,
    records: List<SessionRecord>,
    scope: CoroutineScope,
) : TaskRuntime {
    override val id: TaskId = setup.taskId

    private val eventFlow =
        MutableSharedFlow<LoopEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<LoopEvent> = eventFlow.asSharedFlow()

    private val phaseFlow =
        MutableStateFlow(phaseAfter(records.mapNotNull { (it.event as? TurnEnded)?.reason }.lastOrNull()))
    override val phase: StateFlow<Phase> = phaseFlow.asStateFlow()

    private val log = LoopLog(setup, store) { eventFlow.tryEmit(LoopEvent.Recorded(it)) }
    private val inbox = Inbox.restore(records) { log.append(it) }
    private val loop =
        LoopContext(setup, log, inbox, phaseFlow, eventFlow, settingsOf(records).unattended)
    private val prompts = Prompts(loop)
    private val intake = Intake(inbox, { log.append(it) }) { stop(StopCause.ERROR) }
    private val driver =
        Driver(
            loop,
            prompts,
            intake,
            records.mapNotNull { (it.event as? TurnStarted)?.turn }.lastOrNull() ?: 0,
        )

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val intakeJob: Job
    private val driverJob: Job

    init {
        val loopScope = CoroutineScope(scope.coroutineContext + job)
        intakeJob = loopScope.launch { intake.run() }
        // Undispatched: the driver is inside its `try` before anything can cancel it, so a stop
        // always reaches the shutdown.
        driverJob =
            loopScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    driver.drive()
                } finally {
                    withContext(NonCancellable) { shutDown() }
                }
            }
    }

    override fun followup(input: UserInput) {
        submit(InboxQueue.NEXT_TURN, InboxEntry(InboxEntryKind.FOLLOWUP, input.parts), wake = true)
    }

    override fun steer(input: UserInput) {
        submit(InboxQueue.NEXT_STEP, InboxEntry(InboxEntryKind.STEER, input.parts), wake = true)
    }

    override fun inject(context: ContextInput) {
        submit(InboxQueue.NEXT_STEP, InboxEntry(InboxEntryKind.INJECT, context.parts, context.source), wake = false)
    }

    override fun changeLevel(level: SecurityLevel) {
        if (!driver.stopped) intake.changeLevel(level)
    }

    override fun stop(cause: StopCause) {
        if (driver.claimStop(cause)) driverJob.cancel()
    }

    override fun answer(
        promptId: PromptId,
        reply: UserReply,
    ): Boolean = prompts.answer(promptId, reply)

    private fun submit(
        queue: InboxQueue,
        entry: InboxEntry,
        wake: Boolean,
    ) {
        if (!driver.stopped) intake.submit(queue, entry, wake)
    }

    /**
     * Ends this runtime (phase1 §3.7). The driver has returned; the intake stops accepting input
     * and is joined (it stops between two records), so from then on this is the only writer. In
     * order: the accepted input not recorded yet, the inbox cleared, the interrupted turn closed
     * (prefix, `prompt/withdrawn`, CANCELLED results, `step/end`, `turn/end(STOPPED)`), then
     * `task/stopped(cause)` and the log closed, so nothing can follow it. Shows [Phase.Stopped] even
     * when that fails.
     */
    private suspend fun shutDown() {
        val cause = driver.finalCause()
        try {
            intake.close()
            intakeJob.cancelAndJoin()
            intake.drain()
            clearInbox()
            driver.finish()
            recordStop(cause)
            log.close()
        } finally {
            phaseFlow.value = Phase.Stopped(cause)
            job.complete()
        }
    }

    private suspend fun recordStop(cause: StopCause) {
        try {
            log.append(TaskStopped(cause))
            log.flush()
        } catch (ignored: SessionLogException) {
            // The log refuses writes since an earlier failure: the stop stays unrecorded and the
            // next open repairs the log like a crashed one.
        }
    }

    private suspend fun clearInbox() {
        try {
            inbox.clear()
        } catch (ignored: SessionLogException) {
            // The log refuses writes: the queued input cannot be recorded as cleared, and the
            // next open sees the log as it is.
        }
    }

    /** Starting and resuming tasks. */
    public companion object {
        private const val EVENT_BUFFER = 256
        private const val RESUMED_DETAIL = "the app stopped before this turn finished"

        /**
         * Starts task [AgentSetup.taskId] with a new session [sessionId] in [AgentSetup.files], running
         * in [scope], with the security level and attendance in [settings] (record 1, `task/start`;
         * from then on the log is their only source). It is idle until the first followup. Throws
         * what `SessionStore.create` throws.
         */
        public suspend fun start(
            setup: AgentSetup,
            sessionId: SessionId,
            settings: TaskStarted,
            scope: CoroutineScope,
        ): AgentLoop {
            val store = SessionStore.create(setup.files, sessionId, setup.taskId, settings, setup.clock)
            return AgentLoop(setup, store, store.records(), scope)
        }

        /**
         * Reopens the session in [AgentSetup.files] after the process ended, possibly mid-turn, and
         * repairs it (D14): calls without a result get NOT_STARTED or OUTCOME_UNKNOWN (observe first),
         * an open prompt is withdrawn, an open step and turn end `FAILED`, then `session/resumed`.
         * Nothing continues on its own: queued input stays queued until the next followup or steer.
         * Throws what `SessionStore.open` throws, and [IllegalArgumentException] when the log
         * belongs to another task. When it throws or is cancelled after opening the log, it closes
         * the log again.
         */
        public suspend fun resume(
            setup: AgentSetup,
            scope: CoroutineScope,
        ): AgentLoop {
            val opened = SessionStore.open(setup.files, setup.clock)
            val handover = StoreHandover(opened.store)
            try {
                return handover.to(adopt(setup, opened, scope))
            } finally {
                handover.release()
            }
        }

        /** Repairs the reopened log of [opened] and hands its store to a new loop. */
        private suspend fun adopt(
            setup: AgentSetup,
            opened: SessionStore.Opened,
            scope: CoroutineScope,
        ): AgentLoop {
            val store = opened.store
            val owner = store.header.taskId
            require(
                owner == setup.taskId,
            ) { "session ${store.header.sessionId} belongs to task $owner, not ${setup.taskId}" }
            val repairs = repairOf(store.records(), RepairCause.FAILED, RESUMED_DETAIL, setup.config.texts)
            for (repair in repairs) store.append(repair.event, sources = repair.sources)
            store.append(SessionResumed(opened.log.truncatedTail?.bytes ?: 0))
            store.flush()
            return AgentLoop(setup, store, store.records(), scope)
        }
    }
}

/** Owns a store until it is handed [to] a loop; [release] closes it if it never was. */
private class StoreHandover(
    private val store: SessionStore,
) {
    private var handedOver = false

    fun to(loop: AgentLoop): AgentLoop = loop.also { handedOver = true }

    /** Closes the store, even when cancelled, unless a loop owns it now. */
    suspend fun release() {
        if (!handedOver) withContext(NonCancellable) { store.close() }
    }
}
