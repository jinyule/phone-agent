package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaskingTest {
    @Test
    fun `card numbers that pass the Luhn check keep their last four digits`() {
        assertEquals("卡号 ************1111", Masking.mask("卡号 4111111111111111", null))
        assertEquals("**** **** **** 1111 到账", Masking.mask("4111 1111 1111 1111 到账", FieldClass.PLAIN))
        assertEquals("***************0128", Masking.mask("6222021234567890128", null))
        assertEquals("****-****-****-0004", Masking.mask("5500-0000-0000-0004", null))
    }

    @Test
    fun `numbers that are not cards stay`() {
        // Fails the Luhn check: an order number, not a card.
        assertEquals("订单 1234567812345678", Masking.mask("订单 1234567812345678", null))
        assertEquals("电话 13800138000", Masking.mask("电话 13800138000", null))
        assertEquals("明天 3 点见", Masking.mask("明天 3 点见", null))
    }

    @Test
    fun `PRC id numbers with a valid check digit keep their last four characters`() {
        // Synthetic numbers: area code 999999 does not exist.
        assertEquals("身份证 **************004X", Masking.mask("身份证 99999919000101004X", null))
        assertEquals("**************004x", Masking.mask("99999919000101004x", null))
        assertEquals("**************0058", Masking.mask("999999190001010058", null))
        // A wrong check digit is some other number.
        assertEquals("999999190001010050", Masking.mask("999999190001010050", null))
    }

    @Test
    fun `text typed into a card or id field is masked whole`() {
        assertEquals("**** 5678", Masking.mask("1234 5678", FieldClass.BANK_CARD))
        assertEquals("***2abc", Masking.mask("6222abc", FieldClass.ID_NUMBER))
        assertEquals("12", Masking.mask("12", FieldClass.BANK_CARD))
    }
}
