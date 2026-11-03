package dev.phoneagent.core.policy.rules

import java.text.Normalizer
import java.util.Locale

/**
 * The one text normalization every list is matched with, so lists are written in plain form.
 *
 * [idWords] is adapted from Mika's `IrreversibleDetector` (Copyright 2026 wangzan101,
 * Apache-2.0). Modified by PhoneAgent contributors: also splits camel case and dots, and the
 * normalization adds NFKC, zero-width removal and traditional-to-simplified folding. See NOTICE.
 */
internal object Text {
    /** Android package names are at most this long; anything longer is not one. */
    private const val PACKAGE_MAX = 255

    private val WHITESPACE = Regex("\\s+")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val ID_SEPARATORS = Regex("[_.\\-]+")

    /**
     * Amounts and counts ("¥1,299.00", "123.45 元", "3件"), after [normalize]. Runs of spaces
     * inside are bounded, so a match costs time in proportion to what it covers.
     */
    private val NUMBERS =
        Regex(
            "(?:us\\$|hk\\$|nt\\$|s\\$|a\\$|c\\$|rmb|cny|usd|hkd|eur|gbp|jpy|[$¥€£])?\\s{0,8}\\d[\\d,.]*" +
                "\\s{0,8}万?\\s{0,8}(?:元|块|美元|港元|港币|欧元|英镑|日元|rmb|cny|usd|hkd|eur|gbp|jpy|件|个|份|项|张|次)?",
        )

    /**
     * Amounts with a currency mark ("¥20", "20 元", "US$5/月", "¥15起"), after [normalize]. Every
     * run of spaces inside is bounded and a number starts only where no digit precedes, so a
     * match costs time in proportion to what it covers.
     */
    private val MONEY =
        Regex(
            "(?:(?:(?:us|hk|nt|s|a|c)\\$|rmb|cny|usd|hkd|eur|gbp|jpy|[$¥€£])\\s{0,8}\\d[\\d,.]*(?:\\s{0,8}万)?" +
                "(?:\\s{0,8}$CURRENCY_WORDS)?|(?<![\\d.,])\\d[\\d,.]*(?:\\s{0,8}万)?\\s{0,8}$CURRENCY_WORDS)" +
                "(?:\\s{0,8}(?:/|每)\\s{0,8}(?:月|年|季|周|天|month|year|mo|yr))?(?:\\s{0,8}起)?",
        )

    /** Traditional characters of the vocabulary and their simplified forms, in pairs. */
    private const val TRADITIONAL_PAIRS =
        "萬万與与買买價价會会協协單单歷历曆历發发號号後后員员唄呗圖图塊块備备復复實实寶宝帳账賬账應应開开強强" +
            "錄录態态總总懸悬戶户報报搶抢撥拨擇择據据數数無无顯显暫暂權权來来歐欧溝沟點点狀状現现碼码確确礙碍" +
            "簡简紅红約约紋纹線线細细結结絕绝統统繼继續续緩缓繳缴聯联職职臉脸裝装視视計计訂订認认議议訊讯記记" +
            "許许論论設设訪访證证評评識识試试話话詳详語语說说請请調调敗败貨货購购費费賞赏轉转載载輔辅輸输過过" +
            "還还進进選选遞递錢钱銀银鎖锁鍵键鎊镑閉闭問问閱阅隱隐項项順顺須须預预頻频額额馬马驗验刪删擊击動动" +
            "關关憑凭別别幣币佈布況况廠厂臺台啟启啓启內内機机係系繫系註注銷销獲获簽签誌志畫画傳传匯汇儲储"

    private val SIMPLIFIED: Map<Char, Char> = TRADITIONAL_PAIRS.chunked(2).associate { it[0] to it[1] }

    /**
     * Whether [text] is an Android package name: dot-separated segments of ASCII letters, digits
     * and `_`, each starting with a letter, at least two, at most [PACKAGE_MAX] characters. Checked
     * character by character, so input of any length is safe.
     */
    fun isPackage(text: String): Boolean {
        val segments = if (text.length <= PACKAGE_MAX) text.split('.') else emptyList()
        return segments.size >= 2 && segments.all(::isSegment)
    }

    /** NFKC (full-width forms become ASCII, ￥ becomes ¥), lower case, simplified, whitespace and zero-width removed. */
    fun compact(raw: String): String = normalize(raw).filterNot(Char::isWhitespace)

    /**
     * The end of the longest start of [raw] of at most [max] normalized characters, each code
     * point counted as the characters it has in [compact] form: whitespace and zero-width ones
     * none, one that NFKC writes as several ("℡" is "tel") that many. Stops at [max], so it costs
     * time in proportion to what it reads.
     */
    fun prefix(
        raw: String,
        max: Int,
    ): Int {
        var counted = 0
        var end = 0
        while (end < raw.length) {
            val next = raw.offsetByCodePoints(end, 1)
            val c = raw[end]
            val weight =
                when {
                    c.isWhitespace() -> 0
                    c.code < ASCII_END -> 1
                    else -> compact(raw.substring(end, next)).length
                }
            if (counted + weight > max) return end
            counted += weight
            end = next
        }
        return end
    }

