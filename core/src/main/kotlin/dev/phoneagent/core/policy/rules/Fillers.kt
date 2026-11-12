package dev.phoneagent.core.policy.rules

/**
 * [part] without the [Words.ENGLISH_FILLER] words it starts with, each with the characters after
 * it up to the next ASCII letter, digit or `_` ("also, also delete" → "delete"). A filler counts
 * only as a whole word: right after it is the end, or a character that is none of those and no
 * non-spacing mark ("alsofoo", "also_x", "also1" stay). The same as `^(?:also\b\W*)+` on the
 * JVM, in one pass without recursion, so any number of fillers is safe (policy-rules.md §8).
 */
internal fun withoutFillers(part: String): String {
    val filler = Words.ENGLISH_FILLER
    var at = 0
    while (part.startsWith(filler, at) && endsWord(part, at + filler.length)) {
        at += filler.length
        while (at < part.length && !isWordChar(part[at])) at++
    }
    return part.substring(at)
}

/**
 * Whether a word ending in an ASCII letter right before [at] in [text] ends there, as `\b` reads
 * it on the JVM: [at] is the end, or what is at [at] is no [isWordChar] and no non-spacing mark.
 */
private fun endsWord(
    text: String,
    at: Int,
): Boolean =
    at == text.length ||
        (!isWordChar(text[at]) && Character.getType(text.codePointAt(at)) != Character.NON_SPACING_MARK.toInt())

/** An ASCII letter, digit or `_`: what `\w` is on the JVM. */
private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
