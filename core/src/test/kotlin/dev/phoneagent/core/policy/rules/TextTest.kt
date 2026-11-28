package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

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
    fun `invisible characters that are not format characters are dropped too, visible marks stay`() {
        for (mark in INVISIBLE) {
            val code = "U+%04X".format(Locale.ROOT, mark)
            val split = "发" + String(Character.toChars(mark)) + "送"
            assertEquals("发送", Text.compact(split), code)
            // Two characters long, as labelRead counts.
            assertEquals(split.length, Text.prefix(split, 2), code)
        }
        // A combining accent shows: it is kept; so is a character outside the basic plane.
        assertEquals(3, Text.compact("发" + Char(ACUTE) + "送").length)
        val face = String(Character.toChars(FACE))
        assertEquals("发${face}送", Text.compact("发${face}送"))
    }

    @Test
    fun `a note runs to the next closing bracket of any kind, and an opening one without a closing one stays`() {
        assertEquals("ac", Text.words("a(b)c"))
        assertEquals("b", Text.words("(a]b"))
        assertEquals("b", Text.words("((a)b"))
        assertEquals("a(b", Text.words("a(b"))
        assertEquals("(y", Text.words("《x》(y"))
        assertEquals("pay later", Text.phrase("Pay (now) later"))
        assertEquals("(".repeat(5) + "x", Text.words("(".repeat(5) + "x"))
    }

    @Test
    fun `a currency mark further than eight spaces from a number is not part of the amount`() {
        assertEquals("", Text.words("¥" + " ".repeat(8) + "20元"))
        assertEquals("¥", Text.words("¥" + " ".repeat(9) + "20元"))
        assertEquals("x", Text.words(" ".repeat(100) + "x"))
    }

    @Test
    fun `words leave out notes, amounts and counts`() {
        assertEquals("同意并支付", Text.words("同意《服务协议》并支付 ¥1,299.00"))
        assertEquals("删除", Text.words("删除 3件"))
        assertEquals("pay now", Text.phrase("Pay US$ 12.50 now"))
        assertEquals(listOf("iv", "clear", "btn"), Text.idWords("com.x:id/ivClear.btn"))
    }
}

/**
 * Default-ignorable code points outside the format category: the grapheme joiner, a Khmer and a
 * Mongolian invisible vowel or selector, Hangul fillers (also the half-width one), variation
 * selectors (also the supplementary ones), and a tag character, which is a format character
 * outside the basic plane.
 */
private val INVISIBLE =
    listOf(0x034F, 0x115F, 0x1160, 0x17B4, 0x180B, 0x180F, 0x3164, 0xFE0F, 0xFFA0, 0xE0020, 0xE0100)

/** U+0301 COMBINING ACUTE ACCENT. */
private const val ACUTE = 0x0301

/** U+1F600 GRINNING FACE, outside the basic plane. */
private const val FACE = 0x1F600
