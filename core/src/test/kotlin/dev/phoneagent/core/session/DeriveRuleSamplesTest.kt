package dev.phoneagent.core.session

import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.Role
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
        val wrongProjectors =
            mapOf(
                "drops every reasoning once any drop exists" to Projector(dropAllOnceAnyDrop = true),
                "drops reasoning of replies before the drop record, not up to upToSeq" to
                    Projector(dropByPosition = true),
                "applies only the first drop" to Projector(firstDropOnly = true),
                "keeps messages left without parts" to Projector(keepEmpty = true),
                "keeps reasoning of a visible interrupted prefix" to Projector(keepPrefixReasoning = true),
            )
        val undetected =
            wrongProjectors
                .filter { (_, projector) ->
                    DeriveRuleSamples.cases.all { case ->
                        case.views.all { projector.view(case.records, it.afterSeq) == it.messages }
                    }
                }.keys
        assertEquals(emptySet<String>(), undetected, "wrong projectors every sample agrees with")
    }

    /**
     * The model-view rules of the `SessionEvent` KDoc, written out for the samples (no compaction).
     * The flags turn on one plausible mistake each.
     */
    private class Projector(
        val dropAllOnceAnyDrop: Boolean = false,
        val dropByPosition: Boolean = false,
        val firstDropOnly: Boolean = false,
        val keepEmpty: Boolean = false,
        val keepPrefixReasoning: Boolean = false,
    ) {
        fun view(
            records: List<SessionRecord>,
            afterSeq: Long,
        ): List<Message> {
            val log = records.filter { it.seq <= afterSeq }
            val drops = log.filter { it.event is ReasoningDropped }
            return log.mapIndexedNotNull { index, record ->
                when (val event = record.event) {
                    is UserMessage -> Message(Role.USER, event.parts)
                    is AssistantMessage -> assistant(log.drop(index + 1), record.seq, event, drops)
                    is ToolResultRecorded -> Message(Role.TOOL, listOf(event.result.toPart(event.callId)))
                    is CompactionSummary, is CompactionPrune -> error("compaction is not covered by this projector")
                    else -> null
                }
            }
        }

        private fun assistant(
            later: List<SessionRecord>,
            seq: Long,
            event: AssistantMessage,
            drops: List<SessionRecord>,
        ): Message? {
            val next = later.firstOrNull { it.event is UserMessage || it.event is AssistantMessage }
            val visible = !event.interrupted || next?.event is UserMessage
            val dropped = dropped(seq, drops)
            val stripPrefix = event.interrupted && !keepPrefixReasoning
            val parts = event.parts.filterNot { it is Part.Reasoning && (dropped || stripPrefix) }
            return if (visible && (parts.isNotEmpty() || keepEmpty)) Message(Role.ASSISTANT, parts) else null
        }

        private fun dropped(
            seq: Long,
            drops: List<SessionRecord>,
        ): Boolean {
            val applied = if (firstDropOnly) drops.take(1) else drops
            return when {
                dropAllOnceAnyDrop -> drops.isNotEmpty()
                dropByPosition -> applied.any { it.seq > seq }
                else -> applied.any { seq <= (it.event as ReasoningDropped).upToSeq }
            }
        }
    }
}
