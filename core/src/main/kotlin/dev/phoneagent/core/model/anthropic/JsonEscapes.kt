package dev.phoneagent.core.model.anthropic

/**
 * JSON string escapes decoded where the text need not be JSON: call arguments that are cut off or
 * invalid still reach a step that decodes them as JSON (model-layer §2), so [EchoGuard] matches
 * them decoded. The result is only matched, never stored or shown.
 *
 * Same name, rules and test data as the OpenAI adapters' copy (WP03); keep them in step.
 */
internal object JsonEscapes {
    /**
     * [raw] with its escapes decoded in one pass from the left: `\uXXXX` (a high surrogate followed
     * by an escaped low one as their pair), `\/`, `\"`, `\\`, `\n`, `\t`, `\r`, `\b` and `\f`.
     * Anything else stays as written: an unknown escape, a `\u` without four hex digits, a lone
     * surrogate, a backslash at the end. The backslash `\\` decodes to starts nothing, so
     * `\\u0070` decodes once, to a backslash and `u0070`. Text without a backslash is returned as
     * it is.
     */
    fun lenientDecode(raw: String): String {
        if (BACKSLASH !in raw) return raw
        val decoded = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            val next = raw.getOrNull(at + 1)
            val simple = SIMPLE[next]
            at =
                when {
                    raw[at] != BACKSLASH || next == null -> decoded.keep(raw[at], at)
                    simple != null -> decoded.append(simple).let { at + 2 }
                    next == 'u' -> unicode(raw, at, decoded)
                    else -> decoded.keep(BACKSLASH, at)
                }
        }
        return decoded.toString()
    }

    /** Decodes the `\u` escape at [at] into [decoded], or keeps its backslash; where the pass goes on. */
    private fun unicode(
        raw: String,
        at: Int,
        decoded: StringBuilder,
    ): Int {
        val unit = hex(raw, at + 2)?.toChar() ?: return decoded.keep(BACKSLASH, at)
        val low = if (raw.startsWith(U_ESCAPE, at + ESCAPE_LENGTH)) hex(raw, at + ESCAPE_LENGTH + 2)?.toChar() else null
        return when {
            unit.isHighSurrogate() && low != null && low.isLowSurrogate() -> {
                decoded.append(unit).append(low)
                at + 2 * ESCAPE_LENGTH
            }

            unit.isSurrogate() -> {
                decoded.append(raw, at, at + ESCAPE_LENGTH)
                at + ESCAPE_LENGTH
            }

            else -> {
                decoded.append(unit)
                at + ESCAPE_LENGTH
            }
        }
    }

    /** The four hex digits at [from] as a number, or `null` when there are not four. */
    private fun hex(
        raw: String,
        from: Int,
    ): Int? {
        val digits = raw.substring(from, minOf(from + HEX_DIGITS, raw.length))
        return if (digits.length == HEX_DIGITS && digits.all { it in HEX }) digits.toInt(HEX_RADIX) else null
    }

    /** Appends [char], the one at [at], as written; the pass goes on after it. */
    private fun StringBuilder.keep(
        char: Char,
        at: Int,
    ): Int {
        append(char)
        return at + 1
    }

    private const val BACKSLASH = '\\'
    private const val U_ESCAPE = "\\u"
    private const val HEX = "0123456789abcdefABCDEF"
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 4

    /** `\uXXXX`. */
    private const val ESCAPE_LENGTH = 2 + HEX_DIGITS

    /** The escapes of one character after the backslash. */
    private val SIMPLE =
        mapOf(
            '/' to '/',
            '"' to '"',
            '\\' to '\\',
            'n' to '\n',
            't' to '\t',
            'r' to '\r',
            'b' to '\b',
            'f' to '\u000C',
        )
}
