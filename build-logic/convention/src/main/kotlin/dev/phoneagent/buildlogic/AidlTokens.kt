package dev.phoneagent.buildlogic

/**
 * Splits AIDL text into tokens: words (`[A-Za-z0-9_]+`), string and char literals (kept verbatim,
 * escapes included) and single punctuation characters. Whitespace and comments are dropped, so
 * `int[] f()=1;` and `int [] f ( ) = 1 ;` give the same tokens, while text inside a literal,
 * comment markers included, is never mistaken for a comment.
 */
object AidlTokens {
    /** Tokens of [text]; throws [IllegalArgumentException] naming the line of an unterminated comment or literal. */
    fun of(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = tokenEnd(text, i)
            val token = text.substring(i, end)
            if (!token.isBlank() && !token.startsWith("//") && !token.startsWith("/*")) tokens += token
            i = end
        }
        return tokens
    }

    /** End (exclusive) of the token, comment or whitespace run starting at [start]. */
    private fun tokenEnd(
        text: String,
        start: Int,
    ): Int {
        val c = text[start]
        return when {
            c.isWhitespace() -> start + 1
            text.startsWith("//", start) -> text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.startsWith("/*", start) -> blockCommentEnd(text, start)
            c == '"' || c == '\'' -> literalEnd(text, start)
            c.isWordPart() -> (start until text.length).firstOrNull { !text[it].isWordPart() } ?: text.length
            else -> start + 1
        }
    }

    private fun blockCommentEnd(
        text: String,
        start: Int,
    ): Int {
        val close = text.indexOf("*/", start + 2)
        require(close >= 0) { "unterminated /* comment at line ${lineOf(text, start)}" }
        return close + 2
    }

    /** A literal ends at its unescaped closing quote, on the same line. */
    private fun literalEnd(
        text: String,
        start: Int,
    ): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != quote && text[i] != '\n') {
            i += if (text[i] == '\\') 2 else 1
        }
        require(i < text.length && text[i] == quote) { "unterminated $quote literal at line ${lineOf(text, start)}" }
        return i + 1
    }

    private fun Char.isWordPart(): Boolean = this == '_' || isLetterOrDigit()

    private fun lineOf(
        text: String,
        offset: Int,
    ): Int = 1 + text.substring(0, offset).count { it == '\n' }
}
