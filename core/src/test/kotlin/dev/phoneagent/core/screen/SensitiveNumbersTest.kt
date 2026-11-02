package dev.phoneagent.core.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.Locale
import java.util.Random

class SensitiveNumbersTest {
    private fun mask(text: String) = SensitiveNumbers.mask(text)

    @Test
    fun `masks Luhn-valid card numbers, grouped or not, keeping the last four digits`() {
        assertEquals("卡号「银行卡 尾号7894」", mask("卡号6222021234567894"))
        assertEquals("卡号 「银行卡 尾号7894」 已绑定", mask("卡号 6222 0212 3456 7894 已绑定"))
        assertEquals("「银行卡 尾号7894」", mask("6222-0212-3456-7894"))
        assertEquals("19 位：「银行卡 尾号5677」", mask("19 位：6217000010012345677"))
    }

    @Test
    fun `finds cards next to other numbers in the same digit run`() {
        assertEquals("第 3 「银行卡 尾号7894」", mask("第 3 6222 0212 3456 7894"))
        assertEquals("「银行卡 尾号7894」 「银行卡 尾号1111」", mask("6222021234567894 4111111111111111"))
        assertEquals("「银行卡 尾号7894」 12", mask("6222 0212 3456 7894 12"))
        // Neighbours of three or more digits that make a valid card with the first groups of the
        // real one: both are masked as one, so no part of the card is left readable.
        for (neighbour in listOf("105", "1004", "10005", "100004")) {
            assertEquals("第 「银行卡 尾号7894」", mask("第 $neighbour 6222 0212 3456 7894"), neighbour)
            assertEquals("第 「银行卡 尾号7894」 号", mask("第 $neighbour\n    6222 0212 3456 7894 号"), neighbour)
        }
        assertEquals("「银行卡 尾号7894」 105 号", mask("6222 0212 3456 7894 105 号"))
    }

    @Test
    fun `no number before a card in the same run leaves part of the card readable`() {
        val leaks =
            GAPS_BEFORE_CARD.flatMap { gap ->
                (100..999).map { "$it$gap$CARD_GROUPED" }.filterNot(::coversCard)
            }
        assertEquals(emptyList<String>(), leaks.map(::shown))
    }

