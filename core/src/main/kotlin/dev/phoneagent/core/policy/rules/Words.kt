package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind

/**
 * The risk vocabulary (docs/design/policy-rules.md §3). Data only; [Lexicon] does the matching.
 * Every entry is written in [Text.compact] form (lower case, simplified, no spaces) unless noted.
 *
 * Several lists and the matching approach (strong words count inside short labels unless they
 * read as a status; ambiguous words count only as the whole label) are adapted from Mika's
 * `IrreversibleDetector` (Copyright 2026 wangzan101, Apache-2.0). Modified by PhoneAgent
 * contributors: more words and English phrases, status words split by kind, vetoes limited to
 * codes sent to the user's own phone, and length limits moved to [PolicyTuning]. See NOTICE.
 */
internal object Words {
    /** Count when they occur in a short label and are not a status ([STATUS_BEFORE], [STATUS_AFTER]). */
    val STRONG: List<Pair<String, IntentKind>> =
        listOf(
            "支付" to IntentKind.SPEND,
            "付款" to IntentKind.SPEND,
            "转账" to IntentKind.SPEND,
            "转帐" to IntentKind.SPEND,
            "购买" to IntentKind.SPEND,
            "下单" to IntentKind.SPEND,
            "提交订单" to IntentKind.SPEND,
            "结算" to IntentKind.SPEND,
            "买单" to IntentKind.SPEND,
            "充值" to IntentKind.SPEND,
            "缴费" to IntentKind.SPEND,
            "打赏" to IntentKind.SPEND,
            "提现" to IntentKind.SPEND,
            "续费" to IntentKind.SPEND,
            "确认收货" to IntentKind.SPEND,
            "抢购" to IntentKind.SPEND,
            "删除" to IntentKind.DELETE,
            "撤回" to IntentKind.DELETE,
            "清空" to IntentKind.DELETE,
            "移除" to IntentKind.DELETE,
            "注销" to IntentKind.DELETE,
            "卸载" to IntentKind.DELETE,
            "退款" to IntentKind.DELETE,
            "取消订单" to IntentKind.DELETE,
            "退出群聊" to IntentKind.DELETE,
            "解散群聊" to IntentKind.DELETE,
            "恢复出厂" to IntentKind.DELETE,
            "清除数据" to IntentKind.DELETE,
            "发送" to IntentKind.OUTWARD,
            "发布" to IntentKind.OUTWARD,
            "发表" to IntentKind.OUTWARD,
            "转发" to IntentKind.OUTWARD,
            "打招呼" to IntentKind.OUTWARD,
            "投递" to IntentKind.OUTWARD,
            "投简历" to IntentKind.OUTWARD,
        )

    /** Count only as the whole label (trailing punctuation ignored), optionally after a [PREFIXES] entry. */
    val EXACT: List<Pair<String, IntentKind>> =
        listOf(
            "红包" to IntentKind.SPEND,
            "发红包" to IntentKind.SPEND,
            "塞钱进红包" to IntentKind.SPEND,
            "预订" to IntentKind.SPEND,
            "预定" to IntentKind.SPEND,
            "开通" to IntentKind.SPEND,
            "开通会员" to IntentKind.SPEND,
            "订阅" to IntentKind.SPEND,
            "评论" to IntentKind.OUTWARD,
            "回复" to IntentKind.OUTWARD,
            "提交" to IntentKind.OUTWARD,
            "沟通" to IntentKind.OUTWARD,
            "联系他" to IntentKind.OUTWARD,
            "联系她" to IntentKind.OUTWARD,
            "联系ta" to IntentKind.OUTWARD,
            "应聘" to IntentKind.OUTWARD,
            "申请职位" to IntentKind.OUTWARD,
            "预约" to IntentKind.OUTWARD,
            "报名" to IntentKind.OUTWARD,
            "邀请" to IntentKind.OUTWARD,
            "拨打" to IntentKind.OUTWARD,
            "呼叫" to IntentKind.OUTWARD,
            "语音通话" to IntentKind.OUTWARD,
            "视频通话" to IntentKind.OUTWARD,
            "加好友" to IntentKind.OUTWARD,
            "添加好友" to IntentKind.OUTWARD,
            "添加到通讯录" to IntentKind.OUTWARD,
        )

    /**
     * Payments ([STRONG], [EXACT] and [ENGLISH_PREFIX] entries) that also send something to
     * someone, as a message in the conversation they are made from: a red packet, a transfer.
     * Their cards name the recipient and the draft as a send's do (policy-rules.md §6.3).
     */
    val SENDING_SPENDS: Set<String> = setOf("红包", "发红包", "塞钱进红包", "转账", "转帐", "send money")

    /** Prefixes an [EXACT] word may carry ("立即预订", "去评论"). */
    val PREFIXES: List<String> = listOf("", "立即", "马上", "一键", "去", "确认")

