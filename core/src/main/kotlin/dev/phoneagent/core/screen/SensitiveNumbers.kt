package dev.phoneagent.core.screen

import java.time.YearMonth

/**
 * Masks bank-card and PRC resident-id numbers in text that leaves the device (phase1 §4): screen
 * text for the model, device-side reasons in tool results, step-card summaries and typed text on
 * trajectory cards.
 *
 * - **What is a number**: digit groups (ASCII or fullwidth digits) joined by gaps, not glued to
 *   letters or digits. A gap is a run of any length of separators with at most two dashes in it:
 *   - spaces: the 25 Unicode White_Space characters ([UNICODE_SPACES]: line breaks, U+0085,
 *     U+00A0, U+2009, U+202F, U+3000 …);
 *   - invisible format characters (Unicode category Cf): U+200B zero-width space, U+200C and
 *     U+200D zero-width (non-)joiner, U+2060 word joiner, U+FEFF, U+00AD soft hyphen, bidi marks;
 *   - dashes: `-`, U+2010–U+2014, U+2212 minus sign, U+FE63 small hyphen-minus, U+FF0D.
 *
 *   So a number wrapped onto indented lines or spread over columns is still one number; whether
 *   it is a card or an id is then decided by its group sizes and its check character.
 * - **Field boundaries**: anything else between two groups ends the number: letters, CJK text,
 *   other punctuation (`/`, `.`, `_`, `·`, `*` …) or a third dash. Not supported, so they also end
 *   a number: other digit forms (Arabic-Indic, circled …), and combining marks, emoji or other
 *   characters outside the Basic Multilingual Plane between digits.
 * - **Trade-off**: fields that only spaces separate are not told apart from one number wrapped
 *   over lines. A phone number on one line and a number on the next (`13800138000⏎⏎    1001`)
 *   read as one run, and a card or id that some of their groups make is masked, with the parts of
 *   both fields it covers. This errs on the private side, and it is common: two mobile numbers
 *   written 3-4-4 on consecutive lines are masked as a card about a third of the time. Fields with
 *   a field word between them (`电话 …⏎金额 …`) are never joined.
 * - **What is masked**: a card is 15–19 digits passing Luhn, in one group or in groups of at least
 *   three digits; an id is 17 digits plus a check character (`0`–`9` or `X`) with a valid region,
 *   birth date and ISO 7064 MOD 11-2 check, in one group or in groups of at least two characters
 *   (6-8-4, 4-4-4-4-2, …). A phone number or an amount on its own therefore stays readable. Every
 *   group of a run is tried as the start of a number, and numbers that overlap are masked as one
 *   span: a number before a card in the same run (`第 105 6222 …`) may be masked with it, but
 *   never leaves a part of the card readable.
 * - **Order-number exception**: a card-shaped number directly after an order, tracking, serial or
 *   invoice label (only spaces, colons or `#` in between, e.g. `订单号：…`, `运单号 …`,
 *   `Order No. …`) is left alone, unless a card word (银行卡, 卡号, 储蓄卡, 信用卡, 借记卡, 账号,
 *   card, …) is near the number: card words always win.
 *
 * A masked span is replaced as a whole, in place, keeping its last four characters:
 * `「银行卡 尾号7890」`, `「身份证 尾号123X」` (the kind of the longest number that ends it); everything
 * around it is kept as it was. Linear in the text. Pure and thread-safe.
 */
public object SensitiveNumbers {
    /** [text] with every recognized card and id number replaced by its masked form; the rest is unchanged. */
    public fun mask(text: String): String {
        if (text.count(Char::isDigit) < MIN_CARD_DIGITS) return text
        // Same length as text, so every range found in it is a range of text.
        val plain = NumberRuns.plain(text)
        val out = StringBuilder()
        var copied = 0
        for (run in NumberRuns.of(plain)) {
            for ((range, masked) in numbersIn(plain, run)) {
                out.append(text, copied, range.first).append(masked)
                copied = range.last + 1
            }
        }
        return out.append(text, copied, text.length).toString()
    }

