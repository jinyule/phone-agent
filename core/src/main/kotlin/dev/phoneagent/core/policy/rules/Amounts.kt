package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenSnapshot
import java.math.BigDecimal

/** What a spend is about (policy-rules.md §4): one amount, none at all, or amounts that cannot be told apart. */
internal sealed interface Price {
    /** The amount paid. */
    data class Known(
        val money: Money,
    ) : Price

    /** No amount is shown: there is nothing to bind. */
    data object Absent : Price

    /** Amounts are shown, but which one is paid cannot be told: several, too large, or written in a form not read. */
    data object Unclear : Price
}

/** The amount of a [Price.Known], else `null`. */
internal val Price.money: Money?
    get() = (this as? Price.Known)?.money

/**
 * Amounts as written (policy-rules.md §4): `¥` / `$` / `€` / `£`, currency codes before or after,
 * `元` / `块` / `美元` …, thousands separators, up to two decimals and `万`. Amounts right after a
 * minus sign or a discount word (减, 省, 抵, 返) are discounts, numbers followed by a count word
 * (件, 个, items …) are counts; both are skipped. An explicit currency code wins over a symbol;
 * a lone `$` or `¥` says no currency (Money.currency is `null`).
 *
 * Adapted from Mika's `IrreversibleDetector` amount reading (Copyright 2026 wangzan101,
 * Apache-2.0). Modified by PhoneAgent contributors: currency codes and conflicts, counts, digit
 * limits and the unclear case. See NOTICE.
 */
internal object Prices {
    /**
     * One amount as written; [minor] is `null` when it cannot be read: thousands separators not in
     * groups of three ("12,34"), more than two decimals ("20.000"), more than [MAX_DIGITS] integer
     * digits, or too large to fit.
     */
    data class Found(
        val minor: Long?,
        val currency: String?,
        val marked: Boolean,
    )

    /** Integer digits beyond this are read as an unclear amount. */
    private const val MAX_DIGITS = 15
    private const val MAX_FRACTION = 2
    private const val GROUP = 3
    private const val TEN_THOUSAND = 10_000L
    private const val MINOR_PER_UNIT = 100L
    private val LARGEST = BigDecimal.valueOf(Long.MAX_VALUE)

    // Groups: 1 code before, 2 symbol before, 3 integer part, 4 decimals, 5 万, 6 currency after.
    private val AMOUNT =
        Regex(
            "(?:(?<![a-z])(us\\$|hk\\$|nt\\$|s\\$|a\\$|c\\$|rmb|cny|usd|hkd|eur|gbp|jpy)\\s*)?([$¥€£])?\\s*" +
                "(?<![\\d.,])(\\d[\\d,]*)(?:\\.(\\d+))?(?!\\d)(?![.,]\\d)" +
                "\\s*(万)?\\s*(元|块|美元|港元|港币|欧元|英镑|日元|rmb|cny|usd|hkd|eur|gbp|jpy)?(?![a-z])",
        )
    private const val CODE = 1
    private const val SYMBOL = 2
    private const val INT = 3
    private const val FRACTION = 4
    private const val WAN = 5
    private const val SUFFIX = 6

    /** Every code [AMOUNT] captures before or after a number. */
    private val CODES: Map<String, String> =
        mapOf(
            "us$" to "USD",
            "hk$" to "HKD",
            "nt$" to "TWD",
            "s$" to "SGD",
            "a$" to "AUD",
            "c$" to "CAD",
            "rmb" to "CNY",
            "cny" to "CNY",
            "usd" to "USD",
            "hkd" to "HKD",
            "eur" to "EUR",
            "gbp" to "GBP",
            "jpy" to "JPY",
            "元" to "CNY",
            "块" to "CNY",
            "美元" to "USD",
            "港元" to "HKD",
            "港币" to "HKD",
            "欧元" to "EUR",
            "英镑" to "GBP",
            "日元" to "JPY",
        )

    /** Symbols that name one currency; `$` and `¥` are used by several. */
    private val SYMBOLS: Map<String, String> = mapOf("€" to "EUR", "£" to "GBP")

    private val DISCOUNT_BEFORE: Set<Char> = setOf('-', '−', '减', '省', '抵', '返')

    private val COUNTS: List<String> = listOf("件", "个", "份", "项", "张", "次", "items", "item", "pcs")

    /** The amounts in [text] in reading order, discounts and counts left out. */
    fun scan(text: String): List<Found> {
        val spaced = Text.spaced(text)
        return AMOUNT
            .findAll(spaced)
            .filterNot { charBefore(spaced, it.range.first) in DISCOUNT_BEFORE || counted(spaced, it.range.last + 1) }
            .map(::found)
            .toList()
    }

    /** The amount `request_confirmation` states: the first one, with or without a currency; `null` when none. */
    fun stated(text: String): Price? = scan(text).firstOrNull()?.let(::price)

    /** The first amount with a currency mark in a control's own label ("立即支付 ¥39.90"), or `null`. */
    fun inLabel(label: String): Price? = scan(label).firstOrNull(Found::marked)?.let(::price)

