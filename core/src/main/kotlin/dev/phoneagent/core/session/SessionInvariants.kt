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
 *   A result needs no declaration: invalid and skipped calls never get a `tool/call`.
 *
 * Feed every accepted record to [accept] in seq order; one instance per log.
 */
internal class SessionInvariants {
    private val declared = HashSet<ToolCallId>()
    private val answered = HashSet<ToolCallId>()

    /** Why [record] may not follow the records accepted so far, or `null` when it may. */
    fun violation(record: SessionRecord): String? = surfaceOpViolation(record) ?: callViolation(record.event)

    /** Takes [record] into account for later checks. */
    fun accept(record: SessionRecord) {
        when (val event = record.event) {
            is ToolCallRecorded -> declared += event.call.id
            is ToolResultRecorded -> answered += event.callId
            else -> Unit
        }
    }

    private fun surfaceOpViolation(record: SessionRecord): String? {
        val op = record.surfaceOp
        return when (record.event) {
            is CompactionSummary -> {
                if (op is SurfaceOp.Replace) null else "compaction/summary needs a Replace"
            }

            is CompactionPrune -> {
                val single = op is SurfaceOp.Replace && op.startSeq == op.endSeq
                if (single) null else "compaction/prune needs Replace(n, n) over a single record"
            }

            else -> {
                if (op == SurfaceOp.Append) null else "only compaction records replace earlier ones"
            }
        }
    }

    private fun callViolation(event: SessionEvent): String? =
        when (event) {
            is ToolCallRecorded -> if (event.call.id in declared) "call ${event.call.id} is already declared" else null
            is ToolResultRecorded -> if (event.callId in answered) "call ${event.callId} already has a result" else null
            else -> null
        }

    companion object {
        /** Invariants that have accepted [records], for continuing a log that was read. */
        fun after(records: List<SessionRecord>): SessionInvariants =
            SessionInvariants().apply { records.forEach(::accept) }
    }
}
