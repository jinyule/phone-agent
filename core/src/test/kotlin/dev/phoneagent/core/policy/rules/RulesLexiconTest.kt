package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.UiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RulesLexiconTest {
    private val lexicon = Fx.rules().lexicon

    private fun risk(
        element: UiElement,
        vararg others: UiElement,
        pkg: String = Fx.APP,
    ): ElementRisk? = lexicon.activationRisk(element, screen(element, *others, pkg = pkg))

    @Test
    fun `labels name the risk`() {
        assertEquals(ElementRisk(IntentKind.OUTWARD, "发送"), risk(el("b1", "发送")))
        assertEquals(ElementRisk(IntentKind.SPEND, "支付"), risk(el("b1", "立即支付 ¥39.90")))
        // An unnamed row takes its children's text.
        val row = el("b1")
        assertEquals(ElementRisk(IntentKind.DELETE, "删除"), risk(row, text("t1", "删除", parent = "b1")))
        assertNull(risk(el("b1", "返回")))
    }

    @Test
    fun `an unnamed button is judged by the text of its children, as the formatter passes only the button`() {
        val button = el("b1")
        assertEquals(ElementRisk(IntentKind.OUTWARD, "发送"), risk(button, text("t1", "发送", parent = "b1")))
        // The text may sit deeper, under an unnamed layout node, and be blank-named on the way.
        val layout = el("b2", " ", ElementRole.CONTAINER, states = emptySet()).copy(parent = ElementRef("b1"))
        val label = text("t1", "立即支付", parent = "b2")
        assertEquals(ElementRisk(IntentKind.SPEND, "支付"), risk(button, layout, label))
        // Text of other elements is not the button's label.
        assertNull(risk(button, text("t1", "发送")))
    }

    @Test
    fun `fields and their clear buttons are never risky to activate`() {
        assertNull(risk(field("f1", hint = "发送消息")))
        val input = field("f1", bounds = box(0, 100, 800, 160))
        assertNull(risk(el("b1", "清空", bounds = box(700, 110, 790, 150)), input))
    }

    @Test
    fun `an icon without a label is judged by its resource id`() {
        assertEquals(ElementRisk(IntentKind.OUTWARD, "id:send"), risk(el("b1").copy(resourceId = "com.x:id/btn_send")))
        assertEquals(
            ElementRisk(IntentKind.DELETE, "id:delete"),
            risk(el("b1").copy(resourceId = "com.x:id/iv_delete_msg")),
        )
        assertNull(risk(el("b1").copy(resourceId = "com.x:id/iv_avatar")))
        assertNull(risk(el("b1")))
        // Of several words, the most severe decides, and the touch also sends.
        val both = el("b1").copy(resourceId = "com.x:id/btn_send_delete")
        assertEquals(ElementRisk(IntentKind.DELETE, "id:delete"), risk(both))
        assertEquals(true, Reading(PolicyTuning()).risks.activation(both, screen(both)).sends)
        // A label wins over the id: "收藏" with id "btn_send" is not judged by the id.
        assertNull(risk(el("b1", "收藏").copy(resourceId = "com.x:id/btn_send")))
    }

    @Test
    fun `a generic confirmation does what its dialog says`() {
        val ok = el("b1", "确定").copy(window = 1)
        val delete = text("t1", "确定要删除这条消息吗？", window = 1)
        assertEquals(ElementRisk(IntentKind.DELETE, "确定:删除"), risk(ok, delete))
        val send = text("t4", "发送给 张三？", window = 1)
        assertEquals(ElementRisk(IntentKind.OUTWARD, "确定:发送"), risk(ok, send))
        // Spending beats deleting beats reaching out, whatever the order on screen.
        val pay = text("t2", "将从零钱支付 ¥5", window = 1)
        assertEquals(ElementRisk(IntentKind.SPEND, "确定:支付"), risk(ok, send, delete, pay))
        assertEquals(ElementRisk(IntentKind.DELETE, "确定:删除"), risk(ok, send, delete))
        // Text in another window is not part of the dialog; OCR lines are.
        val behind = text("t3", "删除", window = 0)
        assertNull(risk(ok, behind))
        val drawn = ok to screen(ok, ocr = listOf(ocr("删除后无法恢复")))
        assertEquals(ElementRisk(IntentKind.DELETE, "确定:删除"), lexicon.activationRisk(drawn.first, drawn.second))
        // An element with no window sees the whole screen.
        assertEquals(ElementRisk(IntentKind.DELETE, "确认:删除"), risk(el("b1", "确认"), behind))
        // On a payment page it pays, even when the dialog says nothing.
        val sheet = screen(ok, flags = setOf(ScreenFlag.PAYMENT))
        assertEquals(ElementRisk(IntentKind.SPEND, "确定:payment page"), lexicon.activationRisk(ok, sheet))
        // With nothing around it, it is harmless.
        assertNull(risk(ok, text("t1", "已保存", window = 1)))
    }

    @Test
    fun `an unlabeled element on a payment page may be the pay button`() {
        val total = text("t1", "实付 ¥20")
        val icon = el("b1")
        assertEquals(ElementRisk(IntentKind.SPEND, Words.UNLABELED_ON_PAYMENT_PAGE), risk(icon, total))
        assertNull(risk(el("b2", "查看明细"), total))
        assertNull(risk(field("f1", hint = "备注"), total))
    }

    @Test
    fun `sensitive settings entries are SETTINGS before anything else`() {
        val entry = el("b1", "开发者选项")
        assertEquals(ElementRisk(IntentKind.SETTINGS, "settings:开发者选项"), risk(entry, pkg = Pages.SETTINGS))
        // The same label in another app is just a label.
        assertNull(risk(entry))
    }

    @Test
    fun `controls in the settings app change settings`() {
        val switch = el("b1", "蓝牙", role = ElementRole.SWITCH)
        assertEquals(ElementRisk(IntentKind.SETTINGS, "settings:control"), risk(switch, pkg = Pages.SETTINGS))
        val row = el("b1", "蓝牙")
        val toggle = el("b2", role = ElementRole.SWITCH).copy(parent = ElementRef("b1"))
        assertEquals(ElementRisk(IntentKind.SETTINGS, "settings:control"), risk(row, toggle, pkg = Pages.SETTINGS))
        assertEquals(ElementRisk(IntentKind.SETTINGS, "settings:control"), risk(el("b1", "强行停止"), pkg = Pages.SETTINGS))
        // A plain entry only navigates; a word from the lexicon keeps its own kind.
        assertNull(risk(el("b1", "WLAN"), el("b2", role = ElementRole.SWITCH), pkg = Pages.SETTINGS))
        assertEquals(ElementRisk(IntentKind.DELETE, "卸载"), risk(el("b1", "卸载"), pkg = Pages.SETTINGS))
        // Switches in other apps are their own business.
        assertNull(risk(switch))
    }
}
