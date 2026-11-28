package dev.phoneagent.privileged

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [isPackageName], copied from core: a name of any length is checked in time and stack in
 * proportion to it (FWD-B1 sweep: the regex it replaced overflowed the stack on "a.a.a…").
 */
class PackageNameFormatTest {
    @Test
    fun aNameOf64KiBIsAcceptedOrRefusedWithoutOverflowingTheStack() {
        val names = mapOf("valid" to "a.".repeat(KIB_64 / 2 - 1) + "aa", "ends in ." to "a.".repeat(KIB_64 / 2))
        assertEquals(listOf(KIB_64, KIB_64), names.values.map { it.length })
        val observed = names.mapValues { (_, name) -> outcome(name) }
        assertEquals(mapOf("valid" to "accepted", "ends in ." to "refused"), observed)
    }

    @Test
    fun theCheckAcceptsWhatTheRegexItReplacedMatched() {
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
        assertEquals("${differ.size} of ${all.size} differ", emptyList<String>(), differ.take(20))
    }

    private fun outcome(name: String): String =
        try {
            if (isPackageName(name)) "accepted" else "refused"
        } catch (overflow: StackOverflowError) {
            overflow.toString()
        }
}

/** The regex [isPackageName] replaced in HelperArgs and PackageNames, the reference on short inputs only. */
private val ORIGINAL = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** What the differential test builds names of: each kind of segment character, `.`, and characters no name has. */
private val NAME_CHARS: List<String> = listOf("a", "Z", "0", "_", ".", ".", "-", " ", "é", "\n")

/** 64 KiB, in characters (the names are ASCII). */
private const val KIB_64 = 65_536
private const val EXHAUSTIVE_LENGTH = 5
private const val SEED = 20_261_112
private const val RANDOM_CASES = 200_000
private const val MAX_LENGTH = 24
