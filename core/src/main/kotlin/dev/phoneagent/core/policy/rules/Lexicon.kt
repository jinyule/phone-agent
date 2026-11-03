package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.ports.ScreenSnapshot
import java.util.EnumMap
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * A vocabulary match: the effect class and the entry that matched. Where several matches are
 * read as one ([firstMax]), the most severe is kept, and [sends] tells whether any of them sends.
 *
 * @property sends the text also sends something to someone (policy-rules.md §6.3): an OUTWARD
 *   word acts in it, or a payment that also sends ([Words.SENDING_SPENDS]), whatever [kind] is.
 */
internal data class Hit(
    val kind: IntentKind,
    val matched: String,
    val sends: Boolean = kind == IntentKind.OUTWARD,
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
    fun classify(label: String): Hit? = match(label, tuning.labelMax, complete = true, pauses = false)

    /**
     * The risk a dialog text names, for the generic button in that dialog ([PolicyTuning.contextMax]).
     * Counted as a dialog text read ([Work.dialog]).
     */
    fun inContext(text: String): Hit? {
        Work.dialog.increment()
        return match(text, tuning.contextMax, complete = true, pauses = true)
    }

    /**
     * The risk a control's own label names: as [classify], and a strong word anywhere in it counts
     * at any length ("我已阅读并同意服务协议现在支付…"): a control's label says what it does. In
     * English, each part the label joins ("Send and delete") is read. Every reading counts, the
     * most severe deciding, and it [Hit.sends] when any does: an English part that sends hides no
     * strong word that pays ("Photos and send 立即支付全部款项").
     */
    fun ofControl(label: String): Hit? =
        listOf(
            classify(label),
            Phrases.strong(label, complete = true),
            Phrases.english(label, true, partMax = null, pauses = true),
        ).fold(null, ::firstMax)

    /**
     * The risk a control's label names as risk checks read it ([read]): the more severe of what
     * [classify] finds there and a strong word it starts with at any length ("确认支付并同意…" read
     * from a row's texts joined), the first among equals. An exemption (a code sent to the user's
     * own phone, a harmless delete) holds only on a label read whole ([whole]); in one cut short,
     * the words read count as they are (policy-rules.md §0).
     */
    fun ofLabel(label: String): Hit? {
        val read = read(label)
        val complete = whole(label)
        return firstMax(match(read, tuning.labelMax, complete, pauses = false), Phrases.leading(read, complete))
    }

    /** "确定", "OK" and the like: the dialog decides what they do. Reads [read] of [label]. */
    fun isGeneric(label: String): Boolean = Text.bare(read(label)) in Words.GENERIC

    /** "关闭", "跳过", "Not now" and the like: leaves without doing anything. A label longer than [read] is none. */
    fun isEscape(label: String): Boolean = whole(label) && Text.bare(label) in Words.ESCAPES

    /**
     * What risk checks read of a control's label: its longest start of at most
     * [PolicyTuning.labelRead] normalized characters ([Text.length]). The label of a control
     * without a name is the text inside it joined ([Elements.label]), which may hold a whole page;
     * a strong word in any one of those texts still counts, as each text is read by itself too.
     */
    fun read(label: String): String = label.substring(0, Text.prefix(label, tuning.labelRead))

    /** [label] is read whole ([read]): only such a label can be one word that exempts a control. */
    fun whole(label: String): Boolean = Text.prefix(label, tuning.labelRead) == label.length

    private val classified = TextMemo(::classify)
    private val controls = TextMemo(::ofControl)
    private val labels = TextMemo(::ofLabel)

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

    /** [ofLabel], read once per distinct label on [screen]. */
    fun ofLabel(
        label: String,
        screen: ScreenSnapshot,
    ): Hit? = labels.of(label, screen)
}

/**
 * The [Words.ENGLISH_PREFIX] phrase [text] starts with at a word boundary, else, when [text] is
 * the [whole] label and not one part of it, the [Words.ENGLISH_EXACT] phrase it is ([Phrases.english]).
 */
private fun englishPhrase(
    text: String,
    whole: Boolean,
): Pair<String, IntentKind>? =
    Words.ENGLISH_PREFIX.firstOrNull { (phrase, _) -> text.startsWith(phrase) && !latinAt(text, phrase.length) }
        ?: if (whole) Words.ENGLISH_EXACT.firstOrNull { (phrase, _) -> text == phrase } else null

