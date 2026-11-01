package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class LexiconTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            // Strong words inside short labels.
            "立即支付 ¥39.90|SPEND|支付",
            "确认付款|SPEND|付款",
            "去结算(3)|SPEND|结算",
            "提交订单|SPEND|提交订单",
            "支付宝支付|SPEND|支付",
            "使用零钱付款|SPEND|付款",
            "已付款后再付款|SPEND|付款",
            "删除|DELETE|删除",
            "删除聊天记录|DELETE|删除",
            "撤回|DELETE|撤回",
            "发送(1)|OUTWARD|发送",
            "发 送|OUTWARD|发送",
            "转发|OUTWARD|转发",
            "打招呼|OUTWARD|打招呼",
            // Exact words, optionally with a prefix.
            "评论|OUTWARD|评论",
            "去评论|OUTWARD|评论",
            "提交！|OUTWARD|提交",
            "立即预订|SPEND|预订",
            "发红包|SPEND|发红包",
            "立即沟通|OUTWARD|沟通",
            // English.
            "Send message|OUTWARD|send",
            "SEND|OUTWARD|send",
            "Delete conversation|DELETE|delete",
            "Pay now|SPEND|pay",
            "Buy|SPEND|buy",
            "Place order.|SPEND|place order",
            "Submit|OUTWARD|submit",
            "Say hi!|OUTWARD|say hi",
        ],
    )
    fun `risky labels are recognized`(
        label: String,
        kind: IntentKind,
        matched: String,
    ) {
        assertEquals(Hit(kind, matched), Lexicon.classify(label))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            // Statuses and entries, not actions.
            "待付款", "已支付", "支付成功", "付款码", "支付方式", "支付宝", "已发送", "删除记录", "发送中",
            // Payment adverts.
            "支持花呗分期付款",
            // Sending a code to the user's own phone is not reaching another person (§3.3).
            "发送验证码", "重新发送验证码", "Send code",
            // Clearing search history or a cache loses nothing that matters (§3.3).
            "删除搜索历史", "清空搜索记录", "清空缓存", "Delete search history",
            // Exact words with more around them are entries and counters.
            "评论(23)", "回复 3", "提交搜索",
            // English words that only start with a risky word.
            "Payment methods", "Posts", "Sending…", "Apply",
            // Ordinary labels.
            "返回", "张三", "", "   ",
            // Content: long text that mentions a risky word.
            "这条消息里提到了删除两个字但它只是正文",
        ],
    )
    fun `ordinary labels are not risky`(label: String) {
        assertNull(Lexicon.classify(label))
    }

    @Test
    fun `strong words count in longer context text only when asked`() {
        val body = "确定要删除这条聊天记录吗？删除后将无法恢复"
        assertNull(Lexicon.classify(body))
        assertEquals(Hit(IntentKind.DELETE, "删除"), Lexicon.classify(body, Words.CONTEXT_MAX))
    }

    @Test
    fun `generic confirmations and escapes are told apart`() {
        listOf("确定", "确认!", "OK", "Confirm", "好的").forEach { assertTrue(Lexicon.isGeneric(it), it) }
        listOf("确定删除", "发送", "关闭").forEach { assertFalse(Lexicon.isGeneric(it), it) }
        listOf("关闭", "跳过", "暂不登录", "Not now", "Cancel").forEach { assertTrue(Lexicon.isEscape(it), it) }
        listOf("登录", "确定").forEach { assertFalse(Lexicon.isEscape(it), it) }
    }
}
