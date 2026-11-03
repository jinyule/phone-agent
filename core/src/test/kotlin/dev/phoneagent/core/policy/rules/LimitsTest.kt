package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.call
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The limits on what is read of a screen, and the index that keeps reading in proportion to it (§8). */
class LimitsTest {
    private fun rules(tuning: PolicyTuning): PolicyRules = PolicyRules(setOf(Fx.OWN), setOf(Fx.OWN_LABEL), tuning)

    private fun decide(
        policy: PolicyRules,
        call: ToolCall,
        ctx: PolicyContext,
        risk: RiskClass = RiskClass.WRITE,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, risk, ctx.screen), ctx)

    private fun refusal(verdict: Verdict): String = assertInstanceOf(Verdict.Refuse::class.java, verdict).reason

    /** Every screen action and observing, as the model may call them on [s]. */
    private fun screenCalls(s: ScreenSnapshot): List<ToolCall> =
        listOf(
            tap(
                s.elements
                    .first()
                    .ref.value,
            ),
            call("phone_tap", "x" to 50, "y" to 425),
            call("phone_type", "text" to "你好", "submit" to true),
            call("phone_key", "key" to "enter"),
            call("phone_swipe", "x1" to 50, "y1" to 425, "x2" to 950, "y2" to 425, "durationMs" to 300),
            call(
                "phone_scroll",
                "screen" to "s1",
                "ref" to
                    s.elements
                        .first()
                        .ref.value,
            ),
            call("phone_observe"),
        )

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a screen beyond a limit is not read - every action on it and reading it are refused`(level: SecurityLevel) {
        val pay = el("e1", "立即支付")
        val tight = PolicyTuning(maxElements = 3, maxScreenText = 20, maxDepth = 1)
        val policy = rules(tight)
        val beyond =
            listOf(
                screen(pay, el("e2", "a"), el("e3", "b"), el("e4", "c")),
                // OCR lines count with the elements.
                screen(pay, el("e2", "a"), ocr = listOf(ocr("x"), ocr("y"))),
                // Names, values, hints, resource ids and OCR lines count as text.
                screen(pay, field("f1", hint = "说点什么").copy(value = "x".repeat(8), resourceId = "a:id/b")),
                screen(pay, el("e2", "a"), ocr = listOf(ocr("x".repeat(17)))),
                screen(pay, text("t1", "a", parent = "e1"), text("t2", "b", parent = "t1")),
            )
        for (s in beyond) {
            assertFalse(Reading(tight).readable(s), s.toString())
            assertNull(policy.lexicon.activationRisk(pay, s))
            for (c in screenCalls(s)) {
                assertEquals("screen.unreadable", refusal(decide(policy, c, ctx(level, s))), "${c.name} on $s")
            }
            // The way out the refusal names stays open.
            for (key in listOf("back", "home")) {
                assertEquals(Verdict.Allow, decide(policy, call("phone_key", "key" to key), ctx(level, s)), key)
            }
        }
        // At each limit it is read.
        val at = screen(pay, text("t1", "合计 ¥20", parent = "e1"), ocr = listOf(ocr("12345678")))
        assertTrue(Reading(tight).readable(at))
        assertEquals(IntentKind.SPEND, policy.lexicon.activationRisk(pay, at)?.kind)
    }

    @Test
    fun `a tree whose parents form a cycle is not read`() {
        val a = el("e1", "支付").copy(parent = ElementRef("e2"))
        val b = el("e2", "确认").copy(parent = ElementRef("e1"))
        val s = screen(a, b)
        assertFalse(Reading(PolicyTuning()).readable(s))
        assertEquals("screen.unreadable", refusal(decide(Fx.rules(), tap("e1"), ctx(screen = s))))
    }

    @Test
    fun `without the screen read, typing that submits and Enter reach out, and the app is still known`() {
        val s = screen(el("e1", "支付"), el("e2", "x"))
        val resolver = rules(PolicyTuning(maxElements = 1)).resolver
        val typed = resolver.resolve(call("phone_type", "text" to "hi", "submit" to true), RiskClass.WRITE, s)
        assertEquals(IntentKind.OUTWARD to Fx.APP, typed.kind to typed.pkg)
        assertEquals(IntentKind.OUTWARD, resolver.resolve(call("phone_key", "key" to "enter"), RiskClass.WRITE, s).kind)
        val tapped = resolver.resolve(tap("e1"), RiskClass.WRITE, s)
        assertEquals(Triple(IntentKind.NAVIGATE, Fx.APP, null), Triple(tapped.kind, tapped.pkg, tapped.target))
    }

    @Test
    fun `the limits leave calls that do not touch the screen alone, and refused apps are named first`() {
        val policy = rules(PolicyTuning(maxElements = 1))
        val s = screen(el("e1", "支付"), el("e2", "x"))
        val launch = call("phone_launch", "app" to "com.example.notes")
        assertEquals(Verdict.Allow, decide(policy, launch, ctx(SecurityLevel.STANDARD, s)))
        val ask = call("request_confirmation", "action" to "转账", "target" to "张三", "content" to "x")
        assertInstanceOf(
            Verdict.Confirm::class.java,
            decide(policy, ask, ctx(SecurityLevel.STANDARD, s), RiskClass.READ),
        )
        val bank = s.copy(pkg = "com.chinamworld.main")
        assertEquals("hard.finance_app", refusal(decide(policy, tap("e1"), ctx(SecurityLevel.STANDARD, bank))))
    }

    @Test
    fun `a spend whose screen is not read has an unclear amount, so its approval never passes`() {
        val policy = rules(PolicyTuning(maxElements = 1))
        val s = screen(el("e1", "实付 ¥20"), el("e2", "x"))
        val launch = call("phone_launch", "app" to "com.example.shop")
        val first = decide(policy, launch, ctx(SecurityLevel.STANDARD, s), RiskClass.SPEND)
        val card = assertInstanceOf(Verdict.Confirm::class.java, first).card
        assertEquals(ConfirmReason.SPEND to null, card.reason to card.amount)
        val again = decide(policy, launch, ctx(SecurityLevel.STANDARD, s).copy(approved = card), RiskClass.SPEND)
        assertEquals(Verdict.Confirm(card), again)
    }

    @Test
    fun `at most totalsRead total labels are read, and with more and no amount after them the amount is unclear`() {
        val row = box(0, 900, 999, 960)
        val pay = el("e1", "立即支付", bounds = box(0, 100, 200, 150))
        val labels = (1..3).map { text("t$it", "合计", row) }
        val s = screen(*labels.toTypedArray(), pay, text("t9", "¥20", box(0, 500, 200, 550)))
        val spend = { tuning: PolicyTuning, on: ScreenSnapshot ->
            rules(tuning).resolver.resolve(tap("e1"), RiskClass.WRITE, on).amount
        }
        // All three read: none has an amount after it, so the only amount shown counts.
        assertEquals(Money(2000, null), spend(PolicyTuning(), s))
        assertNull(spend(PolicyTuning(totalsRead = 2), s))
        // The best label read that has an amount after it decides.
        val shown = screen(text("t1", "合计 ¥5", row), *labels.drop(1).toTypedArray(), pay)
        assertEquals(Money(500, null), spend(PolicyTuning(totalsRead = 1), shown))
    }

    @Test
    fun `risk checks read the first labelRead characters of a label, and a longer label is no exempting word`() {
        val lexicon = Lexicon(PolicyTuning(labelRead = 6))
        assertEquals("确认支付并同", lexicon.read("确认支付并同意协议"))
        assertTrue(lexicon.whole("关闭    "))
        assertFalse(lexicon.whole("关闭     "))
        assertTrue(lexicon.isEscape("关闭"))
        assertFalse(lexicon.isEscape("关闭     "))
        assertTrue(lexicon.isGeneric("确定     删除"))
        // A clear button whose label is longer than what is read is not one.
        val input = field("f1", bounds = box(0, 100, 800, 160)).copy(value = "abc")
        val clear = el("b1", "清空      ", bounds = box(700, 110, 790, 150))
        val fields = Reading(PolicyTuning(labelRead = 6)).fields
        val s = screen(input, clear)
        assertFalse(Reading(PolicyTuning(labelRead = 6)).editsField(clear, listOf("清空      "), s))
        assertTrue(Reading(PolicyTuning()).editsField(clear, listOf("清空      "), s))
        // Nor is a search button, and a field next to it is no search field.
        val bare = field("f2", bounds = box(0, 300, 800, 360))
        val search = el("b2", "搜索      ", bounds = box(820, 300, 999, 360))
        assertFalse(fields.searchButton(search, screen(bare, search)))
        assertTrue(Reading(PolicyTuning()).fields.searchButton(search, screen(bare, search)))
        assertFalse(Reading(PolicyTuning(labelRead = 6)).searches.isSearch(bare, screen(bare, search)))
        assertTrue(Reading(PolicyTuning()).searches.isSearch(bare, screen(bare, search)))
    }

    @Test
    fun `the index reads each snapshot object once, and another object again`() {
        var built = 0
        val memo = PerScreen { s -> built++.let { s.elements.size } }
        val s = screen(el("e1"))
        assertEquals(1, memo.of(s))
        assertEquals(1, memo.of(s))
        assertEquals(1, built)
        assertEquals(1, memo.of(s.copy()))
        assertEquals(2, built)
        assertSame(ScreenIndex.of(s), ScreenIndex.of(s))
        assertNotSame(ScreenIndex.of(s), ScreenIndex.of(s.copy()))
    }

    @Test
    fun `the index measures depth and text, and finds parents, children and controls`() {
        val row = el("e1", bounds = box(0, 0, 999, 500)).copy(resourceId = "a:id/row")
        val name = text("t1", "张三", parent = "e1")
        val deep = text("t2", "在线", parent = "t1").copy(value = "v", hint = "h")
        val orphan = text("t3", "无主", parent = "e9")
        val s = screen(row, name, deep, orphan, ocr = listOf(ocr("图上")))
        val index = ScreenIndex.of(s)
        assertEquals(2, index.depth)
        assertEquals("a:id/row".length + 2 + 2 + 2 + 2 + 2L, index.textLength)
        assertEquals(listOf(name), index.children(row))
        assertEquals(listOf(name, row), index.ancestors(deep).toList())
        assertTrue(index.inControl(deep))
        assertFalse(index.inControl(row))
        assertFalse(index.inControl(orphan))
        assertNull(index.byRef(ElementRef("e9")))
        assertEquals(0, ScreenIndex.of(screen(el("e1"), el("e2"))).depth)
        // Content is read once for an element of the snapshot; another object with its ref is read as it is.
        assertEquals(listOf("张三", "在线"), Elements.content(row, s))
        assertSame(Elements.content(row, s), Elements.content(row, s))
        val moved = row.copy(bounds = box(0, 600, 999, 999))
        assertEquals(listOf("张三", "在线"), Elements.content(moved, s))
        assertEquals(emptyList<String>(), Elements.content(el("e8"), s))
    }

    @Test
    fun `a touch reaches every innermost control, also several under one parent`() {
        val card = el("e1", "卡片", bounds = box(0, 0, 999, 500))
        val a = el("e2", "甲", bounds = box(100, 100, 300, 300)).copy(parent = ElementRef("e1"))
        val b = el("e3", "乙", bounds = box(100, 100, 400, 400)).copy(parent = ElementRef("e1"))
        val inA = el("e4", "丙", bounds = box(150, 150, 250, 250)).copy(parent = ElementRef("e2"))
        assertEquals(listOf(a, b), Tree.under(screen(card, a, b), 120, 120, long = false))
        assertEquals(listOf(inA, b), Tree.under(screen(card, a, b, inA), 160, 160, long = false))
        // Elements that take no click: the smallest there.
        val plain = el("e5", role = ElementRole.IMAGE, bounds = box(0, 600, 100, 700), states = emptySet())
        assertEquals(listOf(plain), Tree.under(screen(plain), 50, 650, long = false))
    }

    @Test
    fun `a generic button reads the dialog of the snapshot once, in its own window, leaving out unnamed elements`() {
        val ok = el("e1", "确定").copy(window = 1)
        val dialog =
            listOf(
                el("e2").copy(window = 1),
                el("e3", " ").copy(window = 1),
                text("t1", "发送给张三？", window = 1),
                text("t2", "删除全部记录？", window = 2),
            )
        val risks = Reading(PolicyTuning()).risks
        val s = screen(ok, *dialog.toTypedArray())
        assertEquals(ElementRisk(IntentKind.OUTWARD, "确定:发送"), risks.activation(ok, s).risk)
        val drawn = screen(ok, *dialog.toTypedArray(), ocr = listOf(ocr("确认支付？")))
        assertEquals(ElementRisk(IntentKind.SPEND, "确定:支付"), risks.activation(ok, drawn).risk)
        // Unknown window: the whole screen is the dialog.
        val anywhere = screen(ok.copy(window = null), *dialog.toTypedArray())
        assertEquals(ElementRisk(IntentKind.DELETE, "确定:删除"), risks.activation(anywhere.elements[0], anywhere).risk)
    }

    @Test
    fun `a send button on another row does not make a search field send`() {
        val field = field("f1", hint = "搜索", bounds = box(0, 100, 800, 160))
        val send = el("b1", "发送", bounds = box(820, 500, 999, 560))
        assertTrue(Reading(PolicyTuning()).searches.isSearch(field, screen(field, send)))
        // Nor does the field's own name.
        val named = field.copy(name = "发送")
        assertTrue(Reading(PolicyTuning()).searches.isSearch(named, screen(named)))
    }

    @Test
    fun `the clicks of one control are read once, whatever element of it is tapped`() {
        val button = el("e1", bounds = box(600, 900, 999, 960))
        val label = text("t1", "发送", box(700, 910, 800, 950), parent = "e1")
        val icon = el("e2", role = ElementRole.IMAGE, bounds = box(610, 910, 690, 950), states = emptySet())
        val s = screen(button, label, icon.copy(parent = ElementRef("e1")))
        val risks = Reading(PolicyTuning()).risks
        val byLabel = risks.activation(label, s)
        assertEquals(IntentKind.OUTWARD, byLabel.kind)
        assertSame(byLabel, risks.activation(s.elements[2], s))
        assertSame(risks.at(Aim.Point(650, 930), long = false, s), risks.at(Aim.Point(650, 930), long = false, s))
        // An OCR line at the point is part of what is read.
        val drawn = s.copy(ocr = listOf(ocr("删除", box(600, 900, 680, 960))))
        assertEquals(IntentKind.OUTWARD, risks.activation(label, drawn).kind)
        assertEquals(listOf("发送", "删除"), risks.activation(s.elements[2], drawn).texts)
    }
}
