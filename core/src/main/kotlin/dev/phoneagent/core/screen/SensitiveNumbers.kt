package dev.phoneagent.core.screen

import java.time.YearMonth

/**
 * Masks bank-card and PRC resident-id numbers in text that leaves the device (phase1 §4): screen
 * text for the model, typed text on trajectory cards. A number is masked only when its check digit
 * is valid (Luhn for cards, ISO 7064 MOD 11-2 for ids), so phone numbers and amounts stay readable;
 * digit runs shortly after an order or tracking label are treated as such and left alone.
 *
 * Masked numbers keep their last four characters: `「银行卡 尾号7890」`, `「身份证 尾号123X」`.
 * Pure and thread-safe.
 */
public object SensitiveNumbers {
    /** [text] with every recognized card and id number replaced by its masked form; the rest is unchanged. */
    public fun mask(text: String): String {
        if (text.count(Char::isDigit) < MIN_CARD_DIGITS) return text
        val idsMasked = ID_CANDIDATE.replace(text) { match -> maskedIdOrNull(match.value) ?: match.value }
        return CARD_CANDIDATE.replace(idsMasked) { match ->
            val start = match.range.first
            val before = idsMasked.substring(maxOf(0, start - CONTEXT_CHARS), start)
            if (NOT_A_CARD.any { before.contains(it, ignoreCase = true) }) match.value else maskCards(match.value)
        }
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

    private fun maskedIdOrNull(candidate: String): String? =
        if (isResidentIdValid(candidate)) "「身份证 尾号${candidate.takeLast(KEPT).uppercase()}」" else null

    /**
     * [run] (digit groups joined by single separators) with every card number in it masked. A card
     * is a contiguous sequence of groups, so a card next to another number ("3 6222 …") is still
     * found; the longest sequence from each position wins.
     */
    private fun maskCards(run: String): String {
        val groups = DIGITS.findAll(run).map { it.range }.toList()
        val out = StringBuilder()
        var copied = 0
        var first = 0
        while (first < groups.size) {
            val last = (groups.lastIndex downTo first).firstOrNull { isCard(run, groups.subList(first, it + 1)) }
            if (last == null) {
                first++
            } else {
                val digits = groups.subList(first, last + 1).joinToString("") { run.substring(it) }
                out.append(run, copied, groups[first].first).append("「银行卡 尾号${digits.takeLast(KEPT)}」")
                copied = groups[last].last + 1
                first = last + 1
            }
        }
        return out.append(run, copied, run.length).toString()
    }

    private fun isCard(
        run: String,
        groups: List<IntRange>,
    ): Boolean {
        val digits = groups.joinToString("") { run.substring(it) }
        val grouping = groups.size == 1 || groups.all { it.count() >= MIN_GROUP }
        return digits.length in MIN_CARD_DIGITS..MAX_CARD_DIGITS && grouping && isLuhnValid(digits)
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
    private const val MIN_GROUP = 3
    private const val KEPT = 4
    private const val CONTEXT_CHARS = 12

    /** 17 digits plus a digit or X, not glued to other letters or digits. */
    private val ID_CANDIDATE = Regex("(?<![0-9A-Za-z])[0-9]{17}[0-9Xx](?![0-9A-Za-z])")

    /** Digit groups joined by single spaces or hyphens, not glued to other letters or digits. */
    private val CARD_CANDIDATE = Regex("(?<![0-9A-Za-z])[0-9]+(?:[ -][0-9]+)*(?![0-9A-Za-z])")

    private val DIGITS = Regex("[0-9]+")

    /** Labels after which a long digit run is an order, tracking or serial number. */
    private val NOT_A_CARD =
        listOf("订单", "单号", "运单", "快递", "物流", "编号", "流水", "交易号", "序列号", "条码", "工单", "发票", "order", "tracking")
}