    /** Whether [digits] (only `0`–`9`) passes the Luhn check used by payment cards. */
    public fun isLuhnValid(digits: String): Boolean {
        require(digits.isNotEmpty() && digits.all { it in '0'..'9' }) { "Luhn input must be decimal digits" }
        val sum =
            digits.reversed().withIndex().sumOf { (index, char) ->
                val digit = char - '0'
                if (index % 2 == 0) digit else (digit * 2).let { if (it >= RADIX) it - RADIX + 1 else it }
            }
        return sum % RADIX == 0
    }

    /**
     * Whether [id] is a well-formed 18-character resident id: region code not starting with 0, a real
     * birth date in 1900–2099, and a valid ISO 7064 MOD 11-2 check character (`0`–`9` or `X`).
     */
    public fun isResidentIdValid(id: String): Boolean {
        val shaped = id.length == ID_LENGTH && id[0] in '1'..'9' && id.take(ID_LENGTH - 1).all { it in '0'..'9' }
        return shaped && hasValidBirthDate(id) && id.last().uppercaseChar() == idCheckChar(id)
    }

    /**
     * The numbers to mask in [groups] (one run of [plain]), in order, as (range, masked form). Every
     * group is tried as the start of a number, longest group sequence first, so a number next to
     * another one ("第 3 6222 …") is still found. Numbers that overlap are masked as one span, so a
     * number found from an earlier start never leaves the end of a later one readable
     * ("105   6222 0212 3456 7894"); the span is masked as the number that ends last, the longest
     * one when several end together. A later number never ends inside an earlier one: the earlier
     * one would need a group on each side of it, and no card or id is that long.
     */
    private fun numbersIn(
        plain: String,
        groups: List<IntRange>,
    ): List<Pair<IntRange, String>> {
        val found = mutableListOf<Pair<IntRange, String>>()
        var spanFirst = 0
        var spanLast = -1
        var spanMasked = ""
        for (first in groups.indices) {
            val (last, masked) = longestNumberFrom(plain, groups, first) ?: continue
            if (first > spanLast) {
                if (spanLast >= 0) found += (groups[spanFirst].first..groups[spanLast].last) to spanMasked
                spanFirst = first
            }
            if (last > spanLast) {
                spanLast = last
                spanMasked = masked
            }
        }
        if (spanLast >= 0) found += (groups[spanFirst].first..groups[spanLast].last) to spanMasked
        return found
    }

    /** The last group and masked form of the longest number starting at group [first], or `null`. */
    private fun longestNumberFrom(
        plain: String,
        groups: List<IntRange>,
        first: Int,
    ): Pair<Int, String>? {
        // A group longer than any card starts none, however long it is: not even copied.
        if (groups[first].count() > MAX_CARD_DIGITS) return null
        return (longestFrom(groups, first) downTo first).firstNotNullOfOrNull { last ->
            masked(plain, groups.subList(first, last + 1))?.let { last to it }
        }
    }

    /**
     * The last index of the longest group sequence from [first] that is not longer than any card or
     * id, so a long run of short groups costs a bounded number of checks per group.
     */
    private fun longestFrom(
        groups: List<IntRange>,
        first: Int,
    ): Int {
        var last = first
        var chars = groups[first].count()
        while (last < groups.lastIndex && chars + groups[last + 1].count() <= MAX_CARD_DIGITS) {
            last++
            chars += groups[last].count()
        }
        return last
    }

    /** The masked form of [groups] when they make an id or a card that is not exempt, else `null`. */
    private fun masked(
        plain: String,
        groups: List<IntRange>,
    ): String? {
        val chars = groups.joinToString("") { plain.substring(it) }
        val minGroup = groups.minOf { it.count() }
        val id = chars.length == ID_LENGTH && (groups.size == 1 || minGroup >= MIN_ID_GROUP) && isResidentIdValid(chars)
        if (id) return "「身份证 尾号${chars.takeLast(KEPT)}」"
        val card =
            chars.length in MIN_CARD_DIGITS..MAX_CARD_DIGITS &&
                chars.all { it in '0'..'9' } &&
                (groups.size == 1 || minGroup >= MIN_CARD_GROUP) &&
                isLuhnValid(chars)
        val span = groups.first().first..groups.last().last
        return if (card && !isLabeledNotACard(plain, span)) "「银行卡 尾号${chars.takeLast(KEPT)}」" else null
    }

