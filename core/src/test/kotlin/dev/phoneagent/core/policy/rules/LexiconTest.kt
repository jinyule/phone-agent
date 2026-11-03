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
    private val lexicon = Lexicon(PolicyTuning())

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
            "删除记录|DELETE|删除",
            "轉帳|SPEND|转账",
            "立即付款（共计人民币123456.78元）|SPEND|付款",
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
        assertEquals(kind to matched, lexicon.classify(label)?.let { it.kind to it.matched })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            // Statuses and entries, not actions.
            "待付款", "已支付", "支付成功", "付款码", "支付方式", "支付宝", "已发送", "付款记录", "发送中",
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
        assertNull(lexicon.classify(label))
    }

    @Test
    fun `a match tells whether its text also sends, whatever it does`() {
        // Strong words: any word that acts there; one that is a status does not.
        assertEquals(Hit(IntentKind.DELETE, "删除", sends = true), lexicon.classify("发送并删除原图"))
        assertEquals(Hit(IntentKind.DELETE, "删除", sends = false), lexicon.classify("删除已发送"))
        assertEquals(Hit(IntentKind.DELETE, "删除", sends = false), lexicon.classify("删除原图"))
        assertEquals(Hit(IntentKind.SPEND, "支付", sends = true), lexicon.ofControl("我已阅读并同意服务协议现在支付并发送给张三"))
        // Payments that send: red packets and transfers, in every reading.
        listOf("发红包", "立即发红包", "转账", "转账给张三", "Send money").forEach {
            assertEquals(IntentKind.SPEND to true, lexicon.classify(it)?.let { hit -> hit.kind to hit.sends }, it)
        }
        listOf("立即预订", "立即支付", "Pay now").forEach {
            assertEquals(IntentKind.SPEND to false, lexicon.classify(it)?.let { hit -> hit.kind to hit.sends }, it)
        }
        listOf("评论", "发送", "Send message", "Submit").forEach { assertTrue(lexicon.classify(it)?.sends == true, it) }
        assertTrue(lexicon.ofLabel("转账给张三" + "，".repeat(130))?.sends == true)
        assertFalse(lexicon.ofLabel("立即支付" + "，".repeat(130))?.sends == true)
    }

    @Test
    fun `a control's own English label is read part by part where it joins actions`() {
        val sendDelete = Hit(IntentKind.DELETE, "delete", sends = true)
        for (label in listOf(
            "Send and delete",
            "Delete and send",
            "DELETE & SEND",
            "Send + delete",
            "Delete, then send!",
            "Send and then delete",
            "Send & then delete",
            "Send then then delete",
            "Send&delete",
            "Send＆delete",
            "Send,then delete",
            "Send and also delete",
            "Send/delete",
            "Send+delete",
            "Send-and-delete",
        )) {
            assertEquals(sendDelete, lexicon.ofControl(label), label)
        }
        val names = listOf("Q&A", "R&D", "AT&T", "Terms&Conditions", "Photos/Videos")
        names.forEach { assertNull(lexicon.ofControl(it), it) }
        // A sign without spaces joins only inside a word of Latin letters and that sign.
        val links =
            listOf("example.com/buy", "https://www.example.com/checkout/cart", "example.com/remove-background-tips")
        links.forEach { assertNull(lexicon.ofControl(it), it) }
        // A sign joins alone between spaces, not at the edge of a word: an address wrapped after its slash.
        val wrapped = listOf("https://www.example.com/\ncheckout/cart", "example.com/ remove-background-tips")
        wrapped.forEach { assertNull(lexicon.ofControl(it), it) }
        assertEquals(Hit(IntentKind.OUTWARD, "send"), lexicon.ofControl("Send&好的"))
        // Other text is split where each part is within the length limit, however long the whole.
        assertEquals(sendDelete, lexicon.classify("Send and delete"))
        assertEquals(sendDelete, lexicon.inContext("Send and then delete this?"))
        assertEquals(Hit(IntentKind.SPEND, "pay", sends = true), lexicon.ofControl("Reply and pay"))
        // A part that names nothing adds nothing; a single part reads as before.
        assertEquals(Hit(IntentKind.OUTWARD, "post"), lexicon.ofControl("Save and post"))
        assertEquals(Hit(IntentKind.DELETE, "delete"), lexicon.ofControl("Delete photos and videos"))
        listOf("Terms and conditions", "Photos and videos", "Payments & transfers", "Send code").forEach {
            assertNull(lexicon.ofControl(it), it)
        }
        // Each part is read by the phrase it starts with; an exact phrase is only ever the whole label.
        assertEquals(Hit(IntentKind.OUTWARD, "invite"), lexicon.ofControl("Invite"))
        assertEquals(Hit(IntentKind.SPEND, "pay"), lexicon.ofControl("Scan & pay"))
        listOf("Invite and earn", "Like and comment").forEach { assertNull(lexicon.ofControl(it), it) }
        assertNull(lexicon.classify("Invite & earn"))
        // A code to oneself is told from the whole label only, also where the text is split only once.
        assertEquals(sendDelete, lexicon.ofControl("Send code and delete"))
        assertEquals(sendDelete, lexicon.inContext("Send code and delete"))
        // Other text is split within the length limit only, as strong words count: past it, only its start.
        assertEquals(sendDelete, lexicon.classify("Send & delete"))
        assertEquals(Hit(IntentKind.OUTWARD, "send"), lexicon.classify("Send it to me and delete the rest"))
        assertNull(lexicon.classify("Please review it and pay the bill"))
        assertEquals(sendDelete, lexicon.inContext("Send this and delete the chat?"))
    }

    @Test
    fun `of matches read as one, the most severe counts, the first among equals, and it sends when any does`() {
        val delete = Hit(IntentKind.DELETE, "删除")
        val send = Hit(IntentKind.OUTWARD, "发送")
        val sending = delete.copy(sends = true)
        assertEquals(null, firstMax(null, null))
        assertEquals(send, firstMax(null, send))
        assertEquals(send, firstMax(send, null))
        assertEquals(sending, firstMax(send, delete))
        assertEquals(sending, firstMax(delete, send))
        assertEquals(sending, firstMax(sending, delete))
        assertEquals(delete, firstMax(delete, Hit(IntentKind.DELETE, "清空")))
        assertEquals(Hit(IntentKind.SPEND, "支付"), firstMax(delete, Hit(IntentKind.SPEND, "支付")))
    }

    @Test
    fun `strong words count in longer context text only when asked`() {
        val body = "确定要删除这条聊天记录吗？删除后将无法恢复"
        assertNull(lexicon.classify(body))
        assertEquals(Hit(IntentKind.DELETE, "删除"), lexicon.inContext(body))
        assertNull(lexicon.inContext(body.repeat(3)))
    }

    @Test
    fun `a strong word anywhere in a control's own label counts at any length`() {
        val long = "确认支付并同意服务协议和扣款授权及隐私政策"
        assertNull(lexicon.classify(long))
        assertEquals(Hit(IntentKind.SPEND, "支付"), lexicon.ofControl(long))
        assertEquals(Hit(IntentKind.DELETE, "删除"), lexicon.ofControl("立即删除这条消息和它的所有回复以及附件"))
        assertEquals(Hit(IntentKind.SPEND, "支付"), lexicon.ofControl("我已阅读并同意服务协议现在支付并完成本次交易"))
        assertEquals(Hit(IntentKind.DELETE, "删除"), lexicon.ofControl("这条消息里提到了删除两个字但它只是正文"))
        // Statuses and codes to oneself stay what they are at any length.
        assertNull(lexicon.ofControl("待付款的订单一共有三个请尽快处理"))
        assertNull(lexicon.ofControl("发送验证码到手机"))
        // A row's texts joined: only a strong word at the start counts.
        assertEquals(Hit(IntentKind.SPEND, "支付"), lexicon.ofLabel(long))
        assertNull(lexicon.ofLabel("这条消息里提到了删除两个字但它只是正文"))
    }

    @Test
    fun `an exemption holds only on a label read whole`() {
        val pad = "，".repeat(130)
        assertNull(lexicon.ofLabel("发送验证码"))
        assertEquals(Hit(IntentKind.OUTWARD, "发送"), lexicon.ofLabel("发送验证码${pad}给张三"))
        assertNull(lexicon.ofLabel("Send code"))
        assertEquals(IntentKind.OUTWARD, lexicon.ofLabel("Send code$pad")?.kind)
        assertNull(lexicon.ofLabel("清空缓存"))
        assertEquals(IntentKind.DELETE, lexicon.ofLabel("清空缓存$pad")?.kind)
        // A harmless delete names nothing else, sizes aside.
        listOf("清空缓存 128MB", "清除缓存（1.5G）", "Clear recent searches", "一键清理全部缓存").forEach {
            assertNull(lexicon.ofLabel(it), it)
        }
        listOf("删除聊天记录和缓存", "清空缓存和相册", "Delete cache and photos").forEach {
            assertEquals(IntentKind.DELETE, lexicon.ofLabel(it)?.kind, it)
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "订阅 ¥20|SPEND|订阅",
            "开通会员 ¥15/月|SPEND|开通会员",
            "¥399 立即预订|SPEND|预订",
            "立即预订（US$399 起）|SPEND|预订",
            "订阅 20元|SPEND|订阅",
        ],
    )
    fun `an amount with a currency mark does not hide a whole word`(
        label: String,
        kind: IntentKind,
        matched: String,
    ) {
        assertEquals(kind to matched, lexicon.classify(label)?.let { it.kind to it.matched })
    }

    @Test
    fun `generic confirmations and escapes are told apart`() {
        listOf("确定", "确认!", "OK", "Confirm", "好的").forEach { assertTrue(lexicon.isGeneric(it), it) }
        listOf("确定删除", "发送", "关闭").forEach { assertFalse(lexicon.isGeneric(it), it) }
        listOf("关闭", "跳过", "暂不登录", "Not now", "Cancel").forEach { assertTrue(lexicon.isEscape(it), it) }
        listOf("登录", "确定").forEach { assertFalse(lexicon.isEscape(it), it) }
    }
}