/**
 * [text] with the sign of each word made of Latin letters and one [Words.ENGLISH_SIGNS] sign
 * between them spaced out ("send&delete" → "send & delete"), so [Words.ENGLISH_JOINERS] splits
 * there. The punctuation around a word is left out of that ("send+delete? this…"), and a sign
 * written several times in a row counts once ("send&&delete", and "send && delete" too). A sign
 * at the start or end of a word is part of that punctuation and stays ("&pay=", left of
 * "?x=1&pay=2" once numbers are read as spaces; "c++"), and so does a sign in any other word
 * ("example.com/buy."). One pass over [text].
 */
private fun spacedSigns(text: String): String = WORD.replace(text) { spacedSign(it.value) }

private fun spacedSign(word: String): String {
    val start = word.indexOfFirst(Char::isLetterOrDigit)
    if (start < 0) return signOnce(word)
    val end = word.indexOfLast(Char::isLetterOrDigit) + 1
    val core = word.substring(start, end)
    val sign = joiningSign(core)
    val spaced = if (sign == null) core else core.replace("$sign", " $sign ")
    return word.substring(0, start) + spaced + word.substring(end)
}

/** The sign of [core] when it is Latin letters with one [Words.ENGLISH_SIGNS] sign between them, else `null`. */
private fun joiningSign(core: String): Char? {
    val sign = core.firstOrNull { it in Words.ENGLISH_SIGNS }
    val between = isLatinLetter(core.first()) && isLatinLetter(core.last())
    return sign?.takeIf { between && core.all { c -> c == sign || isLatinLetter(c) } }
}

/** A word that is one [Words.ENGLISH_SIGNS] sign written several times ("&&") is that sign once; any other stays. */
private fun signOnce(word: String): String {
    val sign = word.first()
    return if (sign in Words.ENGLISH_SIGNS && word.all { it == sign }) "$sign" else word
}

/** A run of characters other than spaces. */
private val WORD = Regex("\\S+")

private fun isLatinLetter(c: Char): Boolean =
    c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

/** [part] without the punctuation and spaces around it. */
private fun trimmed(part: String): String = part.trim { !it.isLetterOrDigit() }

private fun latinAt(
    text: String,
    at: Int,
): Boolean = at < text.length && Character.UnicodeScript.of(text[at].code) == Character.UnicodeScript.LATIN

/**
 * What [text] names under the length limit [max]: strong words count only in a text that short,
 * English joined actions split only where each part is ([Phrases.english]), at [pauses] too
 * when told; exemptions only when [text] is [complete]. Every reading counts, the most severe
 * deciding, and it [Hit.sends] when any does: "发送 and delete" deletes, and sends; "删除 and pay" pays.
 */
