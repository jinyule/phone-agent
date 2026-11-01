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
    }
}
