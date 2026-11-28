package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import kotlin.random.Random

/** [splitJoiners]: where it splits, and that it splits where the regex it replaced did (FWD-B1 sweep). */
class SplitJoinersTest {
    @Test
    fun `joiners are whole words, or signs between spaces`() {
        val cases =
            listOf(
                "send and delete" to listOf("send ", " delete"),
                "send-and-delete" to listOf("send-", "-delete"),
                "send and then delete" to listOf("send ", " ", " delete"),
                "send & delete" to listOf("send ", " delete"),
                "send + post / pay" to listOf("send ", " post ", " pay"),
                "and" to listOf("", ""),
                "send" to listOf("send"),
                "" to listOf(""),
                // Not whole words: letters, digits, `_` or a mark on a letter go on.
                "sandy and1 and_ thence" to listOf("sandy and1 and_ thence"),
                "and${points(0x0332)} delete" to listOf("and${points(0x0332)} delete"),
                "x${points(0x0332)}and" to listOf("x${points(0x0332)}and"),
                // A mark after a space is no letter's: the word starts after it.
                " ${points(0x0332)}and" to listOf(" ${points(0x0332)}", ""),
                // Signs join only between spaces.
                "send& delete" to listOf("send& delete"),
                "send &delete" to listOf("send &delete"),
                "a${points(0x0332).repeat(100_000)} and b" to listOf("a${points(0x0332).repeat(100_000)} ", " b"),
            )
        assertAll(cases.map { (text, parts) -> Executable { assertEquals(parts, splitJoiners(text), text.take(40)) } })
    }

    @Test
    fun `the scan splits where the regex it replaced did`() {
        // Every string of three fragments, then longer random ones (fixed seed); the regex only ever
        // sees these short inputs.
        val each = FRAGMENTS.distinct()
        val short = each.flatMap { a -> each.flatMap { b -> each.map { c -> a + b + c } } }
        val random = Random(SEED)
        val long =
            List(RANDOM_CASES) {
                List(random.nextInt(1, MAX_FRAGMENTS + 1)) { FRAGMENTS.random(random) }.joinToString("")
            }
        val differ = (short + long).filter { splitJoiners(it) != ORIGINAL.split(it) }
        assertEquals(emptyList<String>(), differ.take(20), "${differ.size} of ${short.size + long.size} differ")
    }
}

/** The regex [splitJoiners] replaced, the reference on short inputs only. */
private val ORIGINAL = Regex("\\b(?:and|then)\\b|(?<=\\s)[&+/](?=\\s)")

/** [code points][points] as a string: marks and characters beyond the basic plane written as numbers. */
private fun points(vararg points: Int): String = points.joinToString("") { String(Character.toChars(it)) }

/**
 * What the differential test builds strings of: the joiners and near misses, the signs, every space
 * `\s` is and three characters it is not (one a control character below them), ASCII and other
 * letters, a digit, `_`, punctuation, marks of each kind (non-spacing, also beyond the basic plane;
 * spacing; enclosing), a letter beyond the basic plane, and the halves of one alone.
 */
private val FRAGMENTS: List<String> =
    listOf("and", "and", "then", "then", "an", "nd", "the", "andthen", "and and") +
        listOf("&", "+", "/", "&&") +
        listOf(" ", "  ", "\t", "\n", points(0x0B), points(0x0C), "\r", points(0x08), points(0xA0), points(0x3000)) +
        listOf("a", "d", "s", "x", "X", "é", "删", "1", "_", "-", ".", ",") +
        listOf(points(0x0332), points(0x1D167), points(0x0903), points(0x20DD), points(0x1D400)) +
        listOf(Char(0xD835).toString(), Char(0xDC00).toString())

private const val SEED = 20_261_112
private const val RANDOM_CASES = 1_000_000
private const val MAX_FRAGMENTS = 16