    fun price(found: Found): Price =
        if (found.minor ==
            null
        ) {
            Price.Unclear
        } else {
            Price.Known(Money(found.minor, found.currency))
        }

    private fun charBefore(
        text: String,
        at: Int,
    ): Char {
        var i = at - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return if (i >= 0) text[i] else ' '
    }

    private fun counted(
        text: String,
        end: Int,
    ): Boolean {
        var i = end
        while (i < text.length && text[i].isWhitespace()) i++
        return COUNTS.any { text.startsWith(it, i) }
    }

    /** A number in a form this cannot read is still an amount: unclear, never "no amount". */
    private fun found(match: MatchResult): Found {
        val g = match.groupValues
        val groups = g[INT].trimEnd(',').split(',')
        val grouped = groups.size == 1 || (groups[0].length <= GROUP && groups.drop(1).all { it.length == GROUP })
        val digits = groups.joinToString("")
        val readable = grouped && g[FRACTION].length <= MAX_FRACTION && digits.length <= MAX_DIGITS
        val minor = if (readable) minor(digits, g[FRACTION], g[WAN].isNotEmpty()) else null
        return Found(minor, currency(g), g[CODE].isNotEmpty() || g[SYMBOL].isNotEmpty() || g[SUFFIX].isNotEmpty())
    }

    private fun currency(g: List<String>): String? {
        val codes = listOf(g[CODE], g[SUFFIX]).filter(String::isNotEmpty).map(CODES::getValue).distinct()
        return when {
            codes.size == 1 -> codes.single()
            codes.isNotEmpty() -> null
            else -> SYMBOLS[g[SYMBOL]]
        }
    }

    private fun minor(
        digits: String,
        fraction: String,
        wan: Boolean,
    ): Long? {
        val units = BigDecimal(digits + "." + fraction.ifEmpty { "0" })
        val scaled = if (wan) units.multiply(BigDecimal.valueOf(TEN_THOUSAND)) else units
        val minor = scaled.multiply(BigDecimal.valueOf(MINOR_PER_UNIT))
        return if (minor > LARGEST) null else minor.toLong()
    }
}

/** Amounts on a screen: what a spend there is about (policy-rules.md §4). */
internal class Amounts(
    private val tuning: PolicyTuning,
) {
    private val byRank: Comparator<Pair<Int, Price>> = compareBy { it.first }

    /** Whether [screen] shows a total label (合计, 实付 …), loaded or not: a checkout. */
    fun showsTotal(screen: ScreenSnapshot): Boolean = Elements.pieces(screen).any { totalLabel(it.text) != null }

    /**
     * What a spend through a control labelled [label] is about: an amount in the label itself,
     * else the amount after the best-ranked total label (the last one in reading order among
     * equals), else the only distinct amount on [screen]; several distinct amounts are unclear.
     */
    fun forSpend(
        label: String,
        screen: ScreenSnapshot?,
    ): Price = Prices.inLabel(label) ?: if (screen == null) Price.Absent else total(screen) ?: single(screen)

    private fun total(screen: ScreenSnapshot): Price? {
        val pieces = Elements.pieces(screen)
        val candidates =
            pieces.mapNotNull { piece ->
                totalLabel(piece.text)?.let { (rank, end) ->
                    val after = Text.spaced(piece.text).substring(end) + " " + Geometry.rightOf(piece, pieces)
                    following(after)?.let { rank to it }
                }
            }
        return candidates.asReversed().minWithOrNull(byRank)?.second
    }

    /** The best total label in [text] and where it ends in [Text.spaced] form, or `null`. */
    private fun totalLabel(text: String): Pair<Int, Int>? {
        val spaced = Text.spaced(text)
        val rank = TOTAL_LABELS.indexOfFirst { it in spaced }
        return if (rank < 0 || Text.compact(text).length > tuning.totalTextMax) {
            null
        } else {
            rank to spaced.indexOf(TOTAL_LABELS[rank]) + TOTAL_LABELS[rank].length
        }
    }

    /** After a total label: the first amount with a currency mark, else the first number. */
    private fun following(text: String): Price? {
        val found = Prices.scan(text)
        return (found.firstOrNull(Prices.Found::marked) ?: found.firstOrNull())?.let(Prices::price)
    }

    private fun single(screen: ScreenSnapshot): Price {
        val shown = Elements.pieces(screen).flatMap { Prices.scan(it.text) }.filter(Prices.Found::marked)
        val distinct = shown.map { it.minor to it.currency }.distinct()
        return when {
            shown.isEmpty() -> Price.Absent
            distinct.size == 1 -> Prices.price(shown.first())
            else -> Price.Unclear
        }
    }
}

/** Total labels ([Text.spaced] form), best first: what the user actually pays beats a subtotal. */
private val TOTAL_LABELS: List<String> =
    listOf(
        "实付",
        "应付",
        "需付",
        "还需支付",
        "支付金额",
        "付款金额",
        "待支付",
        "you pay",
        "amount due",
        "to pay",
        "合计",
        "总计",
        "总价",
        "共计",
        "总额",
        "grand total",
        "order total",
        "total",
    )
