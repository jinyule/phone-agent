package dev.phoneagent.core.session

import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason.TOOL_USE
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeps [DeriveRuleSamples] consistent with the model-view rules, so WP02 tests against
 * correct expectations. `deriveMessages` itself is tested by WP02.
 */
class DeriveRuleSamplesTest {
    private val codec = SessionCodec()

    private fun texts(parts: List<Part>): List<String> = parts.filterIsInstance<Part.Text>().map { it.text }

    @Test
    fun `every sample is a valid log whose views end at one of its records`() {
        DeriveRuleSamples.cases.forEach { case ->
            val lines = listOf(codec.encodeHeader(SessionSamples.header)) + case.records.map(codec::encode)
            val read = codec.read(lines.joinToString("") { "$it\n" }.encodeToByteArray())
            assertEquals(case.records, read.records, case.name)
            val seqs = case.records.map { it.seq }.toSet()
            assertTrue(case.views.all { it.afterSeq in seqs }, case.name)
            assertEquals(case.views.sortedBy { it.afterSeq }, case.views, case.name)
            assertTrue(case.rule.isNotBlank())
            case.views.forEach { view ->
                val json = Json.encodeToString(serializer<List<Message>>(), view.messages)
                assertEquals(view.messages, Json.decodeFromString(serializer<List<Message>>(), json))
            }
        }
        assertEquals(
            DeriveRuleSamples.cases.size,
            DeriveRuleSamples.cases
                .map { it.name }
                .toSet()
                .size,
        )
    }

    @Test
    fun `views only grow, except where reasoning is dropped`() {
        DeriveRuleSamples.cases.forEach { case ->
            case.views.zipWithNext().forEach { (earlier, later) ->
                val drop =
                    case.records
                        .filter { it.seq in (earlier.afterSeq + 1)..later.afterSeq }
                        .any { it.event is ReasoningDropped }
                val grows = later.messages.take(earlier.messages.size) == earlier.messages
                assertTrue(drop || grows, "${case.name}: ${earlier.afterSeq} -> ${later.afterSeq}")
            }
        }
    }

    @Test
    fun `an interrupted reply is visible exactly when a user message follows it before another reply`() {
        DeriveRuleSamples.cases.forEach { case ->
            val final = case.views.last()
            case.records.filter { (it.event as? AssistantMessage)?.interrupted == true }.forEach { cut ->
                val next =
                    case.records
                        .filter { it.seq > cut.seq && it.seq <= final.afterSeq }
                        .firstOrNull { it.event is UserMessage || it.event is AssistantMessage }
                val visible = next?.event is UserMessage
                val parts = (cut.event as AssistantMessage).parts
                val shown = parts.filterNot { it is Part.Reasoning }
                val prefix = Message(Role.ASSISTANT, shown)
                assertEquals(visible && shown.isNotEmpty(), prefix in final.messages, "${case.name}: seq ${cut.seq}")
                val withReasoning = Message(Role.ASSISTANT, parts)
                assertTrue(
                    shown == withReasoning.parts || withReasoning !in final.messages,
                    "${case.name}: reasoning shown",
                )
            }
        }
    }

