package dev.phoneagent.core.screen

import java.time.YearMonth

/**
 * Masks bank-card and PRC resident-id numbers in text that leaves the device (phase1 §4): screen
 * text for the model, device-side reasons in tool results, step-card summaries and typed text on
 * trajectory cards.
 *
 * - **What is a number**: digit groups joined by one or two separators (any Unicode space, or a
 *   hyphen or dash), not glued to letters or digits; fullwidth digits count as digits. Each group
 *   sequence is tested where it stands, so numbers of separate fields are never joined.
 * - **What is masked**: a card is 15–19 digits passing Luhn, in one group or in groups of at least
 *   three digits; an id is 17 digits plus a check character (`0`–`9` or `X`) with a valid region,
 *   birth date and ISO 7064 MOD 11-2 check, in one group or in groups of at least two characters
 *   (6-8-4, 4-4-4-4-2, …). Phone numbers and amounts therefore stay readable.
 * - **Order-number exception**: a card-shaped number directly after an order, tracking, serial or
 *   invoice label (only spaces, colons or `#` in between, e.g. `订单号：…`, `运单号 …`,
 *   `Order No. …`) is left alone, unless a card word (银行卡, 卡号, 储蓄卡, 信用卡, 借记卡, 账号,
 *   card, …) is near the number: card words always win.
 *
 * A masked number is replaced as a whole, in place, keeping its last four characters:
 * `「银行卡 尾号7890」`, `「身份证 尾号123X」`; everything around it is kept as it was. Pure and
 * thread-safe.
 */
public object SensitiveNumbers {
    /** [text] with every recognized card and id number replaced by its masked form; the rest is unchanged. */
    public fun mask(text: String): String {
        if (text.count(Char::isDigit) < MIN_CARD_DIGITS) return text
        // Same length as text, so every range found in it is a range of text.
        val plain = text.map(::plainChar).joinToString("")
        val out = StringBuilder()
        var copied = 0
        for (run in RUN.findAll(plain)) {
            for ((range, masked) in numbersIn(plain, run.range)) {
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
     * The numbers to mask inside [run] of [plain], in order, as (range, masked form). From each
     * group the longest group sequence that is an id or a card wins, so a number next to another
     * one ("第 3 6222 …") is still found.
     */
    private fun numbersIn(
        plain: String,
        run: IntRange,
    ): List<Pair<IntRange, String>> {
        val groups =
            GROUP
                .findAll(plain, run.first)
                .map { it.range }
                .takeWhile { it.last <= run.last }
                .toList()
        val found = mutableListOf<Pair<IntRange, String>>()
        var first = 0
        while (first < groups.size) {
            val hit =
                (groups.lastIndex downTo first).firstNotNullOfOrNull { last ->
                    masked(plain, groups.subList(first, last + 1))?.let { last to it }
                }
            if (hit == null) {
                first++
            } else {
                found += (groups[first].first..groups[hit.first].last) to hit.second
                first = hit.first + 1
            }
        }
        return found
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
        return !cardWord && ORDER_LABEL.containsMatchIn(plain.substring(0, span.first))
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

    /**
     * [c] as the number scanner sees it: fullwidth digits, `X` and `#` as ASCII, `x` as `X`, any
     * whitespace (Unicode spaces included) as a space, hyphens and dashes as `-`; one char for one.
     */
    private fun plainChar(c: Char): Char =
        when {
            c in '０'..'９' -> '0' + (c - '０')
            c == 'x' || c == 'ｘ' || c == 'Ｘ' -> 'X'
            c == '＃' -> '#'
            c.isWhitespace() -> ' '
            c in DASHES -> '-'
            else -> c
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
    private const val DASHES = "‐‑‒–—－"

    /** Digit groups joined by one or two separators, maybe ending in an id check `X`, not glued to alphanumerics. */
    private val RUN = Regex("(?<![0-9A-Za-z])[0-9]+(?:[ -]{1,2}[0-9]+)*X?(?![0-9A-Za-z])")

    private val GROUP = Regex("[0-9]+X?")

    /** An order, tracking, serial or invoice label right before the number (end of the text before it). */
    private val ORDER_LABEL =
        Regex(
            "(?:(?<![A-Za-z])(?:order|tracking|serial|invoice)(?: ?(?:no\\.?|number|id))?" +
                "|订单编号|运单编号|单号|订单|交易号|流水号|序列号|条形码|条码|发票号码|发票代码|发票号)[ :：#]*$",
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
