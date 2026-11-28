package dev.phoneagent.core.ports

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import kotlin.random.Random

/**
 * Package names of any length are checked where they are built, a long one in time and stack in
 * proportion to it (FWD-B1 sweep: a regex repeating a group overflowed the stack on "a.a.a…").
 */
class PackageNamesTest {
    @Test
    fun `a package name of any length is accepted or refused, never overflowing the stack`() {
        val long = "a" + ".a".repeat(REPEATS)
        val cells =
            listOf(
                Executable { assertEquals(long, LaunchTarget.App(long).packageName) },
                Executable { assertEquals(long, AppEntry(long, "a", launchable = true).packageName) },
                Executable { assertThrows<IllegalArgumentException> { LaunchTarget.App("$long.") } },
                Executable { assertThrows<IllegalArgumentException> { AppEntry("$long.", "a", launchable = true) } },
                Executable { assertThrows<IllegalArgumentException> { LaunchTarget.App("$long.1") } },
            )
        assertAll(cells)
    }

    @Test
    fun `the check accepts what the regex it replaced matched`() {
        // Every string of up to five characters, then longer random ones (fixed seed); the regex
        // only ever sees these short inputs.
        val each = NAME_CHARS.distinct()
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
            ) { List(random.nextInt(1, MAX_LENGTH + 1)) { NAME_CHARS.random(random) }.joinToString("") }
        val all = short.flatten() + long
        val differ = all.filter { isPackageName(it) != ORIGINAL.matches(it) }
        assertEquals(emptyList<String>(), differ.take(20), "${differ.size} of ${all.size} differ")
    }
}

/** The regex [isPackageName] replaced, the reference on short inputs only. */
private val ORIGINAL = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** What the differential test builds names of: each kind of segment character, `.`, and characters no name has. */
private val NAME_CHARS: List<String> = listOf("a", "Z", "0", "_", ".", ".", "-", " ", "é", "\n")

/** Segments in the long name: far more than the stack holds repetitions of a regex group. */
private const val REPEATS = 32_000
private const val EXHAUSTIVE_LENGTH = 5
private const val SEED = 20_261_112
private const val RANDOM_CASES = 200_000
private const val MAX_LENGTH = 24
