package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What comes before a label: nothing, a space, a line break. */
private val LEADS = listOf("", " ", "\n")

/** Between a prefix and the word: nothing, a space. */
private val GAPS = listOf("", " ")

/** What a label may end with: punctuation, a space, an amount. */
private val ENDS =
    listOf("", "!", "！", ".", "。", "?", "？", "~", "…", " ", " ¥20", "¥20", " ¥15/月", " $5", " 20元")

/**
 * A text that is an [Words.EXACT] word is no strong word and no English phrase as well, so
 * whichever of the three readings of a text comes first changes nothing for it (policy-rules.md
 * §3.2; Claude r7 S-3). This holds for the lists as they are: every prefix and every exact word
 * starts with a Chinese character, or the prefix is empty, and none holds a [Words.STRONG] word.
 * A word added to these lists that breaks it shows here.
 */
class ExactWordsTest {
    private val tuning = PolicyTuning()

    @Test
    fun `an exact word with any prefix and ending is never also a strong word or an English phrase`() {
        val labels =
            LEADS.flatMap { lead ->
                Words.PREFIXES.flatMap { prefix ->
                    GAPS.flatMap { gap ->
                        Words.EXACT.flatMap { (word, _) -> ENDS.map { end -> "$lead$prefix$gap$word$end" } }
                    }
                }
            }
        val exact = labels.filter { Phrases.exact(it) != null }
        // Without vetoes ("complete = false") each reading finds the most it can.
        val overlaps =
            exact.mapNotNull { label ->
                val english =
                    listOf(tuning.labelMax, tuning.contextMax, null).flatMap { max ->
                        listOf(false, true).map { pauses -> Phrases.english(label, false, max, pauses) }
                    }
                val others = listOf(Phrases.strong(label, complete = false)) + english
                "$label: $others".takeIf { others.any { hit -> hit != null } }
            }
        assertEquals(emptyList<String>(), overlaps)
        // Each prefix and word read alone is an exact word, so the check is not empty.
        val bare = Words.PREFIXES.flatMap { prefix -> Words.EXACT.map { (word, _) -> prefix + word } }
        assertTrue(bare.all { it in exact }, "${bare.filterNot { it in exact }}")
        assertEquals(LEADS.size * Words.PREFIXES.size * GAPS.size * Words.EXACT.size * ENDS.size, labels.size)
    }
}
