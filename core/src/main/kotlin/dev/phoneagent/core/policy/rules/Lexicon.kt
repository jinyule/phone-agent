package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind

/** A vocabulary match: the effect class and the entry that matched. */
internal data class Hit(
    val kind: IntentKind,
    val matched: String,
)

/** Matches one label against [Words]; knows nothing about screens. */
internal object Lexicon {
    /** The risk a label names, or `null`. Strong words count only in texts up to [maxLength] (compact). */
    fun classify(
        label: String,
        maxLength: Int = Words.LABEL_MAX,
    ): Hit? = exact(Text.bare(label)) ?: strong(Text.compact(label), maxLength) ?: english(Text.spaced(label))

    /** "确定", "OK" and the like: the dialog decides what they do. */
    fun isGeneric(label: String): Boolean = Text.bare(label) in Words.GENERIC

    /** "关闭", "跳过", "Not now" and the like: leaves without doing anything. */
    fun isEscape(label: String): Boolean = Text.bare(label) in Words.ESCAPES

    private fun exact(bare: String): Hit? =
        Words.EXACT
            .firstOrNull { (word, _) -> Words.PREFIXES.any { bare == it + word } }
            ?.let { (word, kind) -> Hit(kind, word) }

    private fun strong(
        compact: String,
        maxLength: Int,
    ): Hit? {
        val text =
            Words.NOT_ACTIONS.fold(
                Words.PAYMENT_ADVERT.replace(compact, ""),
            ) { acc, name -> acc.replace(name, "") }
        val found =
            Words.STRONG.firstOrNull { (word, kind) ->
                text.length <= maxLength && actionIn(text, word) && !vetoed(compact, kind)
            }
        return found?.let { (word, kind) -> Hit(kind, word) }
    }

    private fun english(spaced: String): Hit? {
        val text = spaced.trimEnd { !it.isLetterOrDigit() }
        val found =
            Words.ENGLISH_PREFIX.firstOrNull { (phrase, _) ->
                text.startsWith(phrase) &&
                    !text.continuesWord(phrase.length)
            }
                ?: Words.ENGLISH_EXACT.firstOrNull { (phrase, _) -> text == phrase }
        return found?.takeUnless { vetoed(Text.compact(text), it.second) }?.let { (phrase, kind) -> Hit(kind, phrase) }
    }

    private fun String.continuesWord(at: Int): Boolean = at < length && this[at].isLetter()

    /** [word] occurs in [text] as an action somewhere, not as a status. */
    private fun actionIn(
        text: String,
        word: String,
    ): Boolean =
        generateSequence(text.indexOf(word)) { text.indexOf(word, it + 1) }
            .takeWhile { it >= 0 }
            .any { at -> isAction(text, word, at) }

    private fun isAction(
        text: String,
        word: String,
        at: Int,
    ): Boolean {
        val before = if (at > 0) text[at - 1] else ' '
        val rest = text.substring(at + word.length).dropWhile { !it.isLetterOrDigit() }
        return before !in Words.STATUS_BEFORE && Words.STATUS_AFTER.none { rest.startsWith(it) }
    }

    private fun vetoed(
        compact: String,
        kind: IntentKind,
    ): Boolean = Words.VETO[kind].orEmpty().any { it in compact }
}
