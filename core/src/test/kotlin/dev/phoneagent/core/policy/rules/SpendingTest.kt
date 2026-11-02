package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/** Spending at every level, with the amount on the card (policy-rules.md §3, §4). */
class SpendingTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    private fun on(
        s: ScreenSnapshot,
        level: SecurityLevel = SecurityLevel.STANDARD,
        unattended: Boolean = false,
    ): PolicyContext = ctx(level, s, unattended, setOfNotNull(s.pkg))

    private fun card(verdict: Verdict): ConfirmCard = assertInstanceOf(Verdict.Confirm::class.java, verdict).card

    private fun refusal(verdict: Verdict): String = assertInstanceOf(Verdict.Refuse::class.java, verdict).reason

    /** A checkout bar: [total] on the left, [pay] on the right. */
    private fun bar(
        total: String,
        pay: UiElement = el("e1", "提交订单", bounds = box(600, 900, 999, 960)),
    ): ScreenSnapshot = screen(text("t1", total, box(0, 900, 500, 960)), pay)

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "共3件 合计：¥88.00 | 8800",
            "合计(3件)：¥88.00 | 8800",
            "共计2件 ¥50.00 | 5000",
            "已选1件，合计¥39.90 | 3990",
            "合计 2 件 ¥20 | 2000",
            "合计 3 items ¥12.50 | 1250",
            // A bare number after the label loses to an amount with a currency mark.
            "合计(2) ¥20.00 | 2000",
        ],
    )
    fun `the amount is the one after the total label, and counts are not money`(
        total: String,
        minor: Long,
    ) {
        assertEquals(Money(minor, null), card(decide(tap("e1"), on(bar(total)))).amount, total)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "实付 JPY ¥100 | 10000 | JPY",
            "实付 $20 HKD | 2000 | HKD",
            "实付 US$20 | 2000 | USD",
            "实付 20元 | 2000 | CNY",
            "实付 RMB 20 | 2000 | CNY",
            "实付 €20 | 2000 | EUR",
            "实付 ¥20 | 2000 |",
            "实付 $20 | 2000 |",
        ],
    )
    fun `an explicit currency code wins, a lone dollar or yen sign tells no currency`(
        total: String,
        minor: Long,
        currency: String?,
    ) {
        assertEquals(Money(minor, currency), card(decide(tap("e1"), on(bar(total)))).amount, total)
    }

    @Test
    fun `an amount too large to hold is not read as some other amount`() {
        for (shown in listOf("¥9,999,999,999,999,999,999", "¥92,233,720,368,547,758.08", "¥1234567890123456")) {
            val verdict = assertDoesNotThrow(shown) { decide(tap("e1"), on(bar("实付 $shown"))) }
            assertEquals(ConfirmReason.SPEND, card(verdict).reason, shown)
            assertNull(card(verdict).amount, shown)
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `long pay labels still pay, at every level`(level: SecurityLevel) {
        val labels =
            mapOf(
                "确认支付 ¥1,299.00" to Money(129900, null),
                "立即支付 ¥1,139.90" to Money(113990, null),
                "确认付款 ¥10000.00" to Money(1000000, null),
                "立即付款（共计人民币123456.78元）" to Money(12345678, "CNY"),
                "确认支付并同意《服务协议和扣款授权》" to Money(129900, null),
            )
        for ((label, amount) in labels) {
            val s = bar("实付 ¥1,299.00", el("e1", label, bounds = box(500, 900, 999, 960)))
            val confirmed = card(decide(tap("e1"), on(s, level)))
            assertEquals(ConfirmReason.SPEND to amount, confirmed.reason to confirmed.amount, label)
            assertEquals("spend.unattended", refusal(decide(tap("e1"), on(s, level, unattended = true))), label)
        }
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a pay slider is spending, not a captcha`(level: SecurityLevel) {
        val slider = el("e1", "向右滑动支付", bounds = box(0, 800, 999, 880))
        val s = screen(text("t1", "实付 ¥20", box(0, 700, 500, 760)), slider)
        val swipe = call("phone_swipe", "x1" to 50, "y1" to 840, "x2" to 950, "y2" to 840, "durationMs" to 800)
        val confirmed = card(decide(swipe, on(s, level)))
        assertEquals(ConfirmReason.SPEND to Money(2000, null), confirmed.reason to confirmed.amount)
        assertEquals("spend.unattended", refusal(decide(swipe, on(s, level, unattended = true))))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a pay word anywhere in a control's own label pays, and an amount does not hide a pay word`(
        level: SecurityLevel,
    ) {
        val labels =
            mapOf(
                "我已阅读并同意服务协议现在支付并完成本次交易" to null,
                "订阅 ¥20" to Money(2000, null),
                "开通会员 ¥15/月" to Money(1500, null),
                "立即预订 ¥399" to Money(39900, null),
            )
        for ((label, amount) in labels) {
            val s = screen(el("e1", label, bounds = box(0, 900, 999, 960)))
            val confirmed = card(decide(tap("e1"), on(s, level)))
            assertEquals(ConfirmReason.SPEND to amount, confirmed.reason to confirmed.amount, label)
            assertEquals("spend.unattended", refusal(decide(tap("e1"), on(s, level, unattended = true))), label)
        }
        // A counter is not money: "评论(23)" stays an entry.
        assertEquals(Verdict.Allow, decide(tap("e1"), on(screen(el("e1", "评论(23)")), SecurityLevel.AUTONOMOUS)))
    }

    @Test
    fun `a long preview in a list row is not a button label`() {
        // The trade-off of policy-rules.md §3.3: a row shows several texts, read one by one.
        val row =
            screen(
                el("e1", bounds = box(0, 300, 999, 400)),
                text("t2", "张三", box(0, 300, 300, 340), parent = "e1"),
                text("t3", "我昨天已经帮你把这个月的房租支付给房东了别担心", box(0, 350, 999, 390), parent = "e1"),
            )
        assertEquals(Verdict.Allow, decide(tap("e1"), on(row)))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a pay slider drawn without any element still pays`(level: SecurityLevel) {
        val canvas =
            screen(
                ocr =
                    listOf(
                        Fx.ocr("实付 ¥20", box(0, 700, 500, 760)),
                        Fx.ocr("向右滑动支付", box(0, 800, 999, 880)),
                    ),
            )
        val swipe = call("phone_swipe", "x1" to 50, "y1" to 840, "x2" to 950, "y2" to 840, "durationMs" to 800)
        val confirmed = card(decide(swipe, on(canvas, level)))
        assertEquals(ConfirmReason.SPEND to Money(2000, null), confirmed.reason to confirmed.amount)
        assertEquals("spend.unattended", refusal(decide(swipe, on(canvas, level, unattended = true))))
    }

    @Test
    fun `an amount written in a form that cannot be read is unclear, not absent`() {
        for (shown in listOf("¥20.000", "¥12,34", "12,34", "¥1,2345.00")) {
            assertEquals(Price.Unclear, Prices.price(Prices.scan("实付 $shown").single()), shown)
        }
        val verdict = decide(tap("e1"), on(bar("实付 ¥20.000")))
        assertEquals(ConfirmReason.SPEND, card(verdict).reason)
        assertNull(card(verdict).amount)
    }

    @Test
    fun `request_confirmation reads its own amount argument`() {
        val ask =
            call(
                "request_confirmation",
                "action" to "给张三转账",
                "target" to "张三",
                "content" to "房租",
                "amount" to "¥1,200",
            )
        assertEquals(Money(120000, null), card(decide(ask, on(screen()), RiskClass.READ)).amount)
        val explicit =
            call(
                "request_confirmation",
                "action" to "转账",
                "target" to "张三",
                "content" to "x",
                "amount" to "100 HKD",
            )
        assertEquals(Money(10000, "HKD"), card(decide(explicit, on(screen()), RiskClass.READ)).amount)
        // An amount that cannot be read is an argument to fix, not a confirmation without the amount.
        for (bad in listOf("一些钱", "¥92,233,720,368,547,758.08")) {
            val call =
                call(
                    "request_confirmation",
                    "action" to "转账",
                    "target" to "张三",
                    "content" to "x",
                    "amount" to bad,
                )
            assertEquals("call.unresolved", refusal(decide(call, on(screen()), RiskClass.READ)), bad)
        }
    }
}
