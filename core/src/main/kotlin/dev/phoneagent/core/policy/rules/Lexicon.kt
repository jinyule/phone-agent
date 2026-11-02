package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind

/** A vocabulary match: the effect class and the entry that matched. */
internal data class Hit(
    val kind: IntentKind,
    val matched: String,
)

/**
 * Matches one text against [Words] under the length limits of [tuning]; knows nothing about
 * screens. Lengths are measured on [Text.words]: amounts, counts and bracketed notes do not make
 * a pay button "content".
 */
internal class Lexicon(
    private val tuning: PolicyTuning,
) {
    /** The risk a label names, or `null`. Strong words count in labels up to [PolicyTuning.labelMax]. */
    fun classify(label: String): Hit? = match(label, tuning.labelMax)

    /** The risk a dialog text names, for the generic button in that dialog ([PolicyTuning.contextMax]). */
    fun inContext(text: String): Hit? = match(text, tuning.contextMax)

    /**
     * The risk the name of a control names: as [classify], and a strong word the name starts with
     * counts at any length ("确认支付并同意…").
     */
    fun ofControl(name: String): Hit? = classify(name) ?: Phrases.leading(name)

    /** "确定", "OK" and the like: the dialog decides what they do. */
    fun isGeneric(label: String): Boolean = Text.bare(label) in Words.GENERIC

    /** "关闭", "跳过", "Not now" and the like: leaves without doing anything. */
    fun isEscape(label: String): Boolean = Text.bare(label) in Words.ESCAPES

    private fun match(
        text: String,
        max: Int,
    ): Hit? =
        Phrases.exact(text) ?: (if (Phrases.length(text) <= max) Phrases.strong(text) else null)
            ?: Phrases.english(text)
}

/** The matching of [Words] lists, without length limits. Every function takes the raw text. */
internal object Phrases {
    /** An [Words.EXACT] word as the whole label, optionally after a [Words.PREFIXES] entry. */
    fun exact(label: String): Hit? {
        val bare = Text.bare(label)
        return Words.EXACT
            .firstOrNull { (word, _) -> Words.PREFIXES.any { bare == it + word } }
            ?.let { (word, kind) -> Hit(kind, word) }
    }

    /** How long [label] is for the length limits: its [Text.words] without names that are not actions. */
    fun length(label: String): Int = clean(Text.words(label)).length

    /** The first [Words.STRONG] word that acts somewhere in [label] (not as a status), unless vetoed. */
    fun strong(label: String): Hit? {
        val text = clean(Text.words(label))
        val found =
            Words.STRONG.firstOrNull { (word, kind) ->
                generateSequence(text.indexOf(word)) { text.indexOf(word, it + 1) }
                    .takeWhile { it >= 0 }
                    .any { at -> isAction(text, word, at, kind) } &&
                    !vetoed(label, kind)
            }
        return found?.let { (word, kind) -> Hit(kind, word) }
    }

    /** A [Words.STRONG] word acting right at the start of [label], after an optional [Words.PREFIXES] entry. */
    fun leading(label: String): Hit? {
        val text = clean(Text.words(label))
        val found =
            Words.STRONG.firstOrNull { (word, kind) ->
                Words.PREFIXES.any { text.startsWith(it + word) && isAction(text, word, it.length, kind) } &&
                    !vetoed(label, kind)
            }
        return found?.let { (word, kind) -> Hit(kind, word) }
    }

    /**
     * English ([Text.phrase] form): the label starts with an [Words.ENGLISH_PREFIX] phrase at a
     * word boundary (any non-Latin letter ends a word: "Send消息"), or is an [Words.ENGLISH_EXACT]
     * phrase. Letters written apart ("s e n d") are read together.
     */
    fun english(label: String): Hit? {
        val spaced = Text.phrase(label).trimEnd { !it.isLetterOrDigit() }
        val letters = spaced.split(' ')
        val text = if (letters.size > 1 && letters.all { it.length == 1 }) letters.joinToString("") else spaced
        val found =
            Words.ENGLISH_PREFIX.firstOrNull { (phrase, _) -> text.startsWith(phrase) && !latinAt(text, phrase.length) }
                ?: Words.ENGLISH_EXACT.firstOrNull { (phrase, _) -> text == phrase }
        return found?.takeUnless { vetoed(label, it.second) }?.let { (phrase, kind) -> Hit(kind, phrase) }
    }

    private fun latinAt(
        text: String,
        at: Int,
    ): Boolean = at < text.length && Character.UnicodeScript.of(text[at].code) == Character.UnicodeScript.LATIN

    /** [words] without names that contain a strong word but are not actions ("支付宝", "支持微信支付"). */
    private fun clean(words: String): String =
        Words.NOT_ACTIONS.fold(Words.PAYMENT_ADVERT.replace(words, "")) { acc, name -> acc.replace(name, "") }

    /** [word] at [at] in [text] is an action, not a status ("待付款", "付款成功", "付款记录"). */
    private fun isAction(
        text: String,
        word: String,
        at: Int,
        kind: IntentKind,
    ): Boolean {
        val before = if (at > 0) text[at - 1] else ' '
        val rest = text.substring(at + word.length).dropWhile { !it.isLetterOrDigit() }
        val statuses = Words.STATUS_AFTER + Words.STATUS_AFTER_KIND[kind].orEmpty()
        return before !in Words.STATUS_BEFORE && statuses.none { rest.startsWith(it) }
    }

    /** [label] is not of [kind] after all: a code sent to the user's own phone, or a harmless delete. */
    private fun vetoed(
        label: String,
        kind: IntentKind,
    ): Boolean =
        when {
            kind == IntentKind.OUTWARD -> {
                Words.SELF_CODE.matches(Text.words(label).trimEnd { !it.isLetterOrDigit() }) ||
                    Words.SELF_CODE_EN.matches(Text.phrase(label).trimEnd { !it.isLetterOrDigit() })
            }

            kind == IntentKind.DELETE -> {
                Words.HARMLESS_DELETES.any { it in Text.compact(label) }
            }

            else -> {
                false
            }
        }
}
