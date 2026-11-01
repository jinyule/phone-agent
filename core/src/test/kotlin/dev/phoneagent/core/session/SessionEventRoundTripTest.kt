package dev.phoneagent.core.session

import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ToolControl
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SessionEventRoundTripTest {
    private val codec = SessionCodec()

    @ParameterizedTest(name = "{0}")
    @MethodSource("records")
    fun `every record survives encode and decode unchanged`(record: SessionRecord) {
        val line = codec.encode(record)
        assertTrue('\n' !in line, "one line per record")
        assertEquals(DecodedLine.Event(record), codec.decode(line, version = 1, lineNumber = 2))
    }

    @Test
    fun `samples cover every event type and the registry knows exactly the sealed subclasses`() {
        val sealedNames =
            serializer<SessionEvent>()
                .descriptor
                .getElementDescriptor(1)
                .elementNames
                .toSet()
        assertEquals(sealedNames, SessionEventTypes.names)
        assertEquals(sealedNames, SessionSamples.events.map(SessionEventTypes::typeOf).toSet())
    }

    @Test
    fun `samples cover every nested variant`() {
        val all = SessionSamples.records.joinToString("\n") { codec.encode(it) }
        val discriminators = Regex("\"type\":\"([a-z_]+)\"").findAll(all).map { it.groupValues[1] }.toSet()
        val nested =
            listOf(
                serializer<Part>(),
                serializer<Verdict>(),
                serializer<UiAction>(),
                serializer<LaunchTarget>(),
                serializer<ActionOutcome.Status>(),
                serializer<ContextEditPolicy>(),
                serializer<AskResult>(),
                serializer<ToolControl>(),
            ).flatMap { it.descriptor.getElementDescriptor(1).elementNames }
        // tool_result parts only occur in derived messages (tool/result stores a ToolResult); see PartRoundTripTest.
        val expected = nested - "tool_result"
        assertEquals(emptyList<String>(), expected.filterNot { it in discriminators }, "variants without a sample")
    }

    @Test
    fun `type names follow the naming rule`() {
        SessionEventTypes.names.forEach { assertTrue(Envelope.TYPE_PATTERN.matches(it), it) }
    }

    @Test
    fun `ignorable flags are part of the format`() {
        val notIgnorable = SessionEventTypes.names.filter { SessionEventTypes.isIgnorable(it) == false }.toSet()
        assertEquals(
            setOf(
                "turn/start",
                "turn/end",
                "step/start",
                "step/end",
                "inbox/spliced",
                "user/message",
                "request/header",
                "assistant/message",
                "tool/call",
                "tool/result",
                "compaction/summary",
                "compaction/prune",
            ),
            notIgnorable,
        )
        assertEquals(null, SessionEventTypes.isIgnorable("future/thing"))
    }

    companion object {
        @JvmStatic
        fun records(): List<SessionRecord> = SessionSamples.records
    }
}
