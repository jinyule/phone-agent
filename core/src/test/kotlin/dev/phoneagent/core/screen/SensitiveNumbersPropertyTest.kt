package dev.phoneagent.core.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Random

/**
 * Seeded samples of the forms the masking contract supports (SensitiveNumbers KDoc): a card or an
 * id, in one group or in groups of the supported sizes, joined by supported gaps, with other
 * numbers around it joined by gaps or kept apart by field words, and no order label. Every position
 * of the card or id must end up inside a masked span.
 *
 * To see which positions were masked from the output alone, the sensitive number is written in one
 * digit form (ASCII or fullwidth) and every other digit in the other: a masked form only holds ASCII
 * characters and the scanner reads both forms alike, so a character of the number's own form left
 * outside the masked forms is a position that was not masked.
 */
class SensitiveNumbersPropertyTest {
    @Test
    fun `every position of a card or id among other numbers is masked`() {
        val random = Random(SEED)
        val leaks =
            (1..SAMPLES).mapNotNull { _ ->
                val sample = sample(random)
                val masked = SensitiveNumbers.mask(sample.text)
                val own = if (sample.fullwidth) FULLWIDTH else ASCII
                val left = masked.replace(MASKED_FORM, "").filter { it in own }
                if (left.isEmpty()) null else "${shown(sample.text)} -> ${shown(masked)}"
            }
        assertEquals(emptyList<String>(), leaks.take(SHOWN), "${leaks.size} of $SAMPLES samples leak")
    }

    private class Sample(
        val text: String,
        val fullwidth: Boolean,
    )

    private fun sample(random: Random): Sample {
        val isCard = random.nextBoolean()
        val number = if (isCard) card(random) else id(random)
        val sensitive = joined(split(number, if (isCard) MIN_CARD_GROUP else MIN_ID_GROUP, random), random)
        val fullwidth = random.nextBoolean()
        val before = (1..random.nextInt(NEIGHBOURS)).joinToString("") { neighbour(random, !fullwidth) + joiner(random) }
        val after = (1..random.nextInt(NEIGHBOURS)).joinToString("") { joiner(random) + neighbour(random, !fullwidth) }
        val text = pick(random, PREFIXES) + before + form(sensitive, fullwidth) + after + pick(random, SUFFIXES)
        return Sample(text, fullwidth)
    }

    /** A Luhn-valid card number of 15 to 19 digits. */
    private fun card(random: Random): String {
        val body = digits(random, MIN_CARD - 1 + random.nextInt(MAX_CARD - MIN_CARD + 1))
        return ('0'..'9').map { body + it }.single(SensitiveNumbers::isLuhnValid)
    }

    /** A valid resident id: region, birth date in 1900–2099, sequence and check character. */
    private fun id(random: Random): String {
        val region = "${1 + random.nextInt(RADIX - 1)}" + digits(random, REGION - 1)
        val year = FIRST_YEAR + random.nextInt(YEARS)
        val month = "${1 + random.nextInt(MONTHS)}".padStart(2, '0')
        val day = "${1 + random.nextInt(SAFE_DAYS)}".padStart(2, '0')
        val body = "$region$year$month$day" + digits(random, SEQUENCE)
        return "0123456789X".map { body + it }.single(SensitiveNumbers::isResidentIdValid)
    }

    /** [number] as one group, or in groups of at least [min] characters. */
    private fun split(
        number: String,
        min: Int,
        random: Random,
    ): List<String> {
        if (random.nextInt(3) == 0) return listOf(number)
        val groups = mutableListOf<String>()
        var rest = number
        while (rest.length >= 2 * min) {
            val size = min + random.nextInt(minOf(MAX_GROUP, rest.length - min) - min + 1)
            groups += rest.take(size)
            rest = rest.drop(size)
        }
        return groups + rest
    }

    /** [groups] joined by gaps, so they make one run of digits. */
    private fun joined(
        groups: List<String>,
        random: Random,
    ): String = groups.dropLast(1).joinToString("") { it + gap(random) } + groups.last()

    /** A supported gap: one to four spaces or invisible marks, with up to two dashes among them. */
    private fun gap(random: Random): String {
        val chars = (0..random.nextInt(GAP_CHARS)).map { pick(random, SPACES) }.toMutableList()
        repeat(random.nextInt(3)) { chars.add(random.nextInt(chars.size + 1), pick(random, DASHES)) }
        return chars.joinToString("")
    }

    /** What stands between two numbers: a gap (one run of digits) or a field word (two runs). */
    private fun joiner(random: Random): String = if (random.nextInt(4) == 0) pick(random, FIELD_WORDS) else gap(random)

    /** Another number: one to three groups of one to five digits, joined by gaps. */
    private fun neighbour(
        random: Random,
        fullwidth: Boolean,
    ): String {
        val groups = (0..random.nextInt(NEIGHBOUR_GROUPS)).map { digits(random, 1 + random.nextInt(NEIGHBOUR_DIGITS)) }
        return form(joined(groups, random), fullwidth)
    }

    private fun digits(
        random: Random,
        count: Int,
    ): String = (1..count).joinToString("") { "${random.nextInt(RADIX)}" }

    private fun form(
        text: String,
        fullwidth: Boolean,
    ): String =
        if (fullwidth) {
            text
                .map {
                    if (it in
                        ASCII
                    ) {
                        FULLWIDTH[ASCII.indexOf(it)]
                    } else {
                        it
                    }
                }.joinToString("")
        } else {
            text
        }

    private fun <T> pick(
        random: Random,
        from: List<T>,
    ): T = from[random.nextInt(from.size)]

    private fun pick(
        random: Random,
        from: String,
    ): Char = from[random.nextInt(from.length)]

    private fun shown(text: String): String =
        text
            .map { if (it.isLetterOrDigit() || it in VISIBLE) "$it" else "\\u%04X".format(Locale.ROOT, it.code) }
            .joinToString("")

    private companion object {
        const val SEED = 20_261_102L
        const val SAMPLES = 4000
        const val SHOWN = 10
        const val MIN_CARD = 15
        const val MAX_CARD = 19
        const val MIN_CARD_GROUP = 3
        const val MIN_ID_GROUP = 2
        const val MAX_GROUP = 6
        const val NEIGHBOURS = 3
        const val NEIGHBOUR_GROUPS = 3
        const val NEIGHBOUR_DIGITS = 5
        const val GAP_CHARS = 4
        const val REGION = 6
        const val SEQUENCE = 3
        const val FIRST_YEAR = 1900
        const val YEARS = 200
        const val MONTHS = 12
        const val SAFE_DAYS = 28
        const val RADIX = 10
        const val ASCII = "0123456789X"
        const val FULLWIDTH = "０１２３４５６７８９Ｘ"
        const val VISIBLE = "「」，。：/ "
        val MASKED_FORM = Regex("「[^」]*」")

        /** The 25 Unicode White_Space characters, and invisible format characters (category Cf). */
        val SPACES =
            (
                ('\u0009'..'\u000D') + ' ' + '\u0085' + '\u00A0' + '\u1680' + ('\u2000'..'\u200A') +
                    '\u2028' + '\u2029' + '\u202F' + '\u205F' + '\u3000' +
                    listOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u00AD', '\u200E')
            ).joinToString("")

        /** `-` and the other dashes the contract names. */
        const val DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2212\uFE63\uFF0D"
        val FIELD_WORDS = listOf(" 号 ", "，", "\n备注 ", " 共 ", "/")
        val PREFIXES = listOf("", "联系人 张三\n", "收款人 李四 ", "第 ", "备注：")
        val SUFFIXES = listOf("", " 已绑定", "\n", "。")
    }
}
