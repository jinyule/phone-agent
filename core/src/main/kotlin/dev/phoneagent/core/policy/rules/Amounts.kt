package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenSnapshot
import java.math.BigDecimal

/**
 * Money shown on a screen (policy-rules.md §4): `¥` / `￥` / `元` / `$` / … with thousands separators,
 * up to two decimals and `万`. Amounts right after a minus sign or a discount word (减, 省, 抵, 返)
 * are discounts and skipped.
 */
internal object Amounts {
    /** Text this long or shorter may hold a total label; longer text is content. */
    private const val LABEL_TEXT_MAX = 30
    private const val TEN_THOUSAND = 10_000L
    private const val MINOR_PER_UNIT = 100L

    // Groups: 1 currency before, 2 integer part, 3 decimals, 4 万, 5 currency after.
    private val AMOUNT =
        Regex(
            "(?<![a-z])(us\\$|hk\\$|nt\\$|s\\$|a\\$|c\\$|\\$|¥|€|£|rmb|cny|usd|hkd|eur|gbp|jpy)?\\s*" +
                "(?<![\\d.,])(\\d{1,3}(?:,\\d{3})+|\\d{1,12})(?:\\.(\\d{1,2}))?(?!\\d)(?![.,]\\d)" +
                "\\s*(万)?\\s*(元|块|美元|港元|港币|欧元|英镑|日元|rmb|cny|usd|hkd|eur|gbp|jpy)?(?![a-z])",
        )
    private const val PRE = 1
    private const val INT = 2
    private const val FRAC = 3
    private const val WAN = 4
    private const val SUF = 5

    private val CURRENCIES: Map<String, String> =
        mapOf(
            "¥" to "CNY",
            "rmb" to "CNY",
            "cny" to "CNY",
            "元" to "CNY",
            "块" to "CNY",
            "$" to "USD",
            "us$" to "USD",
            "usd" to "USD",
            "美元" to "USD",
            "hk$" to "HKD",
            "hkd" to "HKD",
            "港元" to "HKD",
            "港币" to "HKD",
            "nt$" to "TWD",
            "s$" to "SGD",
            "a$" to "AUD",
            "c$" to "CAD",
            "€" to "EUR",
            "eur" to "EUR",
            "欧元" to "EUR",
            "£" to "GBP",
            "gbp" to "GBP",
            "英镑" to "GBP",
            "jpy" to "JPY",
            "日元" to "JPY",
        )

    private val DISCOUNT_BEFORE: Set<Char> = setOf('-', '−', '减', '省', '抵', '返')

    /**
     * Total labels, best first: what the user actually pays beats a subtotal.
     * [Text.compact] form.
     */
    private val TOTAL_LABELS: List<String> =
        listOf(
            "实付",
            "应付",
            "需付",
            "还需支付",
            "支付金额",
            "付款金额",
            "待支付",
            "youpay",
            "amountdue",
            "topay",
            "合计",
            "总计",
            "总价",
            "共计",
            "总额",
            "grandtotal",
            "ordertotal",
            "total",
        )

    /**
     * The first amount in [text]. With [bare], a number without a currency counts too (it sits
     * next to a total label); otherwise a currency mark is required.
     */
    fun find(
        text: String,
        bare: Boolean = false,
    ): Money? {
        val normalized = Text.spaced(text)
        return AMOUNT
            .findAll(normalized)
            .filterNot { lastCharBefore(normalized, it.range.first) in DISCOUNT_BEFORE }
            .firstNotNullOfOrNull { toMoney(it, bare) }
    }

    private fun lastCharBefore(
        text: String,
        end: Int,
    ): Char = text.substring(0, end).trimEnd().lastOrNull() ?: ' '

    private fun toMoney(
        match: MatchResult,
        bare: Boolean,
    ): Money? {
        val v = match.groupValues
        val currency = CURRENCIES[v[PRE].ifEmpty { v[SUF] }]
        val units = BigDecimal(v[INT].replace(",", "") + "." + v[FRAC].ifEmpty { "0" })
        val scaled = if (v[WAN].isEmpty()) units else units.multiply(BigDecimal.valueOf(TEN_THOUSAND))
        val minor = scaled.multiply(BigDecimal.valueOf(MINOR_PER_UNIT)).toLong()
        return if (currency != null || bare) Money(minor, currency) else null
    }

    /** Whether [screen] shows a total label (合计, 实付 …), loaded or not: a checkout. */
    fun showsTotal(screen: ScreenSnapshot): Boolean = Elements.pieces(screen).any { rank(it.text) != null }

    /**
     * The amount a spend on [screen] is about: one in [label] itself, else the best-ranked total
     * (the last one in reading order among equals), else the only distinct amount on screen.
     */
    fun forSpend(
        label: String,
        screen: ScreenSnapshot,
    ): Money? = find(label) ?: total(screen) ?: single(screen)

    private fun total(screen: ScreenSnapshot): Money? {
        val pieces = Elements.pieces(screen)
        val candidates =
            pieces.mapNotNull { piece ->
                rank(piece.text)?.let { rank -> find(Geometry.rowText(piece, pieces), bare = true)?.let { rank to it } }
            }
        // minByOrNull keeps the first minimum; reversed, that is the last one in reading order.
        return candidates.asReversed().minByOrNull { it.first }?.second
    }

    private fun rank(text: String): Int? {
        val compact = Text.compact(text)
        return TOTAL_LABELS.indexOfFirst { it in compact }.takeIf { it >= 0 && compact.length <= LABEL_TEXT_MAX }
    }

    private fun single(screen: ScreenSnapshot): Money? =
        Elements
            .pieces(screen)
            .mapNotNull { find(it.text) }
            .distinct()
            .singleOrNull()
}
