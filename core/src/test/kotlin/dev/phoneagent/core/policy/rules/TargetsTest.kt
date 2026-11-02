package dev.phoneagent.core.policy.rules

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
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * What a tap activates (policy-rules.md §2): the element by ref, or what lies under a point,
 * with all the evidence on it (its name, the text inside it, the OCR over it).
 */
class TargetsTest {
    private val policy = Fx.rules()

    private fun decide(
        call: ToolCall,
        ctx: PolicyContext,
    ): Verdict = policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, ctx.screen), ctx)

    private fun on(
        s: ScreenSnapshot,
        level: SecurityLevel = SecurityLevel.STANDARD,
    ): PolicyContext = ctx(level, s, operated = setOfNotNull(s.pkg))

    private fun reason(verdict: Verdict): ConfirmReason =
        assertInstanceOf(Verdict.Confirm::class.java, verdict).card.reason

    private fun point(
        x: Int,
        y: Int,
    ): ToolCall = call("phone_tap", "screen" to "s1", "x" to x, "y" to y)

    @Test
    fun `overlapping controls under a point - the riskiest one counts`() {
        val s =
            screen(
                el("e1", "查看", bounds = box(400, 400, 600, 450)).copy(window = 1),
                el("e2", "支付", bounds = box(0, 300, 999, 600)).copy(window = 2),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(point(500, 425), on(s))))
        // A control inside another one takes the touch: the inner one alone decides.
        val row =
            screen(
                el("e1", bounds = box(0, 300, 999, 400)),
                text("t2", "张三", box(0, 300, 400, 400), parent = "e1"),
                el("e3", "删除", bounds = box(800, 310, 990, 390)).copy(parent = ElementRef("e1")),
            )
        assertEquals(ConfirmReason.DELETE, reason(decide(point(900, 350), on(row))))
        assertEquals(Verdict.Allow, decide(point(200, 350), on(row)))
        // By ref the row's own click runs, not the button inside it.
        assertEquals(Verdict.Allow, decide(tap("e1"), on(row)))
    }

    @Test
    fun `risk words anywhere in the control's own content count`() {
        val named = screen(el("e1", "操作"), text("t2", "支付", box(0, 400, 100, 450), parent = "e1"))
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), on(named))))
        val deep =
            screen(
                el("e1"),
                el("e2", role = ElementRole.CONTAINER, states = emptySet()).copy(parent = ElementRef("e1")),
                el("e3", role = ElementRole.CONTAINER, states = emptySet()).copy(parent = ElementRef("e2")),
                el("e4", role = ElementRole.CONTAINER, states = emptySet()).copy(parent = ElementRef("e3")),
                text("t5", "支付", box(0, 400, 100, 450), parent = "e4"),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), on(deep))))
    }

    @Test
    fun `a ref on text inside a control activates the control`() {
        val s =
            screen(
                el("e1", "支付", bounds = box(600, 900, 999, 960)),
                text("t2", "¥20", box(700, 910, 800, 950), parent = "e1"),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("t2"), on(s))))
    }

    @Test
    fun `OCR over an unnamed control counts by ref and by point`() {
        val s = screen(el("e1", bounds = box(400, 400, 600, 450)), ocr = listOf(ocr("删除", box(450, 405, 550, 445))))
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), on(s))))
        assertEquals(ConfirmReason.DELETE, reason(decide(point(500, 425), on(s))))
    }

    @Test
    fun `OCR inside an element counts only for a control the tree names nothing on`() {
        val image = el("e1", role = ElementRole.IMAGE, bounds = box(400, 400, 600, 450), states = emptySet())
        val s = screen(image, ocr = listOf(ocr("删除", box(450, 405, 550, 445))))
        assertEquals(emptyList<String>(), Evidence.of(image, null, s))
        assertEquals(listOf("删除"), Evidence.of(image, Aim.Point(500, 425), s))
        // A blank name is no name: the OCR over the control is its text.
        val blank = el("e1", " ", bounds = box(400, 400, 600, 450))
        assertEquals(listOf("删除"), Evidence.of(blank, null, screen(blank, ocr = s.ocr)))
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), on(screen(blank, ocr = s.ocr)))))
        val named = el("e1", "图片", bounds = box(400, 400, 600, 450))
        assertEquals(listOf("图片"), Evidence.of(named, null, screen(named, ocr = s.ocr)))
    }

    @Test
    fun `an unnamed icon inside a field is not taken for a clear button against the evidence`() {
        val s =
            screen(
                field("e2", bounds = box(0, 900, 999, 960)),
                el("e1", bounds = box(900, 910, 990, 950)),
                flags = setOf(ScreenFlag.PAYMENT),
                ocr = listOf(ocr("支付", box(905, 915, 985, 945))),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), on(s))))
        assertEquals(ConfirmReason.SPEND, reason(decide(point(945, 930), on(s))))
    }

    @Test
    fun `a clear button's id or place does not outweigh a pay or delete word on it`() {
        val pay =
            screen(
                field("e2", bounds = box(0, 900, 999, 960)).copy(value = "abc"),
                el("e1", bounds = box(900, 910, 990, 950)).copy(resourceId = "com.x:id/clear"),
                flags = setOf(ScreenFlag.PAYMENT),
                ocr = listOf(ocr("支付", box(905, 915, 985, 945))),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), on(pay))))
        assertEquals(ConfirmReason.SPEND, reason(decide(point(945, 930), on(pay))))
        val delete =
            screen(
                field("e2", bounds = box(0, 900, 999, 960)).copy(value = "abc"),
                el("e1", bounds = box(700, 910, 990, 950)),
                ocr = listOf(ocr("删除聊天记录", box(705, 915, 985, 945))),
            )
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), on(delete))))
        assertEquals(ConfirmReason.DELETE, reason(decide(point(845, 930), on(delete))))
        // A real clear button still only edits.
        val clear = el("e1", "清空", bounds = box(900, 910, 990, 950))
        val plain = screen(field("e2", bounds = box(0, 900, 999, 960)).copy(value = "abc"), clear)
        assertEquals(Verdict.Allow, decide(tap("e1"), on(plain)))
    }

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `leaving a hand-off page needs every control under the point to leave`(level: SecurityLevel) {
        val s =
            screen(
                el("e1", "取消", bounds = box(450, 400, 550, 450)).copy(window = 2),
                el("e2", "本机号码一键登录", bounds = box(0, 300, 999, 600)).copy(window = 1),
                flags = setOf(ScreenFlag.LOGIN),
            )
        assertEquals(HandoffKind.LOGIN, handOff(decide(tap("e2"), on(s, level))))
        assertEquals(HandoffKind.LOGIN, handOff(decide(point(500, 425), on(s, level))))
        // Only "取消" under the point: it leaves.
        assertEquals(Verdict.Allow, decide(point(500, 700), on(s.copy(elements = s.elements + escape()), level)))
        assertEquals(Verdict.Allow, decide(tap("e1"), on(s, level)))
        // Something else drawn on the escape button counts too.
        val drawn = s.copy(ocr = listOf(ocr("同意并登录", box(460, 410, 540, 440))))
        assertEquals(HandoffKind.LOGIN, handOff(decide(tap("e1"), on(drawn, level))))
    }

    private fun escape() = el("e3", "取消", bounds = box(450, 680, 550, 720))

    private fun handOff(verdict: Verdict): HandoffKind = assertInstanceOf(Verdict.HandOff::class.java, verdict).kind

    @Test
    fun `OCR naming a sensitive settings entry refuses it`() {
        val s =
            screen(
                text("t1", "设置", box(0, 40, 500, 100)),
                el("e1", bounds = box(0, 400, 999, 480)),
                pkg = Pages.SETTINGS,
                ocr = listOf(ocr("无障碍", box(40, 420, 300, 460))),
            )
        for (c in listOf(tap("e1"), point(150, 440))) {
            val verdict = decide(c, on(s, SecurityLevel.AUTONOMOUS))
            assertEquals("hard.sensitive_settings", assertInstanceOf(Verdict.Refuse::class.java, verdict).reason)
        }
    }
}
