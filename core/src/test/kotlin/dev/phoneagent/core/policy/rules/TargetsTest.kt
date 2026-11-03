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
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
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
    fun `every OCR line at the point counts, with what the control shows itself`() {
        val s =
            screen(
                el("e1", "查看", bounds = box(400, 400, 600, 450)),
                ocr = listOf(ocr("详情", box(410, 405, 590, 445)), ocr("立即支付", box(420, 410, 580, 440))),
            )
        assertEquals(listOf("查看", "详情", "立即支付"), Reading(PolicyTuning()).risks.texts(s.elements[0], s))
        assertEquals(ConfirmReason.SPEND, reason(decide(point(500, 425), on(s))))
        // The same text drawn as shown: one text, read as the control's own label.
        val same = s.copy(ocr = listOf(ocr("查看", box(410, 405, 590, 445))))
        assertEquals(listOf("查看"), Reading(PolicyTuning()).risks.texts(same.elements[0], same))
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

    @Test
    fun `the risk that vetoes a clear button decides the tap, however long its text`() {
        val drawn =
            mapOf(
                "我已阅读并同意服务协议现在支付并完成本次交易" to ConfirmReason.SPEND,
                "我已经看完了这些内容现在就发送给张三吧好的" to ConfirmReason.OUTWARD,
                "这条消息里面的内容我想要删除聊天记录全部" to ConfirmReason.DELETE,
            )
        for ((text, expected) in drawn) {
            // No PAYMENT flag: only the text on the button says what it does.
            val s =
                screen(
                    field("e2", bounds = box(0, 900, 999, 960)).copy(value = "abc"),
                    el("e1", "×", bounds = box(900, 910, 990, 950)).copy(resourceId = "com.x:id/clear"),
                    ocr = listOf(ocr(text, box(905, 915, 985, 945))),
                )
            assertEquals(expected, reason(decide(tap("e1"), on(s))), text)
            assertEquals(expected, reason(decide(point(945, 930), on(s))), text)
            assertEquals(
                expected.name,
                policy.lexicon
                    .activationRisk(s.elements[1], s)
                    ?.kind
                    ?.name,
                text,
            )
        }
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

    @ParameterizedTest
    @EnumSource(SecurityLevel::class)
    fun `a long press reaches what takes a long press`(level: SecurityLevel) {
        val s =
            screen(
                el("e1", "取消", bounds = box(450, 400, 550, 450)).copy(window = 2),
                el("e2", "本机号码一键登录", bounds = box(0, 300, 999, 600), states = LONG).copy(window = 1),
                flags = setOf(ScreenFlag.LOGIN),
            )
        val long = call("phone_tap", "screen" to "s1", "x" to 500, "y" to 425, "long" to true)
        assertEquals(HandoffKind.LOGIN, handOff(decide(long, on(s, level))))
        // By ref: "取消" takes no long press, so the actuator presses its center.
        val byRef = call("phone_tap", "screen" to "s1", "ref" to "e1", "long" to true)
        assertEquals(HandoffKind.LOGIN, handOff(decide(byRef, on(s, level))))
        // A short press reaches only "取消", which leaves.
        assertEquals(Verdict.Allow, decide(point(500, 425), on(s, level)))
        assertEquals(Verdict.Allow, decide(tap("e1"), on(s, level)))
    }

    @Test
    fun `a long press by ref goes to the nearest ancestor that takes a long press, as the actuator does`() {
        val s =
            screen(
                el("e1", "删除", bounds = box(0, 300, 999, 400), states = LONG),
                el("e2", "查看", bounds = box(0, 300, 500, 400)).copy(parent = ElementRef("e1")),
                text("t3", "张三", box(0, 300, 400, 400), parent = "e2"),
            )
        assertEquals(Verdict.Allow, decide(tap("t3"), on(s)))
        val long = call("phone_tap", "screen" to "s1", "ref" to "t3", "long" to true)
        assertEquals(ConfirmReason.DELETE, reason(decide(long, on(s))))
        // At a point the innermost control takes the touch, and a held click on it is a click.
        assertEquals(
            Verdict.Allow,
            decide(call("phone_tap", "screen" to "s1", "x" to 200, "y" to 350, "long" to true), on(s)),
        )
        val pay = screen(el("e1", "支付", bounds = box(0, 300, 999, 400)))
        assertEquals(
            ConfirmReason.SPEND,
            reason(
                decide(
                    call(
                        "phone_tap",
                        "screen" to "s1",
                        "x" to 200,
                        "y" to 350,
                        "long" to true,
                    ),
                    on(pay),
                ),
            ),
        )
    }

    private fun escape() = el("e3", "取消", bounds = box(450, 680, 550, 720))

    private fun handOff(verdict: Verdict): HandoffKind = assertInstanceOf(Verdict.HandOff::class.java, verdict).kind

    private companion object {
        /** Takes a long press only. */
        val LONG = setOf(ElementState.LONG_CLICKABLE)
    }

    @Test
    fun `a risk one reading of a control finds counts, whatever another reading finds`() {
        // A generic button with "发送" drawn over it, in a dialog that deletes: the dialog counts.
        val dialog =
            screen(
                el("e1", "确定", bounds = box(400, 400, 600, 450)).copy(window = 1),
                text("t1", "删除全部记录？", window = 1),
                ocr = listOf(ocr("发送", box(450, 405, 550, 445))),
            )
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), on(dialog))))
        // An icon without any text whose id deletes, on a payment page: it may still be the pay button.
        val icon =
            screen(
                el("e1", bounds = box(400, 400, 600, 450)).copy(resourceId = "com.x:id/btn_delete"),
                flags = setOf(ScreenFlag.PAYMENT),
            )
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), on(icon))))
        // A sensitive settings entry that also deletes: the delete counts with the entry.
        val entry = screen(el("e1", "删除无障碍快捷方式"), pkg = Pages.SETTINGS)
        val risk = Reading(PolicyTuning()).risks.activation(entry.elements[0], entry).risk
        assertEquals(ElementRisk(IntentKind.DELETE, "删除"), risk)
    }

    @Test
    fun `of several risks found, the one asked about at more levels counts`() {
        // At 标准, sending asks once per app and deleting or changing settings every time; the app
        // has had its send confirmed.
        val sent = { s: ScreenSnapshot ->
            Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))
        }
        val stacked =
            screen(
                el("e1", "发送", bounds = box(400, 400, 600, 450)).copy(window = 1),
                el("e2", "删除", bounds = box(0, 300, 999, 600)).copy(window = 2),
            )
        assertEquals(ConfirmReason.DELETE, reason(decide(point(500, 425), sent(stacked))))
        val both = screen(el("e1", "发送并删除原图"))
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), sent(both))))
        val settings = screen(field("f1", focused = true), pkg = Pages.SETTINGS)
        val submit = call("phone_type", "text" to "abc", "submit" to true)
        assertEquals(ConfirmReason.SETTINGS, reason(decide(submit, sent(settings))))
        // Enter there is the same submit, also with no field focused.
        val enter = call("phone_key", "key" to "enter")
        assertEquals(ConfirmReason.SETTINGS, reason(decide(enter, sent(settings))))
        val unfocused = screen(field("f1"), pkg = Pages.SETTINGS)
        assertEquals(ConfirmReason.SETTINGS, reason(decide(enter, sent(unfocused))))
        assertEquals(ConfirmReason.SETTINGS, reason(decide(enter, sent(screen(pkg = Pages.SETTINGS)))))
        // A control on a field's row that deletes as well as sends still makes it no search field.
        val row =
            screen(
                field("f1", hint = "搜索", focused = true, bounds = box(0, 900, 700, 960)),
                el("e1", "删除并发送", bounds = box(720, 900, 999, 960)),
            )
        assertEquals(
            ConfirmReason.OUTWARD,
            reason(decide(call("phone_key", "key" to "enter"), on(row, SecurityLevel.CAUTIOUS))),
        )
    }

    @Test
    fun `an English label joining two actions counts both, and one joining two names is no action`() {
        val sent = { s: ScreenSnapshot ->
            Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))
        }
        for (label in listOf(
            "Send and delete",
            "Send & delete",
            "Send + delete",
            "Send then delete",
            "Send, then delete",
        )) {
            assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), sent(screen(el("e1", label))))), label)
        }
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), sent(screen(el("e1", "Reply and pay"))))))
        for (label in listOf("Terms and conditions", "Photos and videos", "Payments & transfers", "Help & feedback")) {
            assertEquals(Verdict.Allow, decide(tap("e1"), sent(screen(el("e1", label)))), label)
        }
        // Each part is read by the phrase it starts with, as Chinese strong words are ("扫码支付" pays);
        // an exact phrase is only ever the whole label, as Chinese exact words are.
        assertEquals(ConfirmReason.SPEND, reason(decide(tap("e1"), sent(screen(el("e1", "Scan & pay"))))))
        val first = { s: ScreenSnapshot -> Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg)) }
        for (label in listOf("Invite", "Save and post")) {
            assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), first(screen(el("e1", label))))), label)
        }
        for (label in listOf("Invite and earn", "Like and comment")) {
            assertEquals(Verdict.Allow, decide(tap("e1"), first(screen(el("e1", label)))), label)
        }
    }

    @Test
    fun `English joiners split however they are spaced or repeated, and names joined by a sign stay names`() {
        val sent = { s: ScreenSnapshot ->
            Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))
        }
        val verdict = { label: String -> decide(tap("e1"), sent(screen(el("e1", label)))) }
        val asked = { kind: ConfirmReason?, labels: List<String> ->
            labels.map { label ->
                Executable { assertEquals(kind, (verdict(label) as? Verdict.Confirm)?.card?.reason, label) }
            }
        }
        // Joiners one after another, and signs without spaces (Codex r5), then the forms that split before.
        val deletes =
            listOf(
                "Send and then delete",
                "Send & then delete",
                "Send then then delete",
                "Send&delete",
                "Send and delete",
                "Delete & send",
                "Send, then delete",
                "SEND And DeLeTe",
                "Send ＆ delete",
                "Send＆delete",
                // Claude r5: a word left after the joiner, a slash, signs and hyphens without spaces.
                "Send and also delete",
                "Send/delete",
                "Send / delete",
                "Send+delete",
                "Send-and-delete",
                "Send. Then delete",
                // Split at the slash as at "&": "Add/Remove" asks once more, as "Add & remove widgets" does.
                "Add/Remove",
                // Commas and semicolons join as well (19:2x): confirmation buttons that delete.
                "Send, delete",
                "Send; delete",
                "Yes, delete",
                "Yes, remove",
                "OK, delete",
            )
        val spends = listOf("Pay and send", "Send and delete & pay", "Send+pay")
        // Split at the sign, each part still reads only by the phrase it starts with: allowed.
        val names =
            listOf("Q&A", "R&D", "AT&T", "Terms&Conditions", "Android and iOS", "Brand+", "Photos/Videos")
                .plus("Also delete")
                // A sign without spaces joins only inside a word of Latin letters and that sign.
                .plus("https://www.example.com/checkout/cart")
                .plus(listOf("example.com/buy", "example.com/remove-background-tips"))
                // Nor at the edge of a word with a space on one side: an address wrapped after its slash.
                .plus(listOf("https://www.example.com/\ncheckout/cart", "example.com/ remove-background-tips"))
                // Nor at the edge of one: numbers read as spaces leave "&pay" at the end of this address.
                .plus("https://a.com/?x=1&pay=2")
        assertAll(asked(ConfirmReason.DELETE, deletes) + asked(ConfirmReason.SPEND, spends) + asked(null, names))
        names.forEach { assertEquals(Verdict.Allow, verdict(it), it) }
    }

    @Test
    fun `a row of several texts splits an English one where each part is within the length limit`() {
        val row = el("e1", bounds = box(0, 300, 999, 400))
        val name = text("t1", "Alice", parent = "e1")
        val said = text("t2", "Send and delete", box(0, 350, 500, 390), parent = "e1")
        val sent = { s: ScreenSnapshot ->
            Fx.ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))
        }
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), sent(screen(row, name, said)))))
        // A long sentence is still read only by its start: a part past the limit keeps it whole.
        val long = text("t2", "Send me the files and delete the old ones", box(0, 350, 500, 390), parent = "e1")
        assertEquals(ConfirmReason.OUTWARD, reason(decide(tap("e1"), on(screen(row, name, long)))))
    }

    @Test
    fun `commas join actions in a control's own label and in a dialog, not in the texts of a row`() {
        val s = screen(el("e1", "Yes, delete"))
        assertEquals(ConfirmReason.DELETE, reason(decide(tap("e1"), on(s))))
        // A chat preview in a list row only opens the chat.
        val row = el("e1", bounds = box(0, 300, 999, 400))
        val name = text("t1", "Alice", parent = "e1")
        val preview = text("t2", "Ok, send it", box(0, 350, 500, 390), parent = "e1")
        assertEquals(Verdict.Allow, decide(tap("e1"), on(screen(row, name, preview))))
        val ok = el("b1", "OK").copy(window = 1)
        for (said in listOf("Send, then delete?", "Send, delete?")) {
            val dialog = screen(ok, text("t3", said, window = 1))
            assertEquals(ConfirmReason.DELETE, reason(decide(tap("b1"), on(dialog))), said)
        }
    }

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
