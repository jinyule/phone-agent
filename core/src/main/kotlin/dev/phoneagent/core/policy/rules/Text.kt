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

    /** Notes in brackets ("《服务协议》", "（共计¥88）", "(3)"): not part of what a button says it does. */
    private val NOTES = Regex("[(（\\[【《〈「『<][^)）\\]】》〉」』>]*[)）\\]】》〉」』>]")

    /** Amounts and counts ("¥1,299.00", "123.45 元", "3件"), after [normalize]. */
    private val NUMBERS =
        Regex(
            "(?:us\\$|hk\\$|nt\\$|s\\$|a\\$|c\\$|rmb|cny|usd|hkd|eur|gbp|jpy|[$¥€£])?\\s*\\d[\\d,.]*\\s*万?\\s*" +
                "(?:元|块|美元|港元|港币|欧元|英镑|日元|rmb|cny|usd|hkd|eur|gbp|jpy|件|个|份|项|张|次)?",
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

    private fun isSegment(segment: String): Boolean =
        segment.isNotEmpty() && segment[0].isAsciiLetter() &&
            segment.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** NFKC (full-width forms become ASCII, ￥ becomes ¥), lower case, simplified, whitespace and zero-width removed. */
    fun compact(raw: String): String = normalize(raw).filterNot(Char::isWhitespace)

    /** Like [compact], but whitespace runs collapse to one space and the ends are trimmed. */
    fun spaced(raw: String): String = normalize(raw).trim().replace(WHITESPACE, " ")

    /** [compact] without trailing punctuation or symbols ("发送！" → "发送", "ok." → "ok"). */
    fun bare(raw: String): String = compact(raw).trimEnd { !it.isLetterOrDigit() }

    /** What a label says without its bracketed notes, amounts and counts, in [compact] form. */
    fun words(raw: String): String = stripped(raw).filterNot(Char::isWhitespace)

    /** [words] in [spaced] form, for English phrases. */
    fun phrase(raw: String): String = stripped(raw).trim().replace(WHITESPACE, " ")

    private fun stripped(raw: String): String = normalize(raw).replace(NOTES, " ").replace(NUMBERS, " ")

    /** Words of a resource id's last segment: split at `_`, `-`, `.` and camel case, lower case. */
    fun idWords(resourceId: String): List<String> =
        resourceId
            .substringAfterLast('/')
            .replace(CAMEL, "$1_$2")
            .lowercase(Locale.ROOT)
            .split(ID_SEPARATORS)
            .filter(String::isNotEmpty)

    private fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildString(folded.length) {
            for (c in folded) {
                if (c.category != CharCategory.FORMAT) append(SIMPLIFIED[c] ?: c)
            }
        }
    }
}
