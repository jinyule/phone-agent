package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * B3: every input of PolicyEngine is derivable from the session log (phase1 §3.6, addendum §8).
 * Each `PolicyContext` property names the log fields it is computed from; a new property without
 * a source, or a source that no longer exists, fails here. The two package sets come from
 * execution facts, and [PolicyContextSamples] shows on small logs what they hold.
 */
class PolicyContextSourcesTest {
    /** `header.<field>` or `<event type>.<field>`. */
    private val sources: Map<String, List<String>> =
        mapOf(
            "taskId" to listOf("header.taskId"),
            "level" to listOf("task/start.level", "task/level-changed.level"),
            "unattended" to listOf("task/start.unattended"),
            // The snapshot the call was planned on; for the re-check after approval, the fresh snapshot S'
            // (addendum §14) is the screen/snapshot recorded after approval/decided.
            "screen" to listOf("screen/snapshot.snapshot", "screen/snapshot.callId"),
            // intent.pkg of every started non-READ call, from the verdict it started on.
            "operatedPackages" to listOf("tool/started.callId", "tool/started.verdictSeq", "policy/verdict.intent"),
            // card.pkg of the approval a call executed with OK ran on: the card of the call's last Confirm
            // before the Allow at verdictSeq, APPROVED, OUTWARD, and naming the package of that Allow's intent.
            "outwardConfirmedPackages" to
                listOf(
                    "approval/asked.card",
                    "approval/asked.promptId",
                    "approval/decided.result",
                    "policy/verdict.callId",
                    "policy/verdict.verdict",
                    "policy/verdict.intent",
                    "tool/started.callId",
                    "tool/started.verdictSeq",
                    "tool/executed.callId",
                    "tool/executed.status",
                ),
            // The card just approved for this call, during the re-check.
            "approved" to listOf("approval/asked.card", "approval/asked.promptId", "approval/decided.result"),
        )

    private val properties =
        PolicyContext::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    @Test
    fun `every policy context property has a source in the log`() {
        assertEquals(properties, sources.keys, "PolicyContext properties without a log source, or stale rows")
        assertEquals(emptyList<String>(), sources.filterValues { it.isEmpty() }.keys.toList(), "rows without sources")
    }