    @Test
    fun `a phone number on the line before a card leaves no part of the card readable`() {
        val random = Random(SEED)
        val texts =
            (1..PHONE_SAMPLES).flatMap {
                val phone = "1" + (3 + random.nextInt(7)) + (1..9).joinToString("") { "${random.nextInt(10)}" }
                val written =
                    if (random.nextBoolean()) {
                        phone
                    } else {
                        "${phone.take(
                            3,
                        )} ${phone.substring(3, 7)} ${phone.drop(7)}"
                    }
                GAPS_BEFORE_CARD.map { gap -> "联系人 张三\n$written$gap$CARD_GROUPED" }
            }
        assertEquals(emptyList<String>(), texts.filterNot(::coversCard).map(::shown))
    }

    @Test
    fun `leaves numbers alone that are not cards`() {
        // Same digits with the check digit changed: not Luhn-valid.
        assertEquals("6222021234567895", mask("6222021234567895"))
        // Order and tracking numbers keep their digits even when they pass Luhn.
        assertEquals("订单号 6222021234567894", mask("订单号 6222021234567894"))
        assertEquals("Tracking: 6222021234567894", mask("Tracking: 6222021234567894"))
        // Too short, glued to letters, or split into tiny groups.
        assertEquals("电话 13800138000", mask("电话 13800138000"))
        assertEquals("AB6222021234567894", mask("AB6222021234567894"))
        // Glued at the end too, also when only the last group is: that group is part of a token.
        assertEquals("6222021234567894ab", mask("6222021234567894ab"))
        assertEquals("6222 0212 3456 7894ab", mask("6222 0212 3456 7894ab"))
        assertEquals("身份证 110105 19491231 002Xa", mask("身份证 110105 19491231 002Xa"))
        assertEquals("6 2 2 2 0 2 1 2 3 4 5 6 7 8 9 4", mask("6 2 2 2 0 2 1 2 3 4 5 6 7 8 9 4"))
        assertEquals("20 位 62220212345678941234", mask("20 位 62220212345678941234"))
        assertEquals("短文本 123", mask("短文本 123"))
    }

    @Test
    fun `an order label keeps a number only when it labels that number directly`() {
        assertEquals("订单号 6222021234567894 已提交", mask("订单号 6222021234567894 已提交"))
        assertEquals("订单号：6222 0212 3456 7894", mask("订单号：6222 0212 3456 7894"))
        assertEquals("运单号 6222021234567894", mask("运单号 6222021234567894"))
        assertEquals("订单编号\u00A06222021234567894", mask("订单编号\u00A06222021234567894"))
        assertEquals("Order No. 6222021234567894", mask("Order No. 6222021234567894"))
        assertEquals("tracking number: 6222021234567894", mask("tracking number: 6222021234567894"))
        // A label earlier in the text, not right before the number, exempts nothing, card word or not.
        assertEquals("订单已发货 「银行卡 尾号7894」", mask("订单已发货 6222021234567894"))
        assertEquals("Order shipped, 「银行卡 尾号7894」", mask("Order shipped, 6222021234567894"))
    }

    @Test
    fun `card words win over an order label, and a label that is not adjacent exempts nothing`() {
        val card = "「银行卡 尾号7894」"
        assertEquals("订单支付 银行卡 $card", mask("订单支付 银行卡 6222021234567894"))
        assertEquals("订单使用的银行卡 $card", mask("订单使用的银行卡 6222021234567894"))
        assertEquals("银行卡编号 $card", mask("银行卡编号 6222021234567894"))
        assertEquals("银行卡编号 $card", mask("银行卡编号 6222 0212 3456 7894"))
        assertEquals("订单支付卡号：$card", mask("订单支付卡号：6222 0212 3456 7894"))
        assertEquals("流水 对方卡号 $card", mask("流水 对方卡号 6222021234567894"))
        assertEquals("订单 6222 已付，储蓄卡 $card", mask("订单 6222 已付，储蓄卡 6222021234567894"))
        assertEquals("信用卡 订单号 $card", mask("信用卡 订单号 6222021234567894"))
        assertEquals("编号 $card", mask("编号 6222021234567894"))
        assertEquals("Recorder $card", mask("Recorder 6222021234567894"))
        assertEquals(
            "card on file for your order: 「银行卡 尾号1111」",
            mask("card on file for your order: 4111 1111 1111 1111"),
        )
    }

    @Test
    fun `masks cards grouped by Unicode spaces or written in fullwidth digits, in place`() {
        for (space in listOf("\u00A0", "\u2009", "\u202F", "\u3000")) {
            val text = "卡号 6222${space}0212${space}3456${space}7894"
            assertEquals("卡号 「银行卡 尾号7894」", mask(text), "U+" + space[0].code.toString(HEX))
        }
        assertEquals("卡号 「银行卡 尾号7894」", mask("卡号 ６２２２０２１２３４５６７８９４"))
        assertEquals("卡号 「银行卡 尾号7894」", mask("卡号 ６２２２　０２１２　３４５６　７８９４"))
        assertEquals(
            "卡号\u00A0「银行卡 尾号7894」\u3000已绑定",
            mask("卡号\u00A06222\u00A00212\u00A03456\u00A07894\u3000已绑定"),
            "the text around the number is kept as it was",
        )
        // Single digits spaced out stay what they are, in any space.
        assertEquals("6\u30002\u30002\u30002\u30000\u30002", mask("6\u30002\u30002\u30002\u30000\u30002"))
    }

    @Test
    fun `masks resident ids grouped or in fullwidth digits`() {
        assertEquals("身份证 「身份证 尾号002X」", mask("身份证 110105 19491231 002X"))
        assertEquals("身份证 「身份证 尾号002X」", mask("身份证 1101 0519 4912 3100 2X"))
        assertEquals("身份证 「身份证 尾号002X」", mask("身份证 110105\u00A019491231\u00A0002X"))
        assertEquals("身份证「身份证 尾号002X」", mask("身份证１１０１０５１９４９１２３１００２Ｘ"))
        assertEquals("「身份证 尾号1100」 「银行卡 尾号7894」", mask("320102 19880815 1100 6222 0212 3456 7894"))
        // Its last 15 digits alone are a valid card ending in the same place: the id, the longer one, names the span.
        assertEquals("身份证 「身份证 尾号0054」", mask("身份证 110 105194912310054"))
    }

    @Test
    fun `a field word keeps numbers apart, while numbers that only whitespace separates may be masked together`() {
        // Concatenated, these would be a valid card (6222021234567894) or id (11010519491231002X).
        assertEquals("电话 6222021 金额 234567894", mask("电话 6222021 金额 234567894"))
        assertEquals("区号 110105，生日 19491231，尾号 002X", mask("区号 110105，生日 19491231，尾号 002X"))
        // The price of reading a number wrapped over lines as one: two fields with only whitespace
        // between them read as one run, and a valid card in it is masked with both.
        assertEquals("「银行卡 尾号1001」", mask("13800138000\n\n    1001"))
        assertEquals("电话 13800138000\n\n金额 1001", mask("电话 13800138000\n\n金额 1001"))
        assertEquals(TWO_PHONES_MASKED, mask("138 0013 8000\n130 0222 0546"))
        assertEquals("电话 138 0013 8000\n电话 130 0222 0546", mask("电话 138 0013 8000\n电话 130 0222 0546"))
    }

    @Test
    fun `an order label exempts the number right after it across a gap of any length`() {
        val gap = " ".repeat(LONG_GAP)
        assertEquals("订单号：${gap}6222021234567894", mask("订单号：${gap}6222021234567894"))
        assertEquals(
            "Tracking number:\n\n${gap}6222 0212 3456 7894",
            mask("Tracking number:\n\n${gap}6222 0212 3456 7894"),
        )
        assertEquals("订单号 # ：6222021234567894", mask("订单号 # ：6222021234567894"))
        assertEquals("订单号＃６２２２０２１２３４５６７８９４", mask("订单号＃６２２２０２１２３４５６７８９４"))
        // Only the number right after the label: the next one in the run is masked.
        assertEquals(
            "订单号：${gap}6222021234567894 「银行卡 尾号1111」",
            mask("订单号：${gap}6222021234567894 4111111111111111"),
        )
        // The longest label, glued to a letter before it, is no label.
        assertEquals("Xtracking number 「银行卡 尾号7894」", mask("Xtracking number 6222021234567894"))
        assertEquals("$gap「银行卡 尾号7894」", mask("${gap}6222021234567894"))
    }

    @Test
    fun `masks resident ids with a valid check character and birth date`() {
        assertEquals("身份证「身份证 尾号002X」", mask("身份证11010519491231002X"))
        assertEquals("「身份证 尾号002X」", mask("11010519491231002x"))
        assertEquals("「身份证 尾号1100」", mask("320102198808151100"))
    }

    @Test
    fun `leaves id-shaped numbers alone when the check or the date is wrong`() {
        assertEquals("320102198808151101", mask("320102198808151101"))
        assertFalse(SensitiveNumbers.isResidentIdValid("320102198808151101"))
        assertFalse(SensitiveNumbers.isResidentIdValid("320102198813151100"), "month 13")
        assertFalse(SensitiveNumbers.isResidentIdValid("320102198802301100"), "30 February")
        assertFalse(SensitiveNumbers.isResidentIdValid("320102180008151100"), "year 1800")
        assertFalse(SensitiveNumbers.isResidentIdValid("020102198808151100"), "region starts with 0")
        assertFalse(SensitiveNumbers.isResidentIdValid("32010219880815110"), "17 characters")
        assertFalse(SensitiveNumbers.isResidentIdValid("3201021988081511X0"), "letter inside")
        assertTrue(SensitiveNumbers.isResidentIdValid("320102198808151119"))
    }

    @Test
    fun `Luhn check`() {
        assertTrue(SensitiveNumbers.isLuhnValid("6222021234567894"))
        assertTrue(SensitiveNumbers.isLuhnValid("4111111111111111"))
        assertFalse(SensitiveNumbers.isLuhnValid("4111111111111112"))
        assertThrows<IllegalArgumentException> { SensitiveNumbers.isLuhnValid("") }
        assertThrows<IllegalArgumentException> { SensitiveNumbers.isLuhnValid("4111-1111") }
    }

    @Test
    fun `every Unicode space, U+0085 included, separates digit groups`() {
        for (space in UNICODE_SPACES) {
            val text = "卡号 6222${space}0212${space}3456${space}7894"
            assertEquals("卡号 「银行卡 尾号7894」", mask(text), "U+" + space.code.toString(HEX))
        }
    }

    @Test
    fun `a gap of any length made of spaces and at most two dashes separates digit groups`() {
        val card = "卡号 「银行卡 尾号7894」"
        val gaps =
            listOf(
                "   ",
                "    ",
                "\r\n  ",
                " \u3000 ",
                "\u0085\u0085",
                " - ",
                "\n-\n",
                " -- ",
                "\t",
                " ".repeat(9),
                " ".repeat(40),
            )
        for (gap in gaps) {
            val text = "卡号 6222${gap}0212${gap}3456${gap}7894"
            assertEquals(card, mask(text), gap.map { "U+" + it.code.toString(HEX) }.toString())
        }
        // A number wrapped onto indented lines, and a column layout of one number.
        assertEquals("尾 「银行卡 尾号7894」 尾", mask("尾 6222\r\n            0212\r\n            3456\r\n            7894 尾"))
        assertEquals("「银行卡 尾号7894」", mask("6222 0212         3456 7894"))
        assertEquals("身份证 「身份证 尾号002X」", mask("身份证 110105\n\n    19491231\n\n    002X"))
    }

    @Test
    fun `minus signs and zero-width marks between groups count as separators`() {
        for (mark in listOf("\u2212", "\uFE63", "\u200B", "\u200C", "\u200D", "\u2060", "\uFEFF")) {
            val text = "卡号 6222${mark}0212${mark}3456${mark}7894"
            assertEquals("卡号 「银行卡 尾号7894」", mask(text), "U+" + mark[0].code.toString(HEX))
        }
        assertEquals("身份证 「身份证 尾号002X」", mask("身份证 110105\u221219491231\u2212002X"))
    }

    @Test
    fun `anything but spaces and up to two dashes between digit groups still separates fields`() {
        for (gap in listOf(" / ", " 号 ", " --- ", " . ", "·", " _ ", "\u00B7", " * ")) {
            val text = "6222 0212${gap}3456 7894"
            assertEquals(text, mask(text), gap)
        }
        // Each group still has to be long enough, and the whole has to pass its check.
        assertEquals("62 22 02 12 34 56 78 94", mask("62 22 02 12 34 56 78 94"))
        assertEquals("6222   0212   3456   7895", mask("6222   0212   3456   7895"))
    }

    @Test
    fun `a long run of short digit groups neither overflows the stack nor costs quadratic checks`() {
        // Gaps of any length make long runs; each start is tried only as far as a card can reach.
        val text = "1 ".repeat(SHORT_GROUPS) + "6222 0212 3456 7894"
        val masked = assertTimeoutPreemptively(Duration.ofSeconds(SCAN_SECONDS)) { mask(text) }
        assertEquals("1 ".repeat(SHORT_GROUPS) + "「银行卡 尾号7894」", masked)
    }

    @Test
    fun `many valid cards in one text cost time linear in its length`() {
        // Not a timing threshold: rescanning the whole text before each card for an order label
        // takes minutes on this input (about 6 s already for 4 000 cards, four times that per
        // doubling), a linear scan a fraction of a second. The bound only tells the two apart, with
        // room for a slow machine. The cards do not overlap: the merging is timed below.
        val text = "4111111111111111 ".repeat(MANY_CARDS)
        val masked = assertTimeoutPreemptively(Duration.ofSeconds(SLOW_BOUND_SECONDS)) { mask(text) }
        assertEquals("「银行卡 尾号1111」 ".repeat(MANY_CARDS), masked)
    }

    @Test
    fun `a long chain of overlapping numbers is merged in time linear in its length`() {
        // Every start makes a card (15 to 18 zeros) that overlaps the next one, so the whole run is
        // one span, extended once per group. Linear merging takes well under a second; rebuilding
        // the span's text at each extension is quadratic, about 18 s for 40 000 groups and four
        // times that per doubling, so minutes here. Again the bound only tells the two apart.
        val text = "000 ".repeat(CHAIN_GROUPS)
        val masked = assertTimeoutPreemptively(Duration.ofSeconds(SLOW_BOUND_SECONDS)) { mask(text) }
        assertEquals("「银行卡 尾号0000」 ", masked)
    }

    /** Whether masking [text], which ends in [CARD_GROUPED], leaves the card wholly inside the last masked span. */
    private fun coversCard(text: String): Boolean {
        val masked = mask(text)
        val kept = masked.removeSuffix(CARD_MASKED)
        return masked.endsWith(CARD_MASKED) && text.startsWith(kept) && kept.length <= text.length - CARD_GROUPED.length
    }

    private fun shown(text: String): String =
        text
            .map { if (it.isLetterOrDigit() || it == ' ') "$it" else "\\u%04X".format(Locale.ROOT, it.code) }
            .joinToString("") + " -> " + mask(text)

    private companion object {
        const val CARD_GROUPED = "6222 0212 3456 7894"
        const val CARD_MASKED = "「银行卡 尾号7894」"

        /** Two mobile numbers on consecutive lines, masked with each other as a card. */
        const val TWO_PHONES_MASKED = "138 「银行卡 尾号0222」 0546"

        /** Gaps between a number and the card after it in one run. */
        val GAPS_BEFORE_CARD = listOf(" ", "\n", "   ", "\n    ", "\r\n    ", "\u0085", "\n\n  ")
        const val SEED = 20_261_102L
        const val PHONE_SAMPLES = 1000
        const val LONG_GAP = 100
        const val MANY_CARDS = 40_000
        const val CHAIN_GROUPS = 160_000

        /** Far above a linear scan even on a slow machine, far below a quadratic one. */
        const val SLOW_BOUND_SECONDS = 60L

        /** The 25 characters of Unicode White_Space. */
        val UNICODE_SPACES =
            ('\u0009'..'\u000D') + ' ' + '\u0085' + '\u00A0' + '\u1680' + ('\u2000'..'\u200A') +
                '\u2028' + '\u2029' + '\u202F' + '\u205F' + '\u3000'
        const val HEX = 16
        const val SHORT_GROUPS = 20_000
        const val SCAN_SECONDS = 10L
    }
}
