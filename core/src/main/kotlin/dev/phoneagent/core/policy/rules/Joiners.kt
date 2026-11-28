package dev.phoneagent.core.policy.rules

/**
 * [text] split at each joiner: a [Words.ENGLISH_JOINERS] word that is a whole word, or an
 * [Words.ENGLISH_SIGNS] sign with a space (`[ \t\n\x0B\f\r]`) right before and right after it.
 * The joiners are left out; the parts keep everything else. The same parts as
 * `text.split(Regex("\\b(?:and|then)\\b|(?<=\\s)[&+/](?=\\s)"))` on the JVM, with its word
 * boundaries ([WordEdges]) and spaces, on every platform: Android, whose `\b` and `\s` are
 * Unicode, kept "Sendéand delete" and "发送and删除" whole (policy-rules.md §8). In time in
 * proportion to [text].
 */
internal fun splitJoiners(text: String): List<String> {
    val edges = WordEdges(text)
    val parts = ArrayList<String>()
    var start = 0
    var at = 0
    while (at < text.length) {
        val end = joinerEnd(text, at, edges)
        if (end < 0) {
            at++
        } else {
            parts += text.substring(start, at)
            start = end
            at = end
        }
    }
    parts += text.substring(start)
    return parts
}

/** Where the joiner that starts at [at] in [text] ends, or -1 when none starts there. */
private fun joinerEnd(
    text: String,
    at: Int,
    edges: WordEdges,
): Int {
    val word = Words.ENGLISH_JOINERS.firstOrNull { text.startsWith(it, at) }
    return when {
        word != null && edges.at(at) && edges.at(at + word.length) -> at + word.length
        text[at] in Words.ENGLISH_SIGNS && spaceAt(text, at - 1) && spaceAt(text, at + 1) -> at + 1
        else -> -1
    }
}

/** Whether what `\s` is on the JVM is at [at] in [text]. */
private fun spaceAt(
    text: String,
    at: Int,
): Boolean = at in text.indices && (text[at] == ' ' || text[at] in '\t'..'\r')

/**
 * The word boundaries of [text] as `\b` reads them on the JVM: between a word character and
 * another character, at either end too. A word character is an ASCII letter, digit or `_`, or a
 * non-spacing mark after a letter or digit (Unicode) with only such marks between, each looked at
 * by the code point that starts at its index. Found in one pass, where the JVM reads back over
 * the marks at every boundary it checks.
 */
private class WordEdges(
    private val text: String,
) {
    /**
     * For each index, whether a letter or digit is there, or a non-spacing mark after one with only
     * such marks between (what `\b` reads back over).
     */
    private val based = BooleanArray(text.length)

    init {
        for (i in text.indices) {
            val point = text.codePointAt(i)
            based[i] = Character.isLetterOrDigit(point) || (isMark(point) && i > 0 && based[i - 1])
        }
    }

    /** Whether there is a word boundary right before [index]. */
    fun at(index: Int): Boolean = wordBefore(index) != wordAt(index)

    private fun wordAt(index: Int): Boolean {
        if (index >= text.length) return false
        val point = text.codePointAt(index)
        return isAsciiWord(point) || (isMark(point) && based[index])
    }

    private fun wordBefore(index: Int): Boolean {
        if (index <= 0) return false
        val point = text.codePointBefore(index)
        return isAsciiWord(point) || (isMark(point) && based[index - 1])
    }
}

private fun isMark(point: Int): Boolean = Character.getType(point) == Character.NON_SPACING_MARK.toInt()

/** An ASCII letter, digit or `_`. */
private fun isAsciiWord(point: Int): Boolean =
    point in 'a'.code..'z'.code || point in 'A'.code..'Z'.code || point in '0'.code..'9'.code || point == '_'.code
