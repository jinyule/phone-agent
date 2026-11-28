@file:OptIn(ExperimentalSerializationApi::class)

package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.session.Envelope
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionHeader
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lock (c) of the normalizer (WP08 design §6): every numeric and id field that the session log and
 * the requests file can hold has exactly one normalization class in
 * `replay/normalized-fields.txt`. A new field fails here until it is classified, so a new time or
 * id cannot slip into fixtures unnormalized, and a stale line fails too.
 */
class NormalizedFieldsTest {
    private val actualFile: Path = Path.of("build/replay/normalized-fields.actual.txt")

    /** What the serializers can write, as `scope path leaf`. */
    private fun inventory(): List<String> {
        val events = serializer<SessionEvent>().descriptor.getElementDescriptor(1)
        val scopes =
            listOf(
                "header" to serializer<SessionHeader>().descriptor,
                "envelope" to serializer<Envelope>().descriptor,
                "requests" to serializer<RecordedRequests.Line>().descriptor,
            ) + (0 until events.elementsCount).map { events.getElementName(it) to events.getElementDescriptor(it) }
        return scopes.flatMap { (scope, descriptor) ->
            FieldInventory.of(descriptor).map { "$scope ${it.path} ${it.type}" }
        }
    }

    @Test
    fun `every numeric and id field is in the table, and every line of the table is a field`() {
        val actual = inventory()
        val listed = NormalizedFields.entries.map { "${it.scope} ${it.path} ${it.leaf}" }
        if (actual.toSet() != listed.toSet()) {
            Files.createDirectories(actualFile.parent)
            Files.writeString(actualFile, actual.joinToString("") { "$it\n" })
        }
        assertEquals(
            emptyList<String>(),
            actual - listed.toSet(),
            "unclassified fields: add them to ${NormalizedFields.FILE} with a class (all of them: $actualFile)",
        )
        assertEquals(emptyList<String>(), listed - actual.toSet(), "lines for fields that no longer exist")
    }

    @Test
    fun `each field is listed once`() {
        val keys = NormalizedFields.entries.map { it.scope to it.path.text }
        assertEquals(
            emptyList<Pair<String, String>>(),
            keys
                .groupingBy {
                    it
                }.eachCount()
                .filterValues { it > 1 }
                .keys
                .toList(),
        )
    }

    @Test
    fun `id classes sit on their id type, numeric classes on numbers`() {
        val misfits =
            NormalizedFields.entries.filterNot { entry ->
                val number = entry.leaf in NUMBERS
                when (entry.fieldClass) {
                    FieldClass.TIME, FieldClass.EXPIRY, FieldClass.DURATION, FieldClass.SEQ, FieldClass.VALUE -> {
                        number
                    }

                    FieldClass.KEPT -> {
                        !number && entry.leaf !in TOKENIZED.values
                    }

                    FieldClass.TASK, FieldClass.SESSION, FieldClass.CALL, FieldClass.PROMPT -> {
                        entry.leaf ==
                            TOKENIZED[entry.fieldClass]
                    }
                }
            }
        assertEquals(emptyList<NormalizedFields.Entry>(), misfits)
    }

    @Test
    fun `only the two clocks, the retry delay and the approval deadline lose their value`() {
        // A field the normalizer zeroes or shifts hides a change of it from replay, whether or not a
        // fixture holds it today; one more here needs a reason in engineering-baseline §3.2.
        val losing = setOf(FieldClass.TIME, FieldClass.DURATION, FieldClass.EXPIRY)
        assertEquals(
            listOf(
                "approval/asked expiresAt expiry",
                "envelope time time",
                "header createdAt time",
                "request/retry delayMs duration",
            ),
            NormalizedFields.entries
                .filter { it.fieldClass in losing }
                .map { "${it.scope} ${it.path} ${it.fieldClass.label}" }
                .sorted(),
        )
    }

    @Test
    fun `the walker reaches fields through lists, maps, nullable and sealed members`() {
        val leaves = FieldInventory.of(serializer<WalkerSample>().descriptor).map { "${it.path} ${it.type}" }
        assertEquals(
            listOf(
                "count Int",
                "seqs[] Long",
                "byName.* Long",
                "nested[][] Double",
                "maybe ToolCallId",
                "pick.{a}.at Long",
                "pick.{b}.ids[] PromptId",
            ),
            leaves,
        )
    }

    @Test
    fun `a malformed table line fails the load and names the line`() {
        val failure = assertThrows<IllegalArgumentException> { NormalizedFields.parse("# c\nheader createdAt Long\n") }
        assertTrue("line 2" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an unknown class fails the load`() {
        assertThrows<IllegalArgumentException> { NormalizedFields.parse("header createdAt Long clock\n") }
    }

    private companion object {
        val NUMBERS = setOf("Long", "Int", "Short", "Byte", "Double", "Float")
        val TOKENIZED =
            mapOf(
                FieldClass.TASK to "TaskId",
                FieldClass.SESSION to "SessionId",
                FieldClass.CALL to "ToolCallId",
                FieldClass.PROMPT to "PromptId",
            )
    }
}

/** Every shape the walker must see through; `again` checks that a type containing itself ends. */
@Serializable
private data class WalkerSample(
    val name: String,
    val count: Int,
    val seqs: List<Long>,
    val byName: Map<String, Long>,
    val nested: List<List<Double>>,
    val maybe: ToolCallId?,
    val pick: Pick,
    val tree: JsonObject,
    val flag: Boolean,
    val again: WalkerSample?,
)

@Serializable
private sealed interface Pick {
    @Serializable
    @SerialName("a")
    data class A(
        val at: Long,
    ) : Pick

    @Serializable
    @SerialName("b")
    data class B(
        val ids: List<PromptId>,
    ) : Pick
}