    /** Whether the number at [span] directly follows an order-like label and no card word is near it. */
    private fun isLabeledNotACard(
        plain: String,
        span: IntRange,
    ): Boolean {
        val before = plain.substring(maxOf(0, span.first - CARD_WORD_BEFORE), span.first)
        val after = plain.substring(span.last + 1, minOf(plain.length, span.last + 1 + CARD_WORD_AFTER))
        val cardWord = CARD_WORDS.containsMatchIn(before) || CARD_WORDS.containsMatchIn(after)
        return !cardWord && followsOrderLabel(plain, span.first)
    }

    /**
     * Whether an order label ends right before [start], with only spaces, colons or `#` (any number
     * of them) in between. Reads back over those, then at most [LABEL_WINDOW] characters, so the
     * cost does not grow with the text before the number.
     */
    private fun followsOrderLabel(
        plain: String,
        start: Int,
    ): Boolean {
        var end = start
        while (end > 0 && plain[end - 1] in LABEL_SEPARATORS) end--
        return ORDER_LABEL.containsMatchIn(plain.substring(maxOf(0, end - LABEL_WINDOW), end))
    }

    private fun hasValidBirthDate(id: String): Boolean {
        val year = id.substring(BIRTH_START, BIRTH_START + 4).toInt()
        val month = id.substring(BIRTH_START + 4, BIRTH_START + 6).toInt()
        val day = id.substring(BIRTH_START + 6, BIRTH_START + 8).toInt()
        return year in MIN_BIRTH_YEAR..MAX_BIRTH_YEAR && month in 1..MONTHS && YearMonth.of(year, month).isValidDay(day)
    }

    /** ISO 7064 MOD 11-2: weight of position i (from the left) is 2^(17 - i) mod 11. */
    private fun idCheckChar(id: String): Char {
        var weight = 1
        val weights = IntArray(ID_LENGTH - 1)
        for (i in ID_LENGTH - 2 downTo 0) {
            weight = weight * 2 % MOD
            weights[i] = weight
        }
        val sum = (0 until ID_LENGTH - 1).sumOf { (id[it] - '0') * weights[it] }
        val check = (MOD + 1 - sum % MOD) % MOD
        return if (check == RADIX) 'X' else '0' + check
    }

    private const val ID_LENGTH = 18
    private const val BIRTH_START = 6
    private const val MIN_BIRTH_YEAR = 1900
    private const val MAX_BIRTH_YEAR = 2099
    private const val MOD = 11
    private const val RADIX = 10
    private const val MONTHS = 12
    private const val MIN_CARD_DIGITS = 15
    private const val MAX_CARD_DIGITS = 19
    private const val MIN_CARD_GROUP = 3
    private const val MIN_ID_GROUP = 2
    private const val KEPT = 4
    private const val CARD_WORD_BEFORE = 32
    private const val CARD_WORD_AFTER = 12

    /** What may stand between an order label and its number (on the plain copy: any space is ` `). */
    private const val LABEL_SEPARATORS = " :：#"

    /** The longest order label ("tracking number") and the character before it, for the letter check. */
    private const val LABEL_WINDOW = 16

    /** An order, tracking, serial or invoice label at the end of the text before a number. */
    private val ORDER_LABEL =
        Regex(
            "(?:(?<![A-Za-z])(?:order|tracking|serial|invoice)(?: ?(?:no\\.?|number|id))?" +
                "|订单编号|运单编号|单号|订单|交易号|流水号|序列号|条形码|条码|发票号码|发票代码|发票号)$",
            RegexOption.IGNORE_CASE,
        )

    /** Words that make a nearby number a card or account number, whatever label precedes it. */
    private val CARD_WORDS =
        Regex(
            "银行卡|卡号|储蓄卡|信用卡|借记卡|银联|账号|帐号|账户|帐户" +
                "|(?<![A-Za-z])(?:card|visa|mastercard|unionpay|debit|credit|account|acct|iban)",
            RegexOption.IGNORE_CASE,
        )
}
