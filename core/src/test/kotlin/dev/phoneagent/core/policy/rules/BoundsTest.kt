package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.tool.RiskClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reading hostile texts and screens ends, in a number of steps within bounds quadratic in the
 * screen (policy-rules.md §8). Each case of [BoundsProbe] runs in a JVM of its own; it fails when
 * the steps counted reading one of its screens ([Work]) exceed their bounds
 * ([BoundsProbe.exceeded]), and it is killed when it has not finished within [LIMIT_SECONDS].
 * The counts are what proves the bounds; time is not measured. The limit is only how long to wait
 * before calling a case hung: at least thirty times what any case takes on a developer machine,
 * JVM start included, so a slow machine does not fail it. On the rules before the first bounds
 * (81d14af) eight of the first nine cases did not finish within it; on 41f46f5 the cases of
 * touches over candidates and of drawn text over everything read OCR lines 4.1 and 8.2 billion
 * times, against a bound of about 10 million (`M·(2·E + 2)`).
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

    @Test
    fun `a count above its bound fails the probe`() {
        val s = Fx.screen(Fx.el("e1", "确定"), Fx.el("e2"), ocr = listOf(Fx.ocr("删除")))
        // Two elements and one OCR line: three touches look at 36 elements, five OCR lines are read
        // for two owns and three points, and the sum, 49, is within 13·3² + 42·3 + 4 = 247.
        val within =
            Work.counts().mapValues { 0L } +
                mapOf("touches" to 3L, "under" to 36L, "owns" to 2L, "points" to 3L, "ocr" to 5L)
        assertEquals(emptyList<String>(), BoundsProbe.exceeded(s, 11, within))
        val counts = mapOf("under" to 37L, "ocr" to 6L, "content" to 1L, "gathered" to 2000L)
        assertEquals(
            listOf("under 37 > 36", "ocr 6 > 5", "content 1 > 0", "gathered 2000 > 9", "total 2052 > 247"),
            BoundsProbe.exceeded(s, 1, within + counts),
        )
    }

    @Test
    fun `a label of many joined English actions is normalized in proportion to its length`() {
        val policy = Fx.rules()
        val tap = Fx.tap("e1")
        // Per character of the label, for 50 and for 500 parts: within a bound that does not grow with them.
        val perCharacter =
            listOf("send", "delete").associateWith { word ->
                listOf(50, 500).map { n ->
                    val label = List(n) { word }.joinToString(" and ")
                    val s = Fx.screen(Fx.el("e1", label))
                    val ctx = Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg))
                    Work.reset()
                    policy.engine.evaluate(tap, policy.resolver.resolve(tap, RiskClass.WRITE, s), ctx)
                    Work.characters.sum() / label.length
                }
            }
        assertTrue(perCharacter.values.flatten().all { it <= NORMALIZED_PER_CHARACTER }, "$perCharacter")
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

        /** Times a label is normalized reading one tap on it, at most, whatever it says. */
        const val NORMALIZED_PER_CHARACTER = 64L

        @JvmStatic
        fun cases(): List<String> = BoundsProbe.CASES.keys.toList()
    }
}
