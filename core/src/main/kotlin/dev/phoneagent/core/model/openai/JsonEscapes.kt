package dev.phoneagent.core.model.openai

/**
 * Decoding of JSON string escapes in text that need not be JSON (model-layer §2). Call arguments
 * that are cut ([dev.phoneagent.core.model.StopReason.MAX_TOKENS]) or broken do not parse, yet the
 * tool layer decodes their escapes all the same, so a secret written with escapes in them (`\u0070`
 * for `p`, `\/` for `/`) is checked in this decoded form. The result is only matched against; it
 * never replaces the model's text.
 *
 * WP04 keeps a copy with the same name, rules and test data; both move into `core.model` with the
 * shared transport layer.
 */
internal object JsonEscapes {
    private const val BACKSLASH = '\\'
    private const val HEX_DIGITS = 4

    /** An escape `\uXXXX`: the backslash, the `u` and four hex digits. */
    private const val UNICODE_ESCAPE = 2 + HEX_DIGITS

    /** The characters `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r` and `\t` stand for. */
    private val SIMPLE =
        mapOf(
            '"' to '"',
            BACKSLASH to BACKSLASH,
            '/' to '/',
            'b' to Char(BACKSPACE),
            'f' to Char(FORM_FEED),
            'n' to Char(LINE_FEED),
            'r' to Char(CARRIAGE_RETURN),
            't' to Char(TAB),
        )

    /**
     * [raw] with its escapes decoded in one pass from left to right: `\uXXXX` (a surrogate pair as
     * two escapes) and the escapes of [SIMPLE]. An escaped backslash is decoded first, so
     * `\\u0070` becomes a backslash and `u0070`. Anything else stays as it is: an unknown escape,
     * an incomplete `\u`, a surrogate escape that is not half of a pair, a backslash at the end.
     */
    fun lenientDecode(raw: String): String {
        if (BACKSLASH !in raw) return raw
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            at = decodeAt(raw, at, out)
        }
        return out.toString()
    }

    /** Appends what [raw] holds at [at] (one character or one escape) to [out]; returns where the next one starts. */
    private fun decodeAt(
        raw: String,
        at: Int,
        out: StringBuilder,
    ): Int {
        val next = raw.getOrNull(at + 1)
        val simple = next?.let(SIMPLE::get)
        return when {
            raw[at] != BACKSLASH || next == null -> {
                out.append(raw[at])
                at + 1
            }

            simple != null -> {
                out.append(simple)
                at + 2
            }

            next == 'u' -> {
                unicode(raw, at, out)
            }

            else -> {
                out.append(BACKSLASH)
                at + 1
            }
        }
    }

    /** The escape `\u…` at [at]: decoded when it is a whole code unit or pair, else its backslash is kept. */
    private fun unicode(
        raw: String,
        at: Int,
        out: StringBuilder,
    ): Int {
        val unit = unitAt(raw, at)
        val low = unit?.takeIf { it.isHighSurrogate() }?.let { unitAt(raw, at + UNICODE_ESCAPE) }
        return when {
            unit == null || unit.isLowSurrogate() || (unit.isHighSurrogate() && low?.isLowSurrogate() != true) -> {
                out.append(BACKSLASH)
                at + 1
            }

            low != null -> {
                out.append(unit).append(low)
                at + 2 * UNICODE_ESCAPE
            }

            else -> {
                out.append(unit)
                at + UNICODE_ESCAPE
            }
        }
    }

    /** The code unit of a whole `\uXXXX` escape at [at], or `null`. */
    private fun unitAt(
        raw: String,
        at: Int,
    ): Char? {
        val digits = at + 2
        val whole =
            raw.startsWith("\\u", at) &&
                digits + HEX_DIGITS <= raw.length &&
                (digits until digits + HEX_DIGITS).all { raw[it].isHexDigit() }
        return if (whole) Char(raw.substring(digits, digits + HEX_DIGITS).toInt(HEX)) else null
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private const val HEX = 16
    private const val BACKSPACE = 8
    private const val TAB = 9
    private const val LINE_FEED = 10
    private const val FORM_FEED = 12
    private const val CARRIAGE_RETURN = 13
}