    /** English (spaced form): the label starts with the phrase at a word boundary ("Send message"). */
    val ENGLISH_PREFIX: List<Pair<String, IntentKind>> =
        listOf(
            "pay" to IntentKind.SPEND,
            "buy" to IntentKind.SPEND,
            "checkout" to IntentKind.SPEND,
            "check out" to IntentKind.SPEND,
            "place order" to IntentKind.SPEND,
            "purchase" to IntentKind.SPEND,
            "subscribe" to IntentKind.SPEND,
            "top up" to IntentKind.SPEND,
            "transfer" to IntentKind.SPEND,
            "send money" to IntentKind.SPEND,
            "donate" to IntentKind.SPEND,
            "book now" to IntentKind.SPEND,
            "withdraw" to IntentKind.SPEND,
            "delete" to IntentKind.DELETE,
            "remove" to IntentKind.DELETE,
            "clear all" to IntentKind.DELETE,
            "erase" to IntentKind.DELETE,
            "uninstall" to IntentKind.DELETE,
            "unsend" to IntentKind.DELETE,
            "factory reset" to IntentKind.DELETE,
            "send" to IntentKind.OUTWARD,
            "post" to IntentKind.OUTWARD,
            "reply" to IntentKind.OUTWARD,
            "publish" to IntentKind.OUTWARD,
            "forward" to IntentKind.OUTWARD,
        )

    /**
     * What joins two actions in an English label ([Text.phrase] form): "Send and delete", "Delete
     * & send", "Save + post", "Delete, then send". Only the joiner itself is taken: "and" and
     * "then" as whole words, at word boundaries, so joiners in a row ("and then", "& then") each
     * split and so do hyphenated ones ("Send-and-delete"); an [ENGLISH_SIGNS] sign with a space on
     * either side. The spaces and commas around a joiner stay with the parts, which are read
     * without them. A control's own label and a dialog also split at [ENGLISH_PAUSES]. Each part is read
     * by the [ENGLISH_PREFIX] phrase it starts with, never as an [ENGLISH_EXACT] one, where strong
     * words count: in a control's own label at any length ([Lexicon.ofControl]), elsewhere within
     * the length limit ([PolicyTuning.labelMax], [PolicyTuning.contextMax] in dialogs).
     */
    val ENGLISH_JOINERS: Regex = Regex("\\b(?:and|then)\\b|(?<=\\s)[&+/]|[&+/](?=\\s)")

    /**
     * "," and ";" before a space, joining actions in a control's own label and in a dialog only
     * ("Yes, delete", "Send; delete"): in the texts of a row they are a chat preview's ("Ok, send
     * it"), which opens the chat.
     */
    val ENGLISH_PAUSES: Regex = Regex("[,;](?=\\s)")

    /**
     * Signs that join two actions ("Send & delete", "Save + post", "Send / delete"; full-width
     * forms are these after NFKC). Without spaces they join only inside a word made of Latin
     * letters and that one sign ("Send&delete", "Send+pay", "Send/delete", "R&D"), read then as
     * if spaced; in any other word they are part of it ("example.com/buy",
     * "https://www.example.com/checkout/cart"). Names so joined ("Q&A", "Photos/Videos") split
     * into parts that start with no phrase; "Add/Remove" removes.
     */
    val ENGLISH_SIGNS: Set<Char> = setOf('&', '+', '/')

    /**
     * A word left at the start of a part after a joiner ("Send and also delete"): taken off, so
     * the part is read by the phrase after it. "and" and "then" are joiners themselves.
     */
    val ENGLISH_FILLERS: Regex = Regex("^(?:also\\b\\W*)+")

    /**
     * English (spaced form): only the whole label, as [EXACT] words ("Apply" alone is often a filter
     * button: not listed); one part of a label joining others ([ENGLISH_JOINERS]) is none.
     */
    val ENGLISH_EXACT: List<Pair<String, IntentKind>> =
        listOf(
            "submit" to IntentKind.OUTWARD,
            "comment" to IntentKind.OUTWARD,
            "call" to IntentKind.OUTWARD,
            "invite" to IntentKind.OUTWARD,
            "say hi" to IntentKind.OUTWARD,
            "say hello" to IntentKind.OUTWARD,
            "apply now" to IntentKind.OUTWARD,
            "easy apply" to IntentKind.OUTWARD,
            "add friend" to IntentKind.OUTWARD,
        )

    /** A strong word right after one of these is a status ("待付款", "已发送"). */
    val STATUS_BEFORE: Set<Char> = setOf('待', '已', '未')

    /** A strong word followed (after punctuation) by one of these names a status or an entry ("付款成功", "发送中"). */
    val STATUS_AFTER: List<String> =
        listOf("成功", "失败", "售后", "明细", "方式", "列表", "中心", "入口", "码", "设置", "详情", "说明", "须知", "协议", "中", "状态")

