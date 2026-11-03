package dev.phoneagent.core.model.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The Bearer rule of [Redactor.text] (model-layer §2 rule 4) on runs longer than any error body,
 * and on random text against the regular expression fix-list r10 S1 gave for the rule: the same
 * result for every text, without the stack that expression takes for a long run of bearer words.
 * The pieces hold no `sk-` or JWT shape, so only the Bearer rule changes the text.
 */
class BearerRunTest {
    @Test
    fun `a run of bearer words of any length is masked as one`() {
        assertEquals("Bearer ***", Redactor.SHAPES_ONLY.text("Bearer ".repeat(RUN) + TOKEN))
        val forbearers = "the " + "forbearer ".repeat(RUN) + TOKEN + " applies"
        assertEquals("the forbearer *** applies", Redactor.SHAPES_ONLY.text(forbearers))
    }

    @Test
    fun `random text is masked as the regular expression of fix-list r10 masks it`() {
        val random = Random(SEED)
        repeat(CASES) {
            val text = buildString { repeat(random.nextInt(MAX_PIECES)) { append(PIECES.random(random)) } }
            assertEquals(RULE.replace(text, "$1***"), Redactor.SHAPES_ONLY.text(text), "for [$text]")
        }
    }

    private companion object {
        /** The rule as fix-list r10 S1 wrote it; the stack it takes grows with the run. */
        val RULE = Regex("""(?i)(bearer\s+)(?:[^\s"',;]*bearer\s+)*[^\s"',;]+""")

        /** Every way to spell the word, parts of it, each character that ends a word, and text. */
        val PIECES =
            listOf("bearer", "Bearer", "BEARER", "bEaReR", "bear", "er", "for", "forbearer", "x", "tok", ":", "é") +
                listOf(" ", "  ", "\t", "\n", "\r", Char(VT).toString(), Char(FF).toString(), "\\n") +
                listOf(Char(NBSP).toString(), Char(EM_SPACE).toString(), "\"", "'", ",", ";")

        const val SEED = 10
        const val CASES = 300_000
        const val MAX_PIECES = 14

        /** Words in a run; many more than an error body (64 KiB) holds. */
        const val RUN = 100_000
        const val VT = 0x0B
        const val FF = 0x0C

        /** Not whitespace to the rule: they belong to the word. */
        const val NBSP = 0xA0
        const val EM_SPACE = 0x2003

        /** The token after `Bearer`, of no credential shape. */
        const val TOKEN = "gw-upstream-token-" + "placeholder-0123456789"
    }
}
