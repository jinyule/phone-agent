package dev.phoneagent.core.session

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
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
            // card.pkg of every APPROVED OUTWARD card whose call started and was executed with OK.
            "outwardConfirmedPackages" to
                listOf(
                    "approval/asked.card",
                    "approval/decided.result",
                    "tool/started.callId",
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
        val wrongDerivations =
            mapOf(
                "reads no source" to Derivation(readsNothing = true),
                "counts dispatched calls, not started ones" to Derivation(fromDispatch = true),
                "counts READ calls" to Derivation(countsRead = true),
                "trusts an approval without execution" to Derivation(approvalOnly = true),
                "ignores the executed status" to Derivation(ignoresStatus = true),
                "does not join approval and execution on the call" to Derivation(anyCall = true),
            )
        val undetected =
            wrongDerivations
                .filter { (_, derivation) ->
                    PolicyContextSamples.cases.all {
                        derivation.of(it.records) ==
                            (it.operatedPackages to it.outwardConfirmedPackages)
                    }
                }.keys
        assertEquals(emptySet<String>(), undetected, "wrong derivations every sample agrees with")
    }

    /**
     * `operatedPackages` to `outwardConfirmedPackages` as the PolicyContext KDoc defines them; the
     * flags add one mistake each.
     */
    private class Derivation(
        val readsNothing: Boolean = false,
        val fromDispatch: Boolean = false,
        val countsRead: Boolean = false,
        val approvalOnly: Boolean = false,
        val ignoresStatus: Boolean = false,
        val anyCall: Boolean = false,
    ) {
        fun of(records: List<SessionRecord>): Pair<Set<String>, Set<String>> {
            if (readsNothing) return emptySet<String>() to emptySet()
            val events = records.map { it.event }
            val bySeq = records.associateBy { it.seq }
            val verdicts = events.filterIsInstance<PolicyVerdictRecorded>()
            val ranOn =
                if (fromDispatch) {
                    verdicts.map { it.callId to it }
                } else {
                    events.filterIsInstance<ToolStarted>().map {
                        it.callId to
                            (bySeq.getValue(it.verdictSeq).event as PolicyVerdictRecorded)
                    }
                }
            val operated =
                ranOn
                    .map { it.second.intent }
                    .filter { countsRead || it.kind != IntentKind.READ }
                    .mapNotNull { it.pkg }
                    .toSet()
            val started = events.filterIsInstance<ToolStarted>().map { it.callId }.toSet()
            val executedOk =
                events
                    .filterIsInstance<ToolExecuted>()
                    .filter { ignoresStatus || it.status == ToolStatus.OK }
                    .map { it.callId }
                    .toSet()
            val cards = events.filterIsInstance<ApprovalAsked>().associate { it.promptId to it.card }
            val approved =
                events
                    .filterIsInstance<ApprovalDecided>()
                    .filter { it.result == ConfirmResult.APPROVED }
                    .mapNotNull { cards[it.promptId] }
                    .filter { it.reason == ConfirmReason.OUTWARD }
            val outward =
                approved
                    .filter { card ->
                        when {
                            approvalOnly -> true
                            anyCall -> started.isNotEmpty() && executedOk.isNotEmpty()
                            else -> card.callId in started && card.callId in executedOk
                        }
                    }.mapNotNull { it.pkg }
                    .toSet()
            return operated to outward
        }
    }
}
