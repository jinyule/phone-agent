package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.SecurityLevel.CAUTIOUS
import dev.phoneagent.core.policy.SecurityLevel.STANDARD
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.APP
import dev.phoneagent.core.policy.rules.Fx.CLICK
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The re-check after approval (policy-rules.md §6.3): the runtime evaluates the call again on the
 * fresh screen S' with the approved card, and with the history the approval itself adds. Only an
 * equal card runs; whatever changed, or whatever can no longer be shown to be unchanged, asks again.
 */
class RecheckTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    private fun card(verdict: Verdict): ConfirmCard = assertInstanceOf(Verdict.Confirm::class.java, verdict).card

    /** The first evaluation, on the screen the model planned on. */
    private fun ask(
        call: ToolCall,
        on: ScreenSnapshot,
        level: SecurityLevel = STANDARD,
        risk: RiskClass = RiskClass.WRITE,
    ): ConfirmCard = card(decide(call, ctx(level, on, operated = setOf(APP)), risk))

    /** The re-check on [fresh] with [approved]; an approved outward card already counts for its app. */
    private fun recheck(
        call: ToolCall,
        approved: ConfirmCard,
        fresh: ScreenSnapshot,
        level: SecurityLevel = STANDARD,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict {
        val confirmed = if (approved.reason == ConfirmReason.OUTWARD) setOfNotNull(approved.pkg) else emptySet()
        val ctx = ctx(level, fresh, operated = setOf(APP), outwardConfirmed = confirmed).copy(approved = approved)
        return decide(call, ctx, risk)
    }

    private fun chat(
        contact: String = "张三",
        draft: String = "明天见",
        id: String = "s1",
    ): ScreenSnapshot =
        screen(
            text("t1", contact, box(0, 40, 500, 100)),
            field("e2", hint = "发消息", focused = true).copy(value = draft),
            el("e1", "发送", bounds = box(820, 900, 999, 960)),
            id = id,
        )

    /** A checkout showing [price] next to a pay control [pay]. */
    private fun checkout(
        price: String,
        pay: UiElement = el("e1", "去结算", bounds = box(600, 900, 999, 960)),
        id: String = "s1",
    ): ScreenSnapshot = screen(text("t1", "实付 $price", box(0, 900, 400, 960)), pay, id = id)

    @Test
    fun `standard - an approved outward tap asks again when the recipient or the draft changed`() {
        val approved = ask(tap("e1"), chat())
        assertEquals(ConfirmReason.OUTWARD, approved.reason)
        assertEquals(Verdict.Allow, recheck(tap("e1"), approved, chat(id = "s2")))
        // The app is already in outwardConfirmedPackages: the level would not ask, the re-check still compares.
        val other = card(recheck(tap("e1"), approved, chat(contact = "李四", id = "s2")))
        assertEquals("李四", other.target)
        val draft = card(recheck(tap("e1"), approved, chat(draft = "转我1000", id = "s2")))
        assertEquals("转我1000", draft.content)
        // The whole card counts, the action the user read included.
        val described = approved.copy(action = "发给李四")
        assertEquals(approved, card(recheck(tap("e1"), described, chat(id = "s2"))))
    }

    @Test
    fun `cautious - the same holds when the level asks every time`() {
        val approved = ask(tap("e1"), chat(), CAUTIOUS)
        assertEquals(Verdict.Allow, recheck(tap("e1"), approved, chat(id = "s2"), CAUTIOUS))
        assertEquals(
            ConfirmReason.OUTWARD,
            card(recheck(tap("e1"), approved, chat(draft = "x", id = "s2"), CAUTIOUS)).reason,
        )
    }

    @Test
    fun `a spend approval does not carry over to a control that now only navigates`() {
        val approved = ask(tap("e1"), checkout("¥20"))
        assertEquals(ConfirmReason.SPEND, approved.reason)
        val details = checkout("¥20", el("e1", "查看详情", bounds = box(600, 900, 999, 960)), id = "s2")
        assertInstanceOf(Verdict.Confirm::class.java, recheck(tap("e1"), approved, details))
        // Same label, but nothing says it pays any more: the approved SPEND card cannot be rebuilt.
        val sheet = screen(el("e1", "确定"), flags = setOf(ScreenFlag.PAYMENT))
        val ok = ask(tap("e1"), sheet)
        assertEquals(ConfirmReason.SPEND, ok.reason)
        assertInstanceOf(Verdict.Confirm::class.java, recheck(tap("e1"), ok, screen(el("e1", "确定"), id = "s2")))
    }

    @Test
    fun `typing that appends binds what the field will hold, not only the appended text`() {
        val box = { draft: String, id: String ->
            screen(field("e2", hint = "发消息", focused = true).copy(value = draft), id = id)
        }
        val type =
            call("phone_type", "screen" to "s1", "ref" to "e2", "text" to " world", "append" to true, "submit" to true)
        val approved = ask(type, box("hello", "s1"))
        assertEquals("hello world", approved.content)
        assertEquals(Verdict.Allow, recheck(type, approved, box("hello", "s2")))
        assertEquals("bye world", card(recheck(type, approved, box("bye", "s2"))).content)
    }

    @Test
    fun `the approval does not lift a refusal that appeared on the fresh screen`() {
        val approved = ask(tap("e1"), chat())
        val bank = chat(id = "s2").copy(pkg = "com.icbc")
        assertEquals(
            "hard.finance_app",
            assertInstanceOf(Verdict.Refuse::class.java, recheck(tap("e1"), approved, bank)).reason,
        )
    }

    @Test
    fun `every spend entry binds the amount - tap`() {
        val approved = ask(tap("e1"), checkout("¥20"))
        assertEquals(Money(2000, null), approved.amount)
        assertEquals(Verdict.Allow, recheck(tap("e1"), approved, checkout("¥20", id = "s2")))
        assertEquals(Money(9900, null), card(recheck(tap("e1"), approved, checkout("¥99", id = "s2"))).amount)
    }

    @Test
    fun `every spend entry binds the amount - enter on a focused pay button`() {
        val pay = el("e1", "付款", bounds = box(600, 900, 999, 960), states = CLICK + ElementState.FOCUSED)
        val enter = call("phone_key", "key" to "enter")
        val approved = ask(enter, checkout("¥20", pay))
        assertEquals(ConfirmReason.SPEND to Money(2000, null), approved.reason to approved.amount)
        assertEquals(Verdict.Allow, recheck(enter, approved, checkout("¥20", pay, "s2")))
        assertEquals(Money(9900, null), card(recheck(enter, approved, checkout("¥99", pay, "s2"))).amount)
    }

    @Test
    fun `every spend entry binds the amount - swipe on a pay slider`() {
        val slider = el("e1", "滑动付款", bounds = box(0, 800, 999, 880))
        val swipe = call("phone_swipe", "x1" to 50, "y1" to 840, "x2" to 950, "y2" to 840, "durationMs" to 800)
        val approved = ask(swipe, checkout("¥20", slider))
        assertEquals(ConfirmReason.SPEND to Money(2000, null), approved.reason to approved.amount)
        assertEquals(Verdict.Allow, recheck(swipe, approved, checkout("¥20", slider, "s2")))
        assertEquals(Money(9900, null), card(recheck(swipe, approved, checkout("¥99", slider, "s2"))).amount)
    }

    @Test
    fun `every spend entry binds the amount - request_confirmation without an amount argument`() {
        val ask = call("request_confirmation", "action" to "付款给张三", "target" to "张三", "content" to "货款")
        val approved = ask(ask, checkout("¥20"), risk = RiskClass.READ)
        assertEquals(ConfirmReason.REQUESTED_BY_MODEL to Money(2000, null), approved.reason to approved.amount)
        assertEquals(Verdict.Allow, recheck(ask, approved, checkout("¥20", id = "s2"), risk = RiskClass.READ))
        val pricier = recheck(ask, approved, checkout("¥99", id = "s2"), risk = RiskClass.READ)
        assertEquals(Money(9900, null), card(pricier).amount)
    }

    @Test
    fun `every spend entry binds the amount - a tool declared SPEND`() {
        val next = el("e1", "下一步", bounds = box(600, 900, 999, 960))
        val approved = ask(tap("e1"), checkout("¥20", next), risk = RiskClass.SPEND)
        assertEquals(ConfirmReason.SPEND to Money(2000, null), approved.reason to approved.amount)
        assertEquals(Verdict.Allow, recheck(tap("e1"), approved, checkout("¥20", next, "s2"), risk = RiskClass.SPEND))
        val pricier = recheck(tap("e1"), approved, checkout("¥99", next, "s2"), risk = RiskClass.SPEND)
        assertEquals(Money(9900, null), card(pricier).amount)
    }

    @Test
    fun `an amount that cannot be told is never taken as unchanged`() {
        // Two prices and no total: which one is paid cannot be told, so null == null proves nothing.
        val cart = { id: String ->
            screen(
                text("t1", "商品 ¥20", box(0, 300, 400, 340)),
                text("t2", "运费 ¥5", box(0, 400, 400, 440)),
                el("e1", "立即购买", bounds = box(600, 900, 999, 960)),
                id = id,
            )
        }
        val approved = ask(tap("e1"), cart("s1"))
        assertNull(approved.amount)
        assertInstanceOf(Verdict.Confirm::class.java, recheck(tap("e1"), approved, cart("s2")))
        // No amount anywhere is a different thing: nothing to bind, the equal card runs.
        val plain = { id: String -> screen(el("e1", "立即购买", bounds = box(600, 900, 999, 960)), id = id) }
        val none = ask(tap("e1"), plain("s1"))
        assertNull(none.amount)
        assertEquals(Verdict.Allow, recheck(tap("e1"), none, plain("s2")))
    }
}
