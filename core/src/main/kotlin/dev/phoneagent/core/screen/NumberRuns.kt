package dev.phoneagent.core.screen

/**
 * The scanner behind [SensitiveNumbers]: where the digit runs of a text are, on a same-length
 * plain copy of it. A gap between digit groups is any run of spaces with at most
 * [MAX_GAP_DASHES] dashes in it, where a space is one of [UNICODE_SPACES] or an invisible format
 * character (Unicode category Cf) and a dash is `-` or one of [DASHES]. Linear in the text.
 */
internal object NumberRuns {
    /** [text] as the scanner reads it, one char for one, so a range in it is a range of [text]. */
    fun plain(text: String): String = String(CharArray(text.length) { plainChar(text[it]) })

    /**
     * The digit runs of [plain] (a [NumberRuns.plain] copy), each as its groups in order: digit
     * groups joined by gaps, the last group maybe ending in an id check `X`. A run is not glued to
     * an ASCII letter or digit: a last group glued to one is left out of the run. Scanned by hand
     * in one pass, since a regex repeating a group overflows the stack on long runs.
     */
    fun of(plain: String): List<List<IntRange>> {
        val runs = mutableListOf<List<IntRange>>()
        var at = 0
        while (at < plain.length) {
            val starts = plain[at] in '0'..'9' && (at == 0 || !plain[at - 1].isAsciiAlphanumeric())
            if (starts) {
                val (groups, end) = runFrom(plain, at)
                if (groups.isNotEmpty()) runs += groups
                at = end
            } else {
                at++
            }
        }
        return runs
    }

    /** The groups of the run whose first digit is at [start], and the end of its last digit group. */
    private fun runFrom(
        plain: String,
        start: Int,
    ): Pair<List<IntRange>, Int> {
        val groups = mutableListOf<IntRange>()
        var first = start
        var end = digitsEnd(plain, first)
        var next = gapEnd(plain, end)
        while (next != null) {
            groups += first until end
            first = next
            end = digitsEnd(plain, first)
            next = gapEnd(plain, end)
        }
        val stop = if (end < plain.length && plain[end] == 'X') end + 1 else end
        if (stop == plain.length || !plain[stop].isAsciiAlphanumeric()) groups += first until stop
        return groups to end
    }

    private fun digitsEnd(
        plain: String,
        from: Int,
    ): Int {
        var at = from
        while (at < plain.length && plain[at] in '0'..'9') at++
        return at
    }

    /** Where the digit group after the gap at [from] starts, or `null` when no gap joins one there. */
    private fun gapEnd(
        plain: String,
        from: Int,
    ): Int? {
        var at = from
        var dashes = 0
        while (at < plain.length && (plain[at] == ' ' || plain[at] == '-')) {
            if (plain[at] == '-') dashes++
            at++
        }
        val joins = at > from && dashes <= MAX_GAP_DASHES && at < plain.length && plain[at] in '0'..'9'
        return if (joins) at else null
    }

    private fun Char.isAsciiAlphanumeric(): Boolean = this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'

    /**
     * [c] as the number scanner sees it: fullwidth digits, `X` and `#` as ASCII, `x` as `X`, the
     * Unicode spaces and invisible format characters as a space, the dashes as `-`; one char for one.
     */
    private fun plainChar(c: Char): Char =
        when {
            c in '０'..'９' -> '0' + (c - '０')
            c == 'x' || c == 'ｘ' || c == 'Ｘ' -> 'X'
            c == '＃' -> '#'
            c.isUnicodeSpace() || c.category == CharCategory.FORMAT -> ' '
            c in DASHES -> '-'
            else -> c
        }

    /** Hyphens and dashes besides `-`: U+2010–U+2014, U+2212 minus sign, U+FE63, U+FF0D. */
    private const val DASHES = "\u2010\u2011\u2012\u2013\u2014\u2212\uFE63\uFF0D"

    /** Most dashes in one gap between digit groups; a third one ends the number. */
    private const val MAX_GAP_DASHES = 2
}
