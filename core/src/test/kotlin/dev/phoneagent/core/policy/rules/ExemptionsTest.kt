package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The exemptions that lower a risk (policy-rules.md §0): each one holds only on a label read
 * whole, and never over a risk named next to it. A label longer than [PolicyTuning.labelRead] is
 * padded here with "，", which counts as a character however the label is measured.
 */
class ExemptionsTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        s: ScreenSnapshot,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, s), on(s))

    private fun on(s: ScreenSnapshot): PolicyContext = ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg))

    private fun reason(verdict: Verdict): ConfirmReason =
        assertInstanceOf(Verdict.Confirm::class.java, verdict).card.reason

    private val submit = call("phone_type", "screen" to "s1", "ref" to "f1", "text" to "你好", "submit" to true)
    private val enter = call("phone_key", "key" to "enter")

    /** A focused field on the input row, and [others] on the same row. */
    private fun row(
        hint: String,
        vararg others: UiElement,
    ): ScreenSnapshot = screen(field("f1", hint = hint, focused = true, bounds = box(0, 900, 700, 960)), *others)

    /** An unnamed control on the input row showing [texts]: its label is them joined. */
    private fun shows(vararg texts: String): Array<UiElement> =
        arrayOf(el("e5", bounds = box(720, 900, 999, 960))) +
            texts.mapIndexed { i, t -> text("t${i + 1}", t, box(720, 900, 999, 960), parent = "e5") }

    @Test
    fun `a send button cut short is still a send button, so the field does not search`() {
        for (pad in listOf(" ".repeat(120), PAD)) {
            val s = row("搜索或提问", el("e1", "发送验证码${pad}给张三", bounds = box(720, 900, 999, 960)))
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), s)), pad)
            assertEquals(ConfirmReason.OUTWARD, reason(decide(submit, s)), pad)
            assertEquals(ConfirmReason.OUTWARD, reason(decide(enter, s)), pad)
        }
    }

    @Test
    fun `a control on the row whose label cannot be read whole may send, so the field does not search`() {
        // Only the joined label says "发送", once its note is taken out; that note closes past what is read.
        val s = row("搜索", *shows("发", "($PAD)", "送"))
        assertEquals(ConfirmReason.OUTWARD, reason(decide(submit, s)))
        assertEquals(ConfirmReason.OUTWARD, reason(decide(enter, s)))
        // Read whole, the same row searches.
        assertEquals(Verdict.Allow, decide(enter, row("搜索", *shows("发", "(说明)", "图"))))
    }

    @Test
    fun `a search button cut short is no search button`() {
        val s = row("说点什么", el("e1", "搜索$PAD", bounds = box(720, 900, 999, 960)))
        assertEquals(ConfirmReason.OUTWARD, reason(decide(enter, s)))
        assertEquals(Verdict.Allow, decide(enter, row("说点什么", el("e1", "搜索", bounds = box(720, 900, 999, 960)))))
    }

    @Test
    fun `a clear button cut short is no clear button`() {
        val s =
            screen(
                field("f1", bounds = box(0, 900, 999, 960)).copy(value = "abc"),
                el("e1", "清空$PAD", bounds = box(900, 910, 990, 950)),
            )
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), s)))
    }

    @Test
    fun `an escape button cut short does not leave a login page`() {
        val s = screen(el("e1", "关闭$PAD"), flags = setOf(ScreenFlag.LOGIN))
        assertEquals(HandoffKind.LOGIN, assertInstanceOf(Verdict.HandOff::class.java, decide(tap("e1"), s)).kind)
    }

    @Test
    fun `a code to oneself or a harmless delete is told only from a label read whole`() {
        val code = screen(*shows("发送验证码", PAD, "给张三"))
        assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e5"), code)))
        val cache = screen(*shows("清空缓存", PAD, "和全部聊天记录"))
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e5"), cache)))
        // Read whole, they are what they say.
        assertEquals(Verdict.Allow, decide(tap("e5"), screen(*shows("发送验证码", "到手机"))))
        assertEquals(Verdict.Allow, decide(tap("e5"), screen(*shows("清空缓存"))))
    }

    @Test
    fun `a harmless object does not excuse deleting something else`() {
        for (label in listOf("删除聊天记录和缓存", "清空缓存和聊天记录", "清除缓存及全部数据并删除")) {
            assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), screen(el("e1", label)))), label)
        }
        for (label in listOf("清空缓存", "删除搜索历史", "一键清理缓存", "Clear cache", "清空缓存（23.5MB）")) {
            assertEquals(Verdict.Allow, decide(tap("e1"), screen(el("e1", label))), label)
        }
    }

    @Test
    fun `a payment advert does not excuse a pay word after it`() {
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), screen(el("e1", "支持一下立即支付")))))
        assertEquals(Verdict.Allow, decide(tap("e1"), screen(el("e1", "支持花呗分期付款"))))
    }

    private companion object {
        /** Longer than the default [PolicyTuning.labelRead] by any measure. */
        val PAD = "，".repeat(130)
    }
}
