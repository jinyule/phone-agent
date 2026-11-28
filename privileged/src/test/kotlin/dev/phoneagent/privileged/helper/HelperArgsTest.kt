package dev.phoneagent.privileged.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperArgsTest {
    @Test
    fun packageAloneUsesTheDefaultGrace() {
        assertEquals(
            HelperArgs("dev.phoneagent", HelperArgs.DEFAULT_ORPHAN_GRACE_MS),
            HelperArgs.parse(arrayOf("dev.phoneagent")),
        )
    }

    @Test
    fun graceCanBeSet() {
        assertEquals(
            HelperArgs("dev.phoneagent", 3_000),
            HelperArgs.parse(arrayOf("dev.phoneagent", "--orphan-grace-ms=3000")),
        )
        assertEquals(1L, HelperArgs.parse(arrayOf("dev.phoneagent", "--orphan-grace-ms=1")).orphanGraceMs)
        assertEquals(600_000L, HelperArgs.parse(arrayOf("dev.phoneagent", "--orphan-grace-ms=600000")).orphanGraceMs)
    }

    @Test
    fun packageCheckIntervalCanBeSet() {
        assertEquals(HelperArgs.DEFAULT_PACKAGE_CHECK_MS, HelperArgs.parse(arrayOf("dev.phoneagent")).packageCheckMs)
        assertEquals(
            HelperArgs("dev.phoneagent", orphanGraceMs = 2_000, packageCheckMs = 5_000),
            HelperArgs.parse(arrayOf("dev.phoneagent", "--package-check-ms=5000", "--orphan-grace-ms=2000")),
        )
        assertEquals(1_000L, HelperArgs.parse(arrayOf("dev.phoneagent", "--package-check-ms=1000")).packageCheckMs)
        assertEquals(600_000L, HelperArgs.parse(arrayOf("dev.phoneagent", "--package-check-ms=600000")).packageCheckMs)
    }

    @Test
    fun badArgumentsFailLoudly() {
        fun message(vararg args: String) =
            assertThrows(IllegalArgumentException::class.java) { HelperArgs.parse(arrayOf(*args)) }.message.orEmpty()

        assertTrue(message().contains("missing"))
        assertTrue(message("phoneagent").contains("invalid package"))
        assertTrue(message("dev.phoneagent;rm").contains("invalid package"))
        assertTrue(message("dev.phoneagent", "--nonce=abc").contains("unknown argument"))
        assertTrue(message("dev.phoneagent", "--orphan-grace-ms=0").contains("1..600000"))
        assertTrue(message("dev.phoneagent", "--orphan-grace-ms=600001").contains("1..600000"))
        assertTrue(message("dev.phoneagent", "--orphan-grace-ms=ten").contains("ten"))
        assertTrue(message("dev.phoneagent", "--package-check-ms=999").contains("1000..600000"))
        assertTrue(message("dev.phoneagent", "--package-check-ms=600001").contains("1000..600000"))
        assertTrue(message("dev.phoneagent", "--package-check-ms=").contains("''"))
    }

    @Test
    fun aPackageArgumentOf64KiBIsParsedOrRefusedWithoutOverflowingTheStack() {
        // FWD-B1 sweep: the argument was checked by a regex repeating a group, which overflowed the stack.
        val names = mapOf("valid" to "a.".repeat(KIB_64 / 2 - 1) + "aa", "ends in ." to "a.".repeat(KIB_64 / 2))
        assertEquals(listOf(KIB_64, KIB_64), names.values.map { it.length })
        val observed = names.mapValues { (_, name) -> outcome(name) }
        assertEquals(mapOf("valid" to "parsed", "ends in ." to "refused: invalid package name"), observed)
    }

    private fun outcome(name: String): String =
        try {
            if (HelperArgs.parse(arrayOf(name)).packageName == name) "parsed" else "parsed as another name"
        } catch (refused: IllegalArgumentException) {
            "refused: " + refused.message.orEmpty().substringBefore(" '")
        } catch (overflow: StackOverflowError) {
            overflow.toString()
        }
}

/** 64 KiB, in characters (the names are ASCII). */
private const val KIB_64 = 65_536