    @Test
    fun `side requests contribute nothing to the main view`() {
        DeriveRuleSamples.cases.forEach { case ->
            val sideTexts =
                case.records.flatMap { record ->
                    when (val event = record.event) {
                        is SideRequest -> {
                            event.request.messages.flatMap { texts(it.parts) }
                        }

                        is SideResponse -> {
                            (event.outcome as? SideOutcome.Completed)
                                ?.response
                                ?.parts
                                ?.let(
                                    ::texts,
                                ).orEmpty()
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }
            val shown = case.views.flatMap { view -> view.messages.flatMap { texts(it.parts) } }
            assertEquals(emptyList<String>(), sideTexts.filter { it in shown }, case.name)
        }
        assertTrue(DeriveRuleSamples.cases.any { case -> case.records.any { it.event is SideRequest } })
    }

    @Test
    fun `a reference projector of the documented rules reproduces every view`() {
        val wrong =
            DeriveRuleSamples.cases.flatMap { case ->
                case.views.mapNotNull { view ->
                    val derived = Projector().view(case.records, view.afterSeq)
                    if (derived == view.messages) null else "${case.name} @${view.afterSeq}: $derived"
                }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the samples tell each plausible wrong projector apart`() {
        val undetected =
            Mistake.entries
                .filter { mistake ->
                    val projector = Projector(mistake)
                    DeriveRuleSamples.cases.all { case ->
                        case.views.all { projector.view(case.records, it.afterSeq) == it.messages }
                    }
                }
        assertEquals(emptyList<Mistake>(), undetected, "wrong projectors every sample agrees with")
    }

    /** One plausible mistake in implementing the model-view rules. */
    private enum class Mistake {
        /** Drops every reasoning once any drop exists. */
        DROP_ALL_ONCE_ANY_DROP,

        /** Drops reasoning of the replies before the drop record, not of those up to upToSeq. */
        DROP_BY_POSITION,

        /** Applies only the first drop. */
        FIRST_DROP_ONLY,

        /** Keeps messages left without parts. */
        KEEP_EMPTY,

        /** Keeps the reasoning of a visible interrupted prefix. */
        KEEP_PREFIX_REASONING,

        /** Keeps a prefix of nothing but reasoning instead of leaving it out. */
        KEEP_REASONING_ONLY_PREFIX,

        /** Drops a steered prefix that holds reasoning instead of stripping it. */
        DROP_STEERED_PREFIX_WITH_REASONING,

        /** Strips reasoning only from steered prefixes, not from one cut by a stop. */
        STRIP_ONLY_STEERED_PREFIX,

        /** Strips reasoning from the retry that supersedes a prefix too. */
        STRIP_RETRY_AFTER_INTERRUPTION,

        /** Removes compaction blocks with the reasoning a drop removes. */
        DROP_COMPACTION_TOO,

        /** Leaves out the tool calls of a reply that did not end with TOOL_USE (r2's "discarded"). */
        DROP_CALLS_OF_INCOMPLETE_REPLY,

        /** Leaves out SKIPPED results, as if a call that never ran had nothing to answer. */
        DROP_SKIPPED_RESULTS,

        /** Leaves out CANCELLED results of a stop. */
        DROP_CANCELLED_RESULTS,
    }

    /**
     * The model-view rules of the `SessionEvent` KDoc, written out for the samples (no compaction records);
     * with a [mistake], one plausible error in them.
     */
    private class Projector(
        private val mistake: Mistake? = null,
    ) {
        fun view(
            records: List<SessionRecord>,
            afterSeq: Long,
        ): List<Message> {
            val log = records.filter { it.seq <= afterSeq }
            val drops = log.filter { it.event is ReasoningDropped }
            return log.mapIndexedNotNull { index, record ->
                when (val event = record.event) {
                    is UserMessage -> {
                        Message(Role.USER, event.parts)
                    }

                    is AssistantMessage -> {
                        assistant(log.take(index), log.drop(index + 1), record.seq, event, drops)
                    }

                    is ToolResultRecorded -> {
                        if (leavesOut(
                                event,
                            )
                        ) {
                            null
                        } else {
                            Message(Role.TOOL, listOf(event.result.toPart(event.callId)))
                        }
                    }

                    is CompactionSummary, is CompactionPrune -> {
                        error("compaction is not covered by this projector")
                    }

                    else -> {
                        null
                    }
                }
            }
        }

        private fun makes(candidate: Mistake) = mistake == candidate

        private fun leavesOut(result: ToolResultRecorded): Boolean =
            when {
                result.result.status == ToolStatus.SKIPPED -> makes(Mistake.DROP_SKIPPED_RESULTS)
                result.result.status == ToolStatus.CANCELLED -> makes(Mistake.DROP_CANCELLED_RESULTS)
                else -> false
            }

        private fun assistant(
            earlier: List<SessionRecord>,
            later: List<SessionRecord>,
            seq: Long,
            event: AssistantMessage,
            drops: List<SessionRecord>,
        ): Message? {
            val next = later.firstOrNull { it.event is UserMessage || it.event is AssistantMessage }
            val visible = !event.interrupted || next?.event is UserMessage
            // Steered: the input that follows comes in the same turn; otherwise a stop ended the turn first.
            val steered = next != null && later.takeWhile { it !== next }.none { it.event is TurnEnded }
            if (!visible || droppedWhole(event, steered)) return null
            val byDrop = dropped(seq, drops)
            val whole = stripsPrefix(event, steered) || stripsRetry(earlier)
            val stripped = event.parts.filterNot { part -> strips(event, part, whole, byDrop) }
            val keepAll = stripped.isEmpty() && event.interrupted && makes(Mistake.KEEP_REASONING_ONLY_PREFIX)
            val parts = if (keepAll) event.parts else stripped
            return if (parts.isNotEmpty() || makes(Mistake.KEEP_EMPTY)) Message(Role.ASSISTANT, parts) else null
        }

        /** Whether [part] of [event] is left out: all reasoning when [whole], reasoning blocks when [byDrop]. */
        private fun strips(
            event: AssistantMessage,
            part: Part,
            whole: Boolean,
            byDrop: Boolean,
        ): Boolean =
            when {
                part is Part.Reasoning -> whole || (byDrop && dropsKind(part.kind))
                part is Part.ToolCall -> makes(Mistake.DROP_CALLS_OF_INCOMPLETE_REPLY) && event.stopReason != TOOL_USE
                else -> false
            }

        private fun dropsKind(kind: ReasoningKind): Boolean = kind.isReasoning || makes(Mistake.DROP_COMPACTION_TOO)

        private fun droppedWhole(
            event: AssistantMessage,
            steered: Boolean,
        ): Boolean =
            makes(Mistake.DROP_STEERED_PREFIX_WITH_REASONING) &&
                event.interrupted &&
                steered &&
                event.parts.any { it is Part.Reasoning }

        private fun stripsPrefix(
            event: AssistantMessage,
            steered: Boolean,
        ): Boolean =
            event.interrupted &&
                !makes(Mistake.KEEP_PREFIX_REASONING) &&
                (steered || !makes(Mistake.STRIP_ONLY_STEERED_PREFIX))

        private fun stripsRetry(earlier: List<SessionRecord>): Boolean {
            val previous = earlier.lastOrNull { it.event is UserMessage || it.event is AssistantMessage }?.event
            return makes(Mistake.STRIP_RETRY_AFTER_INTERRUPTION) && (previous as? AssistantMessage)?.interrupted == true
        }

        private fun dropped(
            seq: Long,
            drops: List<SessionRecord>,
        ): Boolean {
            val applied = if (makes(Mistake.FIRST_DROP_ONLY)) drops.take(1) else drops
            return when {
                makes(Mistake.DROP_ALL_ONCE_ANY_DROP) -> drops.isNotEmpty()
                makes(Mistake.DROP_BY_POSITION) -> applied.any { it.seq > seq }
                else -> applied.any { seq <= (it.event as ReasoningDropped).upToSeq }
            }
        }
    }
}