private fun match(
    text: String,
    max: Int,
    complete: Boolean,
    pauses: Boolean,
): Hit? {
    val strong = if (Phrases.length(text) <= max) Phrases.strong(text, complete) else null
    return listOf(Phrases.exact(text), strong, Phrases.english(text, complete, partMax = max, pauses = pauses))
        .fold(null, ::firstMax)
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
 * The matching of [Words] lists, without length limits. Every function takes the raw text; one
 * that may find an exemption ([vetoed]) applies it only when told the text is complete, never
 * to a part of a label (policy-rules.md §0).
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
        return first?.let { Words.EXACT[it].let { (word, kind) -> Hit(kind, word, sends(word, kind)) } }
    }

    /** The place of each [Words.EXACT] word in its list: the first one listed wins. */
    private val EXACT_RANKS: Map<String, Int> = Words.EXACT.withIndex().associate { (i, entry) -> entry.first to i }

    /** How long [label] is for the length limits: its [Text.words] without names that are not actions. */
    fun length(label: String): Int = clean(Text.words(label)).length

    /**
     * The first [Words.STRONG] word that acts somewhere in [label] (not as a status), unless
     * vetoed; a [label] that is not [complete] is never vetoed. It [Hit.sends] when any word
     * that acts there sends: "发送并删除原图" deletes, and sends.
     */
    fun strong(
        label: String,
        complete: Boolean,
    ): Hit? {
        val text = clean(Text.words(label))
        val vetoes = Vetoes(label)
        val acting =
            Words.STRONG.filter { (word, kind) ->
                generateSequence(text.indexOf(word)) { text.indexOf(word, it + 1) }
                    .takeWhile { it >= 0 }
                    .any { at -> isAction(text, word, at, kind) } &&
                    !(complete && vetoes.of(kind))
            }
        return acting.firstOrNull()?.let { (word, kind) ->
            Hit(kind, word, acting.any { (other, its) -> sends(other, its) })
        }
    }

    /**
     * A [Words.STRONG] word acting right at the start of [label], after an optional [Words.PREFIXES]
     * entry, unless vetoed; a [label] that is not [complete] is never vetoed.
     */
    fun leading(
        label: String,
        complete: Boolean,
    ): Hit? {
        val text = clean(Text.words(label))
        val vetoes = Vetoes(label)
        val found =
            Words.STRONG.firstOrNull { (word, kind) ->
                Words.PREFIXES.any { text.startsWith(it + word) && isAction(text, word, it.length, kind) } &&
                    !(complete && vetoes.of(kind))
            }
        return found?.let { (word, kind) -> Hit(kind, word, sends(word, kind)) }
    }

    /**
     * English ([Text.phrase] form): the label starts with an [Words.ENGLISH_PREFIX] phrase at a
     * word boundary (any non-Latin letter ends a word: "Send消息"), or is an [Words.ENGLISH_EXACT]
     * phrase. Letters written apart ("s e n d") are read together. Each part the label joins with
     * [Words.ENGLISH_JOINERS] ("Delete and send"), and with [pauses] [Words.ENGLISH_PAUSES] too,
     * without a word left over from the joiner ([Words.ENGLISH_FILLERS]: "and also delete"), is
     * read by the phrase it starts with, the most severe deciding, and it sends when any part does;
     * an exact phrase is only ever the whole label ("Invite and earn" invites no one). The label is
     * split only where every part is at most [partMax] long ([length]; `null`: any length), else
     * read whole by its start: a long sentence is not split. A [label] that is not [complete] is
     * never vetoed; a vetoed one is vetoed whole.
     */
    fun english(
        label: String,
        complete: Boolean,
        partMax: Int?,
        pauses: Boolean,
    ): Hit? {
        val spaced = Text.phrase(label).trimEnd { !it.isLetterOrDigit() }
        val letters = spaced.split(' ')
        val text = if (letters.size > 1 && letters.all { it.length == 1 }) letters.joinToString("") else spaced
        val joined = spacedSigns(text).split(Words.ENGLISH_JOINERS)
        val pieces =
            (if (pauses) joined.flatMap { it.split(Words.ENGLISH_PAUSES) } else joined)
                .map { trimmed(it).replace(Words.ENGLISH_FILLERS, "") }
        val split = pieces.size > 1 && (partMax == null || pieces.all { length(it) <= partMax })
        val read = if (split) pieces else listOf(text)
        val vetoes = Vetoes(label)
        return read
            .map { part ->
                englishPhrase(part, whole = read.size == 1)
                    ?.takeUnless { complete && vetoes.of(it.second) }
                    ?.let { (phrase, kind) -> Hit(kind, phrase, sends(phrase, kind)) }
            }.fold(null, ::firstMax)
    }

    /** [word] of [kind] sends something to someone: an OUTWARD word, or a [Words.SENDING_SPENDS] one. */
    private fun sends(
        word: String,
        kind: IntentKind,
    ): Boolean = kind == IntentKind.OUTWARD || word in Words.SENDING_SPENDS

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

    /**
     * Whether [label], read whole, is [vetoed] as each kind, found out at most once per kind: the
     * parts of one label ("send and send and …") share it, so a match reads the label a bounded
     * number of times however many parts it has (policy-rules.md §8).
     */
    private class Vetoes(
        private val label: String,
    ) {
        private val known = EnumMap<IntentKind, Boolean>(IntentKind::class.java)

        fun of(kind: IntentKind): Boolean = known.getOrPut(kind) { vetoed(label, kind) }
    }

    /**
     * [label], read whole, is not of [kind] after all: a code sent to the user's own phone, or a
     * delete of only what the user gets back ([harmless]).
     */
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
                harmless(label)
            }

            else -> {
                false
            }
        }

    /**
     * [label] deletes only what the user gets back (policy-rules.md §3.3): it names a
     * [Words.HARMLESS_DELETES] object, and once those objects, the [Words.DELETE_FILLERS] and the
     * sizes ("128MB") are taken out of its [Text.words], no letter or digit is left: "清空缓存", not
     * "删除聊天记录和缓存".
     */
    private fun harmless(label: String): Boolean {
        val words = Text.words(Words.SIZES.replace(Text.compact(label), " "))
        val objects = Words.HARMLESS_DELETES.fold(words) { acc, word -> acc.replace(word, " ") }
        val rest = Words.DELETE_FILLERS.fold(objects) { acc, word -> acc.replace(word, " ") }
        return objects != words && rest.none(Char::isLetterOrDigit)
    }
}