    /**
     * Status words of one kind only: "付款记录" and "发送记录" open a list, but "删除记录"
     * deletes records.
     */
    val STATUS_AFTER_KIND: Map<IntentKind, List<String>> =
        mapOf(
            IntentKind.SPEND to listOf("记录"),
            IntentKind.OUTWARD to listOf("记录"),
        )

    /** Removed from a label before matching: names that contain a strong word but are not actions. */
    val NOT_ACTIONS: List<String> = listOf("支付宝", "支付方式", "付款方式", "支付设置", "货到付款", "先用后付", "花呗")

    /**
     * Removed before matching: "支持微信支付" and similar advertise a payment method. Up to eight
     * letters between, none of them starting a call to act now ("支持一下立即支付" pays).
     */
    val PAYMENT_ADVERT: Regex = Regex("支持(?:(?!立即|马上|确认|现在|去)\\p{L}){0,8}?(?:支付|付款)")

    /**
     * The whole label ([Text.words] form) asks for a code sent to the user's own phone: not
     * reaching anyone ("发送验证码", "重新获取短信验证码"). With a recipient it is ("发送验证码给张三").
     */
    val SELF_CODE: Regex =
        Regex("(?:重新|再次)?(?:发送|获取)(?:短信|手机)?(?:验证码|校验码|动态码|短信码|动态密码)(?:到手机|至手机|到本机)?")

    /** [SELF_CODE] in English ([Text.phrase] form, trailing punctuation removed). */
    val SELF_CODE_EN: Regex =
        Regex("(?:re)?send (?:(?:a|the|an|sms|verification|one-time|login) ){0,4}(?:code|otp|passcode)")

    /**
     * What a delete may name and still delete nothing the user cannot get back ([Text.words] form,
     * longest first where one holds another; policy-rules.md §3.3).
     */
    val HARMLESS_DELETES: List<String> =
        listOf("搜索历史", "历史搜索", "搜索记录", "缓存", "searchhistory", "recentsearches", "recentsearch", "cache")

    /** Words a harmless delete may say besides its object ("一键清空全部缓存", "Clear all cache"). */
    val DELETE_FILLERS: List<String> =
        listOf(
            "删除",
            "清空",
            "清除",
            "移除",
            "清理",
            "一键",
            "立即",
            "马上",
            "确认",
            "确定",
            "全部",
            "所有",
            "的",
            "要",
            "是否",
            "吗",
            "您",
            "你",
            "delete",
            "clear",
            "remove",
            "all",
            "the",
            "my",
            "your",
        )

    /**
     * Sizes in [Text.compact] form ("128mb", "1.5g"), which a harmless delete may show. A size
     * starts only where no digit precedes and takes its digits without backtracking, so a match
     * costs time in proportion to what it covers.
     */
    val SIZES: Regex = Regex("(?<![\\d.,])\\d[\\d,.]*+(?:[kmgt]i?b?|b|字节)")

    /** Generic confirmations ([Text.bare] form): their risk comes from the dialog around them. */
    val GENERIC: Set<String> =
        setOf("确认", "确定", "好", "好的", "是", "完成", "继续", "同意", "ok", "okay", "yes", "confirm", "done", "continue", "agree")

    /** Leaving a page without doing anything ([Text.bare] form): allowed on hand-off pages. */
    val ESCAPES: Set<String> =
        setOf(
            "关闭",
            "跳过",
            "取消",
            "返回",
            "暂不登录",
            "以后再说",
            "稍后再说",
            "暂不",
            "不了",
            "知道了",
            "我知道了",
            "close",
            "skip",
            "cancel",
            "back",
            "notnow",
            "later",
            "nothanks",
            "dismiss",
        )

    /** Clearing an input is editing, not deleting: words in [Text.bare] form, symbols in [Text.compact] form. */
    val FIELD_CLEAR: Set<String> =
        setOf("清空", "清除", "清空输入", "清除输入", "清空内容", "清除内容", "清空搜索", "×", "✕", "x", "clear", "cleartext")

    /** The `matched` of an element without a label on a payment page: it may well be the pay button. */
    const val UNLABELED_ON_PAYMENT_PAGE: String = "unlabeled on a payment page"

    /** Resource-id words of icon buttons without a label ([Text.idWords]). */
    val ID_WORDS: Map<String, IntentKind> =
        mapOf(
            "send" to IntentKind.OUTWARD,
            "reply" to IntentKind.OUTWARD,
            "pay" to IntentKind.SPEND,
            "checkout" to IntentKind.SPEND,
            "delete" to IntentKind.DELETE,
            "remove" to IntentKind.DELETE,
        )
}
