package dev.phoneagent.core.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

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
    }

    @Test
    fun `numbers of separate fields are never joined into one`() {
        // Concatenated, these would be a valid card (6222021234567894) or id (11010519491231002X).
        assertEquals("电话 6222021 金额 234567894", mask("电话 6222021 金额 234567894"))
        assertEquals("区号 110105，生日 19491231，尾号 002X", mask("区号 110105，生日 19491231，尾号 002X"))
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

    private companion object {
        /** The 25 characters of Unicode White_Space. */
        val UNICODE_SPACES =
            ('\u0009'..'\u000D') + ' ' + '\u0085' + '\u00A0' + '\u1680' + ('\u2000'..'\u200A') +
                '\u2028' + '\u2029' + '\u202F' + '\u205F' + '\u3000'
        const val HEX = 16
        const val SHORT_GROUPS = 20_000
        const val SCAN_SECONDS = 10L
    }
}
