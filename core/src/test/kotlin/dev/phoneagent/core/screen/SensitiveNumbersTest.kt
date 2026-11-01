package dev.phoneagent.core.screen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
        assertEquals("6 2 2 2 0 2 1 2 3 4 5 6 7 8 9 4", mask("6 2 2 2 0 2 1 2 3 4 5 6 7 8 9 4"))
        assertEquals("20 位 62220212345678941234", mask("20 位 62220212345678941234"))
        assertEquals("短文本 123", mask("短文本 123"))
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
}
