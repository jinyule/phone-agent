package dev.phoneagent.core.agent

import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.SessionRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * The two queues of one task (D12): `NEXT_TURN` (followups, one per turn) and `NEXT_STEP`
 * (steers and injected context, all taken at the next step boundary). Every change is recorded as
 * `inbox/spliced` through [record] while the queues are locked, so the log's order of splices is
 * the queues' order and folding the log rebuilds them ([restore]).
 *
 * It also holds the in-flight model request ([register]) so that adding a steer cancels it.
 */
internal class Inbox(
    nextTurn: List<InboxEntry>,
    nextStep: List<InboxEntry>,
    private val record: suspend (InboxSpliced) -> Unit,
) {
    private val mutex = Mutex()
    private val nextTurn = ArrayDeque(nextTurn)
    private val nextStep = ArrayDeque(nextStep)
    private val inFlight = AtomicReference<Job?>(null)

    /** Appends [entry] to [queue]; a steer cancels the registered request. */
    suspend fun add(
        queue: InboxQueue,
        entry: InboxEntry,
    ): Unit =
        mutex.withLock {
            record(InboxSpliced(queue, removed = 0, added = listOf(entry)))
            queueOf(queue).addLast(entry)
            if (entry.kind == InboxEntryKind.STEER) inFlight.get()?.cancel(SteerCancellation())
        }

    /** Takes the inputs of a step: every `NEXT_STEP` entry and, at a turn start, one `NEXT_TURN` entry. */
    suspend fun take(turnStart: Boolean): List<InboxEntry> =
        mutex.withLock {
            val steps = nextStep.toList()
            val turns = if (turnStart) listOfNotNull(nextTurn.firstOrNull()) else emptyList()
            if (steps.isNotEmpty()) record(InboxSpliced(InboxQueue.NEXT_STEP, removed = steps.size))
            if (turns.isNotEmpty()) record(InboxSpliced(InboxQueue.NEXT_TURN, removed = 1))
            nextStep.clear()
            repeat(turns.size) { nextTurn.removeFirst() }
            steps + turns
        }

    /** Whether a turn should start: a followup, or a steer, is queued (injected context alone waits). */
    suspend fun hasTurnWork(): Boolean = mutex.withLock { nextTurn.isNotEmpty() || hasSteerLocked() }

    /** Whether a steer is queued. */
    suspend fun hasSteer(): Boolean = mutex.withLock { hasSteerLocked() }

    /**
     * Makes [job] the in-flight request so a steer cancels it. Returns `false`, registering
     * nothing, when a steer is already queued (the caller then does not send).
     */
    suspend fun register(job: Job): Boolean =
        mutex.withLock {
            val free = !hasSteerLocked()
            if (free) inFlight.set(job)
            free
        }

    /** Forgets [job] as the in-flight request. */
    fun unregister(job: Job) {
        inFlight.compareAndSet(job, null)
    }

    /** Empties both queues (stop). */
    suspend fun clear(): Unit =
        mutex.withLock {
            for ((queue, entries) in listOf(InboxQueue.NEXT_TURN to nextTurn, InboxQueue.NEXT_STEP to nextStep)) {
                if (entries.isNotEmpty()) record(InboxSpliced(queue, removed = entries.size))
                entries.clear()
            }
        }

    private fun hasSteerLocked(): Boolean = nextStep.any { it.kind == InboxEntryKind.STEER }

    private fun queueOf(queue: InboxQueue): ArrayDeque<InboxEntry> =
        when (queue) {
            InboxQueue.NEXT_TURN -> nextTurn
            InboxQueue.NEXT_STEP -> nextStep
        }

    companion object {
        /** The queues as the `inbox/spliced` records in [records] leave them. */
        fun restore(
            records: List<SessionRecord>,
            record: suspend (InboxSpliced) -> Unit,
        ): Inbox {
            val queues =
                mapOf(
                    InboxQueue.NEXT_TURN to mutableListOf<InboxEntry>(),
                    InboxQueue.NEXT_STEP to mutableListOf(),
                )
            for (splice in records.mapNotNull { it.event as? InboxSpliced }) {
                val queue = queues.getValue(splice.queue)
                repeat(minOf(splice.removed, queue.size)) { queue.removeAt(0) }
                queue += splice.added
            }
            return Inbox(queues.getValue(InboxQueue.NEXT_TURN), queues.getValue(InboxQueue.NEXT_STEP), record)
        }
    }
}
