package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ScreenSnapshot
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

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
     * The risk a control's own label names: as [classify], and a strong word anywhere in it counts
     * at any length ("我已阅读并同意服务协议现在支付…"): a control's label says what it does.
     */
    fun ofControl(label: String): Hit? = classify(label) ?: Phrases.strong(label)

    /** A strong word [text] starts with, at any length ("确认支付并同意…" read from a row's texts joined). */
    fun leading(text: String): Hit? = Phrases.leading(text)

    /** "确定", "OK" and the like: the dialog decides what they do. Reads [read] of [label]. */
    fun isGeneric(label: String): Boolean = Text.bare(read(label)) in Words.GENERIC

    /** "关闭", "跳过", "Not now" and the like: leaves without doing anything. A label longer than [read] is none. */
    fun isEscape(label: String): Boolean = whole(label) && Text.bare(label) in Words.ESCAPES

    /**
     * What risk checks read of a control's label: its first [PolicyTuning.labelRead] characters.
     * The label of a control without a name is the text inside it joined ([Elements.label]),
     * which may hold a whole page; a strong word in any one of those texts still counts, as each
     * text is read by itself too.
     */
    fun read(label: String): String = label.take(tuning.labelRead)

    /** [label] is read whole ([read]): only such a label can be one word that exempts a control. */
    fun whole(label: String): Boolean = label.length <= tuning.labelRead

    private val classified = TextMemo(::classify)
    private val controls = TextMemo(::ofControl)

    /** [classify], read once per distinct text on [screen]. */
    fun classify(
        text: String,
        screen: ScreenSnapshot,
    ): Hit? = classified.of(text, screen)

    /** [ofControl], read once per distinct text on [screen]. */
    fun ofControl(
        text: String,
        screen: ScreenSnapshot,
    ): Hit? = controls.of(text, screen)

    private fun match(
        text: String,
        max: Int,
    ): Hit? =
        Phrases.exact(text) ?: (if (Phrases.length(text) <= max) Phrases.strong(text) else null)
            ?: Phrases.english(text)
}

/**
 * What [read] finds in each distinct text, computed once while the screen stays the same object
 * ([PerScreen]); [read] is a pure function of the text.
 */
private class TextMemo(
    private val read: (String) -> Hit?,
) {
    private val memo = PerScreen { ConcurrentHashMap<String, Optional<Hit>>() }

    fun of(
        text: String,
        screen: ScreenSnapshot,
    ): Hit? = memo.of(screen).computeIfAbsent(text) { Optional.ofNullable(read(it)) }.orElse(null)
}

/**
 * The matching of [Words] lists, without length limits. Every function takes the raw text.
 *
 * Adapted from the label matching of Mika's `IrreversibleDetector` (strong words at each of
 * their occurrences unless they read as a status, whole words after prefixes, English phrases at
 * a word boundary, names and adverts removed first; Copyright 2026 wangzan101, Apache-2.0).
 * Modified by PhoneAgent contributors: traditional characters read as simplified, zero-width
 * characters dropped, English letters written apart read together, status words that depend on
 * the kind, vetoes limited to codes sent to the user's own phone, amounts left out of whole
 * words, and status checks in time in proportion to the text. See NOTICE.
 */
internal object Phrases {
    /**
     * An [Words.EXACT] word as the whole label, optionally after a [Words.PREFIXES] entry; an
     * amount with a currency mark around it does not count ("订阅 ¥20", "开通会员 ¥15/月"), a
     * count does ("评论(23)" is an entry).
     */
    fun exact(label: String): Hit? {
        val forms = setOf(Text.bare(label), Text.bare(Text.withoutMoney(label)))
        val first =
            forms
                .flatMap { form ->
                    Words.PREFIXES.filter(form::startsWith).mapNotNull { EXACT_RANKS[form.drop(it.length)] }
                }.minOrNull()
        return first?.let { Words.EXACT[it].let { (word, kind) -> Hit(kind, word) } }
    }

    /** The place of each [Words.EXACT] word in its list: the first one listed wins. */
    private val EXACT_RANKS: Map<String, Int> = Words.EXACT.withIndex().associate { (i, entry) -> entry.first to i }

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

    /**
     * [word] at [at] in [text] is an action, not a status ("待付款", "付款成功", "付款记录"). Looks
     * only at the character before and the text right after the punctuation that follows, so
     * checking every occurrence of a word costs time in proportion to [text].
     */
    private fun isAction(
        text: String,
        word: String,
        at: Int,
        kind: IntentKind,
    ): Boolean {
        val before = if (at > 0) text[at - 1] else ' '
        var rest = at + word.length
        while (rest < text.length && !text[rest].isLetterOrDigit()) rest++
        val statuses = Words.STATUS_AFTER + Words.STATUS_AFTER_KIND[kind].orEmpty()
        return before !in Words.STATUS_BEFORE && statuses.none { text.startsWith(it, rest) }
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
