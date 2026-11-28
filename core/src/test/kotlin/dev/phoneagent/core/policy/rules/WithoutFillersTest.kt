package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import kotlin.random.Random

/** [withoutFillers]: what it takes off, and that it takes off what the regex it replaced did (FWD-B1). */
class WithoutFillersTest {
    @Test
    fun `also is taken off the start of a part as often as it is written, each time a whole word`() {
        val cases =
            listOf(
                "also delete" to "delete",
                "also also, also; delete" to "delete",
                "also-also-delete" to "delete",
                "also" to "",
                "also delete also" to "delete also",
                "delete also" to "delete also",
                // Not a whole word: another letter, a digit, `_` or a mark on its last letter follows.
                "alsofoo" to "alsofoo",
                "alsoX" to "alsoX",
                "also1 delete" to "also1 delete",
                "also_delete" to "also_delete",
                "also${points(0x0332)} delete" to "also${points(0x0332)} delete",
                "also -alsodelete" to "alsodelete",
                "also ".repeat(1_000_000) + "delete" to "delete",
            )
        assertAll(cases.map { (part, left) -> Executable { assertEquals(left, withoutFillers(part), part.take(40)) } })
    }

    @Test
    fun `the scan takes off what the regex it replaced did`() {
        // Every string of three fragments, with and without "also" before it, then longer random
        // ones (fixed seed, "also" more likely); the regex only ever sees these short inputs.
        val each = FRAGMENTS.distinct()
        val short =
            each.flatMap { a -> each.flatMap { b -> each.map { c -> a + b + c } } }.flatMap { listOf(it, "also$it") }
        val random = Random(SEED)
        val long =
            List(RANDOM_CASES) {
                val start = if (random.nextBoolean()) "also" else ""
                start + List(random.nextInt(1, MAX_FRAGMENTS + 1)) { FRAGMENTS.random(random) }.joinToString("")
            }
        val differ = (short + long).filter { withoutFillers(it) != ORIGINAL.replace(it, "") }
        assertEquals(emptyList<String>(), differ.take(20), "${differ.size} of ${short.size + long.size} differ")
    }
}

/** The regex [withoutFillers] replaced (FWD-B1), the reference on short inputs only. */
private val ORIGINAL = Regex("^(?:also\\b\\W*)+")

/** [code points][points] as a string, so marks and characters beyond the basic plane are written as numbers. */
private fun points(vararg points: Int): String = points.joinToString("") { String(Character.toChars(it)) }

/**
 * What the differential test builds strings of: "also" and near misses, spaces, punctuation, ASCII
 * and other letters, a digit, `_`, and marks of each kind (non-spacing, also beyond the basic plane;
 * spacing; enclosing) and a letter beyond the basic plane.
 */
private val FRAGMENTS: List<String> =
    listOf("also", "also", "also", "Also", "als", "lso", "alsoalso", "also also") +
        listOf(" ", "  ", "\t", "\n", points(0x00A0), points(0x3000)) +
        listOf(",", ".", "-", "!", "?", "'", "、", "，", "&", "/", ";") +
        listOf("a", "o", "x", "X", "foo", "delete", "é", "删", "1", "_") +
        listOf(points(0x0332), points(0x1D167), points(0x0903), points(0x20DD), points(0x1D400))

private const val SEED = 20_261_112
private const val RANDOM_CASES = 1_000_000
private const val MAX_FRAGMENTS = 16