    /** Like [compact], but whitespace runs collapse to one space and the ends are trimmed. */
    fun spaced(raw: String): String = normalize(raw).trim().replace(WHITESPACE, " ")

    /** [compact] without trailing punctuation or symbols ("发送！" → "发送", "ok." → "ok"). */
    fun bare(raw: String): String = compact(raw).trimEnd { !it.isLetterOrDigit() }

    /** [raw] in [normalize] form with its amounts that carry a currency mark replaced by a space. */
    fun withoutMoney(raw: String): String = normalize(raw).replace(MONEY, " ")

    /** What a label says without its bracketed notes, amounts and counts, in [compact] form. */
    fun words(raw: String): String = stripped(raw).filterNot(Char::isWhitespace)

    /** [words] in [spaced] form, for English phrases. */
    fun phrase(raw: String): String = stripped(raw).trim().replace(WHITESPACE, " ")

    private fun stripped(raw: String): String = withoutNotes(normalize(raw)).replace(NUMBERS, " ")

    /** Words of a resource id's last segment: split at `_`, `-`, `.` and camel case, lower case. */
    fun idWords(resourceId: String): List<String> =
        resourceId
            .substringAfterLast('/')
            .replace(CAMEL, "$1_$2")
            .lowercase(Locale.ROOT)
            .split(ID_SEPARATORS)
            .filter(String::isNotEmpty)

    /**
     * NFKC, lower case, simplified, and without invisible code points ([invisible]); one pass over
     * the code points.
     */
    private fun normalize(raw: String): String {
        Work.characters.add(raw.length.toLong())
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString(folded.length) {
            var at = 0
            while (at < folded.length) {
                val point = folded.codePointAt(at)
                val c = folded[at]
                when {
                    invisible(point) -> Unit
                    Character.isBmpCodePoint(point) -> append(SIMPLIFIED[c] ?: c)
                    else -> appendCodePoint(point)
                }
                at += Character.charCount(point)
            }
        }
    }
}

/**
 * [point] shows nothing and may sit inside a word: a format character (zero-width ones, also
 * outside the basic plane) or one of the other default-ignorable code points of Unicode, such
 * as the grapheme joiner, variation selectors and Hangul fillers ([IGNORABLE]).
 */
private fun invisible(point: Int): Boolean =
    Character.getType(point) == Character.FORMAT.toInt() || IGNORABLE.any { point in it }

/**
 * The default-ignorable code points of Unicode (DerivedCoreProperties) that are not format
 * characters: the grapheme joiner, Hangul fillers, Khmer inherent vowels, Mongolian selectors,
 * variation selectors, and reserved ranges kept invisible.
 */
private val IGNORABLE: List<IntRange> =
    listOf(
        0x034F..0x034F,
        0x115F..0x1160,
        0x17B4..0x17B5,
        0x180B..0x180F,
        0x2065..0x2065,
        0x3164..0x3164,
        0xFE00..0xFE0F,
        0xFFA0..0xFFA0,
        0xFFF0..0xFFF8,
        0xE0000..0xE0FFF,
    )

/** ASCII characters are their own normalized form, but for upper case, which lower case does not lengthen. */
private const val ASCII_END = 128

/** Brackets that open a note ("《服务协议》", "（共计¥88）", "(3)") and those that close one, of any kind. */
private const val OPENERS = "(（[【《〈「『<"
private val CLOSERS = charArrayOf(')', '）', ']', '】', '》', '〉', '」', '』', '>')

/**
 * [text] with every bracketed note replaced by a space: from an opening bracket to the next closing
 * one of any kind; an opening bracket with no closing one after it stays. Notes are not part of
 * what a button says it does. One pass over [text]: each character is looked at a bounded number
 * of times.
 */
private fun withoutNotes(text: String): String {
    val last = text.lastIndexOfAny(CLOSERS)
    val out = StringBuilder(text.length)
    var at = 0
    while (at < text.length) {
        if (at < last && text[at] in OPENERS) {
            out.append(' ')
            at = text.indexOfAny(CLOSERS, at + 1) + 1
        } else {
            out.append(text[at])
            at++
        }
    }
    return out.toString()
}

/** Currency words after an amount, in [Text.compact] form, as one regex group. */
private const val CURRENCY_WORDS = "(?:元|块|美元|港元|港币|欧元|英镑|日元|rmb|cny|usd|hkd|eur|gbp|jpy)"

private fun isSegment(segment: String): Boolean =
    segment.isNotEmpty() && segment[0].isAsciiLetter() &&
        segment.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
