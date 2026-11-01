package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId

/**
 * Rules that span several records, checked where records are written ([SessionStore.append]:
 * [IllegalArgumentException], nothing written) and where a log is read ([SessionCodec.read]:
 * [SessionCorruptedException] naming the line):
 *
 * - `compaction/summary` is written with a [SurfaceOp.Replace], `compaction/prune` with a
 *   single-record `Replace(n, n)`, every other type with [SurfaceOp.Append];
 * - a call id is declared by at most one `tool/call` and answered by at most one `tool/result`.
 *   A result needs no declaration: invalid and skipped calls never get a `tool/call`;
 * - record 1 is `task/start`, and no other record is;
 * - a `side/response` has exactly one source: an earlier `side/request` that has no response yet;
 * - `context/reasoning-dropped` points to an earlier record.
 *
 * Feed every accepted record to [accept] in seq order; one instance per log.
 */
internal class SessionInvariants {
    private val declared = HashSet<ToolCallId>()
    private val answered = HashSet<ToolCallId>()
    private val openSideRequests = HashSet<Long>()

    /** Why [record] may not follow the records accepted so far, or `null` when it may. */
    fun violation(record: SessionRecord): String? =
        surfaceOpViolation(record)
            ?: startViolation(record)
            ?: callViolation(record.event)
            ?: linkViolation(record)

    /** Takes [record] into account for later checks. */
    fun accept(record: SessionRecord) {
        when (val event = record.event) {
            is ToolCallRecorded -> declared += event.call.id
            is ToolResultRecorded -> answered += event.callId
            is SideRequest -> openSideRequests += record.seq
            is SideResponse -> openSideRequests -= record.sources.toSet()
            else -> Unit
        }
    }

    private fun startViolation(record: SessionRecord): String? {
        val isStart = record.event is TaskStarted
        return when {
            record.seq == 1L && !isStart -> "a log starts with task/start"
            record.seq != 1L && isStart -> "task/start is only record 1"
            else -> null
        }
    }

    private fun linkViolation(record: SessionRecord): String? {
        val event = record.event
        return when {
            event is SideResponse && record.sources.singleOrNull() !in openSideRequests -> {
                "side/response answers one earlier side/request without a response"
            }

            event is ReasoningDropped && event.upToSeq >= record.seq -> {
                "upToSeq must point to an earlier record"
            }

            else -> {
                null
            }
        }
    }

    private fun surfaceOpViolation(record: SessionRecord): String? {
        val op = record.surfaceOp
        val event = record.event
        return when {
            event is CompactionSummary -> {
                if (op is SurfaceOp.Replace) null else "compaction/summary needs a Replace"
            }

            event is CompactionPrune -> {
                val single = op is SurfaceOp.Replace && op.startSeq == op.endSeq
                if (single) null else "compaction/prune needs Replace(n, n) over a single record"
            }

            op != SurfaceOp.Append -> {
                "only compaction records replace earlier ones"
            }

            else -> {
                null
            }
        }
    }

    private fun callViolation(event: SessionEvent): String? =
        when {
            event is ToolCallRecorded && event.call.id in declared -> "call ${event.call.id} is already declared"
            event is ToolResultRecorded && event.callId in answered -> "call ${event.callId} already has a result"
            else -> null
        }

    companion object {
        /** Invariants that have accepted [records], for continuing a log that was read. */
        fun after(records: List<SessionRecord>): SessionInvariants =
            SessionInvariants().apply { records.forEach(::accept) }
    }
}