    @Test
    fun `every source names an existing field of the header or an event`() {
        val events = serializer<SessionEvent>().descriptor.getElementDescriptor(1)
        val missing =
            sources.values.flatten().distinct().filterNot { source ->
                val type = source.substringBeforeLast('.')
                val field = source.substringAfterLast('.')
                val descriptor: SerialDescriptor? =
                    if (type == "header") {
                        serializer<SessionHeader>().descriptor
                    } else {
                        events.elementNames
                            .indexOf(type)
                            .takeIf { it >= 0 }
                            ?.let(events::getElementDescriptor)
                    }
                descriptor != null && field in descriptor.elementNames
            }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the task settings and execution facts are not ignorable, so no reader can skip them`() {
        listOf(
            "task/start",
            "task/level-changed",
            "task/stopped",
            "tool/started",
            "tool/executed",
        ).forEach { assertEquals(false, SessionEventTypes.isIgnorable(it), it) }
    }

    @Test
    fun `every sample is a valid log`() {
        val codec = SessionCodec()
        PolicyContextSamples.cases.forEach { case ->
            val lines = listOf(codec.encodeHeader(SessionSamples.header)) + case.records.map(codec::encode)
            assertEquals(
                case.records,
                codec.read(lines.joinToString("") { "$it\n" }.encodeToByteArray()).records,
                case.name,
            )
        }
        assertEquals(
            PolicyContextSamples.cases.size,
            PolicyContextSamples.cases
                .map { it.name }
                .toSet()
                .size,
        )
    }

    @Test
    fun `the documented derivation gives the expected packages for every sample`() {
        val wrong =
            PolicyContextSamples.cases.mapNotNull { case ->
                val derived = Derivation().of(case.records)
                val expected = case.operatedPackages to case.outwardConfirmedPackages
                if (derived == expected) null else "${case.name}: $derived"
            }
        assertEquals(emptyList<String>(), wrong)
        assertTrue(PolicyContextSamples.cases.any { it.operatedPackages.isNotEmpty() })
        assertTrue(PolicyContextSamples.cases.any { it.outwardConfirmedPackages.isNotEmpty() })
    }

    @Test
    fun `the samples tell each plausible wrong derivation apart`() {
        val undetected =
            Mistake.entries.filter { mistake ->
                PolicyContextSamples.cases.all {
                    Derivation(mistake).of(it.records) == (it.operatedPackages to it.outwardConfirmedPackages)
                }
            }
        assertEquals(emptyList<Mistake>(), undetected, "wrong derivations every sample agrees with")
    }

    /** One plausible mistake in deriving the two package sets. */
    private enum class Mistake {
        /** Reads no source at all. */
        READS_NOTHING,

        /** Counts calls that got a verdict, not calls that started. */
        FROM_DISPATCH,

        /** Counts READ calls as operated. */
        COUNTS_READ,

        /** Grants for an approval without the call's execution. */
        APPROVAL_ONLY,

        /** Grants whatever the executed status. */
        IGNORES_STATUS,

        /** Grants when any call executed, not the approved one. */
        ANY_CALL,

        /** Grants for an approved card of any reason. */
        IGNORES_REASON,

        /** Grants for every approved card of the call, superseded ones included. */
        UNIONS_ALL_APPROVALS,

        /** Grants the card's package even when the execution's intent names another. */
        IGNORES_INTENT_PACKAGE,

        /** Grants for any approved OUTWARD card of the call that names the package, superseded ones included. */
        ANY_MATCHING_APPROVAL,

        /** Grants nothing for a call that was confirmed more than once. */
        REVOKED_BY_RECONFIRM,
    }

    /**
     * `operatedPackages` to `outwardConfirmedPackages` as the PolicyContext KDoc defines them; with a
     * [mistake], one plausible error in them.
     */
    private class Derivation(
        private val mistake: Mistake? = null,
    ) {
        private fun makes(candidate: Mistake) = mistake == candidate

        fun of(records: List<SessionRecord>): Pair<Set<String>, Set<String>> {
            if (makes(Mistake.READS_NOTHING)) return emptySet<String>() to emptySet()
            val log = Log(records)
            return operated(log) to outward(log)
        }

        private fun operated(log: Log): Set<String> {
            val intents =
                if (makes(Mistake.FROM_DISPATCH)) {
                    log.verdicts.map { it.event.intent }
                } else {
                    log.starts.map { log.verdictAt(it.verdictSeq).intent }
                }
            return intents
                .filter { makes(Mistake.COUNTS_READ) || it.kind != IntentKind.READ }
                .mapNotNull { it.pkg }
                .toSet()
        }

        private fun outward(log: Log): Set<String> {
            val granting =
                when {
                    makes(Mistake.APPROVAL_ONLY) -> {
                        log.approvals.map { it.second }
                    }

                    makes(Mistake.ANY_CALL) -> {
                        if (log.executedOk().isEmpty()) emptyList() else log.approvals.map { it.second }
                    }

                    makes(Mistake.UNIONS_ALL_APPROVALS) -> {
                        log.approvals.filter { (_, card) -> card.callId in log.executedOk() }.map { it.second }
                    }

                    else -> {
                        log.starts.filter { it.callId in log.executedOk() }.mapNotNull { log.approvalRunOn(it) }
                    }
                }
            return granting
                .filter { makes(Mistake.IGNORES_REASON) || it.reason == ConfirmReason.OUTWARD }
                .mapNotNull { it.pkg }
                .toSet()
        }

        /**
         * The card of the call's last Confirm before the Allow it started on, when that card was
         * approved and names its package.
         */
        private fun Log.approvalRunOn(start: ToolStarted): ConfirmCard? {
            val confirms =
                verdicts
                    .filter { it.seq < start.verdictSeq && it.event.callId == start.callId }
                    .mapNotNull { (it.event.verdict as? Verdict.Confirm)?.card }
            val approved =
                confirms.filter { card ->
                    approvals.any { (seq, it) -> seq < start.verdictSeq && it == card }
                }
            val pkg = verdictAt(start.verdictSeq).intent.pkg
            val basis =
                when {
                    makes(Mistake.ANY_MATCHING_APPROVAL) -> approved.filter { it.reason == ConfirmReason.OUTWARD }
                    makes(Mistake.REVOKED_BY_RECONFIRM) && confirms.size > 1 -> emptyList()
                    else -> listOfNotNull(confirms.lastOrNull()?.takeIf { it in approved })
                }
            return basis.firstOrNull { makes(Mistake.IGNORES_INTENT_PACKAGE) || it.pkg == pkg }
        }

        /** The records the derivation reads, indexed. */
        private inner class Log(
            records: List<SessionRecord>,
        ) {
            private val bySeq = records.associateBy { it.seq }
            private val events = records.map { it.event }

            /** Every verdict with its seq. */
            val verdicts: List<Indexed<PolicyVerdictRecorded>> =
                records.mapNotNull { record ->
                    (record.event as? PolicyVerdictRecorded)?.let { Indexed(record.seq, it) }
                }

            val starts: List<ToolStarted> = events.filterIsInstance<ToolStarted>()

            /** Seq of each APPROVED decision with the card it approved. */
            val approvals: List<Pair<Long, ConfirmCard>> =
                records.mapNotNull { record ->
                    val decided = record.event as? ApprovalDecided
                    val card =
                        events
                            .filterIsInstance<ApprovalAsked>()
                            .firstOrNull { it.promptId == decided?.promptId }
                            ?.card
                    if (decided?.result == ConfirmResult.APPROVED && card != null) record.seq to card else null
                }

            fun verdictAt(seq: Long): PolicyVerdictRecorded = bySeq.getValue(seq).event as PolicyVerdictRecorded

            fun executedOk(): Set<ToolCallId> =
                events
                    .filterIsInstance<ToolExecuted>()
                    .filter { makes(Mistake.IGNORES_STATUS) || it.status == ToolStatus.OK }
                    .map { it.callId }
                    .toSet()
        }

        /** An event with its seq. */
        private data class Indexed<E>(
            val seq: Long,
            val event: E,
        )
    }
}
