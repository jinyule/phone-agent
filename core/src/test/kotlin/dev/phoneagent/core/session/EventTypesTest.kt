package dev.phoneagent.core.session

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import kotlin.random.Random

/**
 * Event types of any length are checked where a record is built, a long one in time and stack in
 * proportion to it (FWD-B1 sweep: a regex repeating a group overflowed the stack on "a-a-a…").
 */
class EventTypesTest {
    private fun envelope(type: String) = Envelope(seq = 1, time = 0, type = type, data = JsonObject(emptyMap()))

    @Test
    fun `a type of any length is accepted or refused, never overflowing the stack`() {
        val long = "a" + "-a".repeat(REPEATS)
        val cells =
            listOf("$long/a", "a/$long", "$long/$long").map { type ->
                Executable { assertEquals(type, envelope(type).type) }
            } +
                listOf("$long-/a", "a/$long-", "$long/$long/a", "$long--a/a").map { type ->
                    Executable { assertThrows<IllegalArgumentException> { envelope(type) } }
                }
        assertAll(cells)
    }

    @Test
    fun `the check accepts what the pattern matches`() {
        // Every string of up to six characters, then longer random ones (fixed seed); the regex
        // only ever sees these short inputs.
        val each = TYPE_CHARS.distinct()
        val short =
            (1..EXHAUSTIVE_LENGTH).runningFold(listOf("")) { shorter, _ ->
                shorter.flatMap { s ->
                    each.map { s + it }
                }
            }
        val random = Random(SEED)
        val long =
            List(
                RANDOM_CASES,
            ) { List(random.nextInt(1, MAX_LENGTH + 1)) { TYPE_CHARS.random(random) }.joinToString("") }
        val all = short.flatten() + long
        val differ = all.filter { isEventType(it) != Envelope.TYPE_PATTERN.matches(it) }
        assertEquals(emptyList<String>(), differ.take(20), "${differ.size} of ${all.size} differ")
    }
}

/** What the differential test builds types of: lowercase letters, `-`, `/`, and characters no type has. */
private val TYPE_CHARS: List<String> = listOf("a", "z", "a", "-", "-", "/", "A", "0", "_", " ")

/** Words in the long type: far more than the stack holds repetitions of a regex group. */
private const val REPEATS = 32_000
private const val EXHAUSTIVE_LENGTH = 6
private const val SEED = 20_261_112
private const val RANDOM_CASES = 200_000
private const val MAX_LENGTH = 24
