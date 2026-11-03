package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.screen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * How a button's wording is read (policy-rules.md §3): through the lexicon the screen format
 * marks ⚠ with. Written forms that should not change the answer: amounts, counts and notes in
 * brackets, zero-width characters, traditional characters, spaced letters, mixed scripts.
 */
class WordingTest {
    private val policy = Fx.rules()

    private fun kind(label: String): IntentKind? =
        policy.lexicon.activationRisk(el("e1", label), screen(el("e1", label)))?.kind

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "确认支付并同意《服务协议和扣款授权》 | SPEND",
            "立即付款（共计人民币123456.78元） | SPEND",
            "立即支付 ¥1,139.90 | SPEND",
            "确认支付 ¥1,299.00 | SPEND",
            "确认付款 ¥10000.00 | SPEND",
            "确认支付并同意服务协议和扣款授权及隐私政策 | SPEND",
            "付\u200B款 | SPEND",
            "轉帳 | SPEND",
            "發送 | OUTWARD",
            "刪除 | DELETE",
            "s e n d | OUTWARD",
            "S E N D | OUTWARD",
            "Send消息 | OUTWARD",
            "删除记录 | DELETE",
            "清空聊天记录 | DELETE",
            "发送验证码给张三 | OUTWARD",
            "Send postcode | OUTWARD",
            "发送(3) | OUTWARD",
            "支付宝支付 | SPEND",
        ],
    )
    fun `risky wording in any written form is caught`(
        label: String,
        kind: IntentKind,
    ) {
        assertEquals(kind, kind(label), label)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "支付宝",
            "支付寶",
            "付款记录",
            "转账记录",
            "发送记录",
            "发送验证码",
            "获取验证码",
            "重新发送验证码(59s)",
            "Send code",
            "Resend code",
            "删除搜索历史",
            "已付款",
            "评论(23)",
        ],
    )
    fun `names, statuses and codes to oneself stay harmless`(label: String) {
        assertEquals(null, kind(label), label)
    }

    @Test
    fun `an invisible mark inside a word does not hide it`() {
        val joiner = Char(GRAPHEME_JOINER).toString()
        assertEquals(IntentKind.OUTWARD, kind("发${joiner}送"))
        assertEquals(IntentKind.DELETE, kind("删${String(Character.toChars(VARIATION_SELECTOR))}除"))
    }
}

/** U+034F COMBINING GRAPHEME JOINER: a combining mark that shows nothing. */
private const val GRAPHEME_JOINER = 0x034F

/** U+E0100 VARIATION SELECTOR-17, outside the basic plane. */
private const val VARIATION_SELECTOR = 0xE0100
