package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.tool.ToolStatus

/**
 * Rules that span several records, checked where records are written ([SessionStore.append]:
 * [IllegalArgumentException], nothing written) and where a log is read ([SessionCodec.read]:
 * [SessionCorruptedException] naming the line). Both ends use this one implementation, and an
 * ignorable record of unknown type is held to the envelope rules only, so a log the store wrote
 * reads back, and a store reopened on it continues from the same state:
 *
 * - record 1 is `task/start`, and no other record is (an unknown record is not);
 * - `compaction/summary` is written with a [SurfaceOp.Replace], `compaction/prune` with a
 *   single-record `Replace(n, n)`, every other type with [SurfaceOp.Append];
 * - a call id is declared by at most one `tool/call` and answered by at most one `tool/result`.
 *   A result needs no declaration: a call that was never dispatched (invalid arguments, skipped,
 *   cut off at the output cap, answered by a stop or a repair before dispatch) has no `tool/call`;
 *   a dispatched one has, whether it started or not. A call that has a result is never declared
 *   afterwards;
 * - `tool/started` follows the call's `tool/call`, and its `verdictSeq` is the call's latest
 *   `policy/verdict`, an Allow (READ calls included); `tool/executed` follows the `tool/started`.
 *   Each appears at most once per call, and never after the call's `tool/result`;
 * - a result for a call that never got one of its own follows the persisted start: a call with a
 *   `tool/started` is never NOT_STARTED, one without is never OUTCOME_UNKNOWN;
 * - how a run ends and the next one starts ([RunRules]): the calls that need a result have one
 *   before `step/end`, `turn/end`, `task/stopped` and `session/resumed`; `turn/end(STOPPED)` is
 *   followed by `task/stopped`; a SHUTDOWN or ERROR stop by `session/resumed`; after any stop or
 *   resume nothing runs before the user's own input is recorded in a turn;
 * - a `side/response` has exactly one source: an earlier `side/request` that has no response yet;
 * - `context/reasoning-dropped` points to an earlier record.
 *
 * Feed every accepted record to [accept] in seq order; one instance per log.
 */
internal class SessionInvariants {
    private val declared = HashSet<ToolCallId>()
    private val answered = HashSet<ToolCallId>()
    private val started = HashSet<ToolCallId>()
    private val executed = HashSet<ToolCallId>()
    private val openSideRequests = HashSet<Long>()
    private val runs = RunRules()

    /** Seq of each call's latest `policy/verdict`, for calls whose latest verdict is an Allow. */
    private val allowedOn = HashMap<ToolCallId, Long>()

    /** Why [record] may not follow the records accepted so far, or `null` when it may. */
    fun violation(record: SessionRecord): String? =
        startViolation(record.seq, record.event is TaskStarted)
            ?: surfaceOpViolation(record)
            ?: callViolation(record.event)
            ?: executionViolation(record.event)
            ?: linkViolation(record)
            ?: runs.violation(record.event)

    /**
     * Why an ignorable record of unknown type may not come at [seq], or `null` when it may. Such a
     * record changes no state: it is not [accept]ed.
     */
    fun skippedViolation(seq: Long): String? = startViolation(seq, isStart = false)

    /** Takes [record] into account for later checks. */
    fun accept(record: SessionRecord) {
        when (val event = record.event) {
            is ToolCallRecorded -> {
                declared += event.call.id
            }

            is ToolResultRecorded -> {
                answered += event.callId
            }

            is PolicyVerdictRecorded -> {
                if (event.verdict == Verdict.Allow) allowedOn[event.callId] = record.seq else allowedOn -= event.callId
            }

            is ToolStarted -> {
                started += event.callId
            }

            is ToolExecuted -> {
                executed += event.callId
            }

            is SideRequest -> {
                openSideRequests += record.seq
            }

            is SideResponse -> {
                openSideRequests -= record.sources.toSet()
            }

            else -> {}
        }
        runs.accept(record.event)
    }

    private fun executionViolation(event: SessionEvent): String? =
        when {
            event is ToolStarted -> startedViolation(event)
            event is ToolExecuted -> executedViolation(event)
            else -> null
        }

    private fun startedViolation(event: ToolStarted): String? {
        val id = event.callId
        return when {
            id !in declared -> "tool/started for $id needs its tool/call first"
            id in answered -> "call $id already has a result"
            id in started -> "call $id already started"
            allowedOn[id] != event.verdictSeq -> "call $id must start on its latest verdict, an Allow"
            else -> null
        }
    }

    private fun executedViolation(event: ToolExecuted): String? {
        val id = event.callId
        return when {
            id !in started -> "tool/executed for $id needs its tool/started first"
            id in answered -> "call $id already has a result"
            id in executed -> "call $id already executed"
            else -> null
        }
    }

    private fun startViolation(
        seq: Long,
        isStart: Boolean,
    ): String? =
        when {
            seq == 1L && !isStart -> "a log starts with task/start"
            seq != 1L && isStart -> "task/start is only record 1"
            else -> null
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
            event is ToolCallRecorded && event.call.id in answered -> "call ${event.call.id} already has a result"
            event is ToolResultRecorded -> resultViolation(event)
            else -> null
        }

    private fun resultViolation(event: ToolResultRecorded): String? {
        val id = event.callId
        val ran = id in started
        return when {
            id in answered -> "call $id already has a result"
            event.result.status == ToolStatus.NOT_STARTED && ran -> "call $id started: its result is OUTCOME_UNKNOWN"
            event.result.status == ToolStatus.OUTCOME_UNKNOWN && !ran -> "call $id never started: not OUTCOME_UNKNOWN"
            else -> null
        }
    }

    companion object {
        /** Invariants that have accepted [records], for continuing a log that was read. */
        fun after(records: List<SessionRecord>): SessionInvariants =
            SessionInvariants().apply { records.forEach(::accept) }
    }
}
