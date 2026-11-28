package dev.phoneagent.core.model.anthropic

/** [text] that counts how often each of its characters is read; views share the count. */
internal class CountingChars(
    private val text: String,
    private val reads: IntArray = IntArray(text.length),
    private val offset: Int = 0,
    override val length: Int = text.length,
) : CharSequence {
    override fun get(index: Int): Char {
        if (index !in 0 until length) throw IndexOutOfBoundsException("index $index, length $length")
        reads[offset + index]++
        return text[offset + index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = CountingChars(text, reads, offset + startIndex, endIndex - startIndex)

    /** The characters, each read once. */
    override fun toString(): String = CharArray(length) { get(it) }.concatToString()

    /** The most reads of any one character. */
    fun most(): Int = reads.maxOrNull() ?: 0
}
