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
                val prefix = Message(Role.ASSISTANT, (cut.event as AssistantMessage).parts)
                assertEquals(visible, prefix in final.messages, "${case.name}: seq ${cut.seq}")
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
    fun `dropping reasoning removes only the reasoning parts of earlier replies`() {
        val case = DeriveRuleSamples.cases.single { c -> c.records.any { it.event is ReasoningDropped } }
        val drop = case.records.single { it.event is ReasoningDropped }
        val before = case.views.last { it.afterSeq < drop.seq }
        val after = case.views.first { it.afterSeq >= drop.seq }
        val stripped = before.messages.map { Message(it.role, it.parts.filterNot { part -> part is Part.Reasoning }) }
        assertTrue(
            before.messages.any { m ->
                m.parts.any { it is Part.Reasoning }
            },
            "the sample has reasoning to drop",
        )
        assertEquals(stripped, after.messages, "everything else, tool calls and results included, is unchanged")
    }
}
