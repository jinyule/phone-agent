package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reading hostile texts and screens ends (policy-rules.md §8). Each case of [BoundsProbe] runs in
 * a JVM of its own, killed when it has not finished within [LIMIT_SECONDS]. The time is not
 * measured: the limit is only how long to wait before calling a case hung. It is about a hundred
 * times what a case takes on a developer machine, so a slow machine does not fail it, while on the
 * rules before these bounds every case but the one beyond the limits did not finish within it.
 */
class BoundsTest {
    @Test
    fun `the probe screens are at the default limits, or beyond them`() {
        val tuning = PolicyTuning()
        assertEquals(
            listOf(tuning.maxElements, tuning.maxScreenText, tuning.maxDepth),
            listOf(BoundsProbe.MAX_ELEMENTS, BoundsProbe.MAX_SCREEN_TEXT, BoundsProbe.MAX_DEPTH),
        )
        val reading = Reading(tuning)
        for ((name, build) in BoundsProbe.CASES) {
            val case = build()
            case.screens.forEach { assertEquals(case.readable, reading.readable(it), name) }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `every hostile case finishes, in a process of its own`(case: String) {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val log = File.createTempFile("bounds-probe", ".log")
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), BoundsProbe::class.java.name, case)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
        val finished = process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly().waitFor()
        val output = log.readText().take(OUTPUT_SHOWN)
        log.delete()
        assertTrue(finished, "'$case' did not finish within $LIMIT_SECONDS s")
        assertEquals(0, process.exitValue(), "'$case' failed: $output")
    }

    private companion object {
        const val LIMIT_SECONDS = 120L
        const val OUTPUT_SHOWN = 2000

        @JvmStatic
        fun cases(): List<String> = BoundsProbe.CASES.keys.toList()
    }
}
