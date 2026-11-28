package dev.phoneagent.privileged

/**
 * Whether [text] is a Java package name: two or more segments joined by `.`, each an ASCII letter
 * and then ASCII letters, digits and `_`; what `[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+`
 * matches. Checked without a regex, in time in proportion to [text] and constant stack, however
 * many segments it has (a regex repeating the group recurses once per segment). The same check as
 * core's `isPackageName`, copied: this module does not depend on core.
 */
internal fun isPackageName(text: String): Boolean {
    val segments = text.split('.')
    return segments.size >= 2 && segments.all(::isSegment)
}

private fun isSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment[0].isAsciiLetter() &&
        segment.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
