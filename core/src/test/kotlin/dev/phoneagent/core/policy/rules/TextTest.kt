package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The one text normalization every list is matched with, and package names (policy-rules.md §3). */
class TextTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["com.tencent.mm", "a.b", "com.A_b.c9", "Com.Example.App"])
    fun `a package name is two or more dot-separated segments starting with a letter`(text: String) {
        assertTrue(Text.isPackage(text), text)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = ["", "微信", "tencent", "com..mm", "com.mm.", ".com.mm", "com.1a", "com._a", "com.a-b", "com.微信"],
    )
    fun `anything else is not a package name`(text: String) {
        assertFalse(Text.isPackage(text), text)
    }

    @Test
    fun `a package name is at most 255 characters`() {
        val longest = "a." + "b".repeat(253)
        assertTrue(Text.isPackage(longest))
        assertFalse(Text.isPackage(longest + "c"))
        assertFalse(Text.isPackage("a.".repeat(100_000) + "b"))
    }

    @Test
    fun `normalization folds width, case, traditional forms and invisible characters`() {
        assertEquals("确认付款¥88", Text.compact("確認付款 ￥88"))
        assertEquals("删除", Text.compact("刪\u200B除"))
        assertEquals("pay now", Text.spaced("  PAY \t NOW "))
        assertEquals("发送", Text.bare("发送！"))
        assertEquals("ok", Text.bare("OK."))
    }

    @Test
    fun `words leave out notes, amounts and counts`() {
        assertEquals("同意并支付", Text.words("同意《服务协议》并支付 ¥1,299.00"))
        assertEquals("删除", Text.words("删除 3件"))
        assertEquals("pay now", Text.phrase("Pay US$ 12.50 now"))
        assertEquals(listOf("iv", "clear", "btn"), Text.idWords("com.x:id/ivClear.btn"))
    }
}
