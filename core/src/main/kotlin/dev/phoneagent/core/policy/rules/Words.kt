package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind

/**
 * The risk vocabulary (docs/design/policy-rules.md §3). Data only; [Lexicon] does the matching.
 * Every entry is written in [Text.compact] form (lower case, no spaces) unless noted.
 *
 * Several lists and the matching approach (strong words count inside short labels unless they
 * read as a status; ambiguous words count only as the whole label) are adapted from Mika's
 * `IrreversibleDetector` (Copyright 2026 wangzan101, Apache-2.0). Modified by PhoneAgent
 * contributors. See NOTICE.
 */
internal object Words {
    /** Longest label (compact) in which a strong word still counts: longer text is content. */
    const val LABEL_MAX = 12

    /** Longest context text (dialog body) in which a strong word counts for a generic button. */
    const val CONTEXT_MAX = 40

    /** Count when they occur in a short label and are not a status ([STATUS_BEFORE], [STATUS_AFTER]). */
    val STRONG: List<Pair<String, IntentKind>> =
        listOf(
            "支付" to IntentKind.SPEND,
            "付款" to IntentKind.SPEND,
            "转账" to IntentKind.SPEND,
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

    /** English (spaced form): only the whole label ("Apply" alone is often a filter button: not listed). */
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

    /** A strong word followed (after punctuation) by one of these names a status or an entry ("付款成功", "删除记录"). */
    val STATUS_AFTER: List<String> =
        listOf("成功", "失败", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码", "设置", "详情", "说明", "须知", "协议", "中", "状态")

    /** Removed from a label before matching: names that contain a strong word but are not actions. */
    val NOT_ACTIONS: List<String> = listOf("支付宝", "支付方式", "付款方式", "支付设置", "货到付款", "先用后付", "花呗")

    /** Removed before matching: "支持微信支付" and similar advertise a payment method. */
    val PAYMENT_ADVERT: Regex = Regex("支持.{0,8}?(?:支付|付款)")

    /**
     * A label containing one of these is not of that kind at all. "发送验证码" sends a code to the
     * user's own phone, not to another person; clearing search history or a cache loses nothing
     * the user cannot get back (policy-rules.md §3.3).
     */
    val VETO: Map<IntentKind, List<String>> =
        mapOf(
            IntentKind.OUTWARD to listOf("验证码", "校验码", "动态码", "短信码", "code"),
            IntentKind.DELETE to listOf("搜索历史", "历史搜索", "搜索记录", "缓存", "searchhistory", "recentsearch", "cache"),
        )

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

    /** Clearing the text of an input ([Text.bare] form) is editing, not deleting. */
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
