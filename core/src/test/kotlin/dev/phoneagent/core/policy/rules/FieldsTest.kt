package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldsTest {
    private val plain = screen()
    private val fields = Reading(PolicyTuning()).fields
    private val searches = Reading(PolicyTuning()).searches

    private fun edits(
        element: UiElement,
        s: ScreenSnapshot,
    ): Boolean = fields.editsField(element, Evidence.of(element, Geometry.center(element.bounds), s), s)

    @Test
    fun `fields are classified by hint, name and resource id, never by what is typed`() {
        fun cls(
            hint: String? = null,
            name: String? = null,
            id: String? = null,
            value: String? = null,
        ) = fields.classify(field("f1", hint).copy(name = name, value = value, resourceId = id), plain)
        assertEquals(FieldClass.PAYMENT_PASSWORD, cls(hint = "请输入支付密码"))
        assertEquals(FieldClass.PAYMENT_PASSWORD, cls(id = "com.x:id/et_pay_pwd"))
        assertEquals(FieldClass.VERIFICATION_CODE, cls(hint = "短信验证码"))
        assertEquals(FieldClass.VERIFICATION_CODE, cls(name = "Verification code"))
        assertEquals(FieldClass.VERIFICATION_CODE, cls(id = "com.x:id/smsCode"))
        assertEquals(FieldClass.PASSWORD, cls(hint = "登录密码"))
        assertEquals(FieldClass.PASSWORD, cls(id = "com.x:id/input_pwd"))
        assertEquals(FieldClass.BANK_CARD, cls(hint = "银行卡号"))
        assertEquals(FieldClass.ID_NUMBER, cls(hint = "身份证号码"))
        assertEquals(FieldClass.PLAIN, cls(hint = "发消息"))
        assertEquals(FieldClass.PLAIN, cls(value = "我的密码是什么"))
        assertEquals(FieldClass.PLAIN, cls())
    }

    @Test
    fun `a secret field is a password, or a payment password on a payment-password page`() {
        val secret = field("f1", secret = true)
        assertEquals(FieldClass.PASSWORD, fields.classify(secret, screen(secret)))
        val payPage = screen(text("t1", "请输入支付密码"), secret)
        assertEquals(FieldClass.PAYMENT_PASSWORD, fields.classify(secret, payPage))
    }

    @Test
    fun `search fields are recognized unless something on their row sends`() {
        val byHint = field("f1", hint = "搜索联系人")
        assertTrue(searches.isSearch(byHint, screen(byHint)))
        val byEnglish = field("f1").copy(name = "Search")
        assertTrue(searches.isSearch(byEnglish, screen(byEnglish)))
        val byId = field("f1").copy(resourceId = "com.x:id/searchBox")
        assertTrue(searches.isSearch(byId, screen(byId)))
        val bare = field("f1", bounds = box(0, 100, 800, 160))
        val searchButton = el("b1", "搜索", bounds = box(820, 100, 999, 160))
        assertTrue(searches.isSearch(bare, screen(bare, searchButton)))
        val otherRow = el("b2", "搜索", bounds = box(820, 500, 999, 560))
        assertFalse(searches.isSearch(bare, screen(bare, otherRow)))
        // "搜索或提问" with a send button on the row is a chat box.
        val chat = field("f1", hint = "搜索或提问", bounds = box(0, 900, 800, 960))
        val send = el("b3", "发送", bounds = box(820, 900, 999, 960))
        assertFalse(searches.isSearch(chat, screen(chat, send)))
        assertFalse(searches.isSearch(field("f2", hint = "说点什么"), plain))
    }

    @Test
    fun `clear buttons in or next to a field only edit it`() {
        val input = field("f1", bounds = box(0, 100, 800, 160)).copy(value = "abc")
        val inside = el("b1", "×", bounds = box(700, 110, 790, 150))
        val icon = el("b2", bounds = box(600, 110, 690, 150))
        val next = el("b3", "清空", bounds = box(810, 100, 870, 160))
        val byId = el("b4", bounds = box(720, 110, 790, 150)).copy(resourceId = "com.x:id/iv_clear")
        val far = el("b5", "清空", bounds = box(900, 100, 999, 160))
        val below = el("b6", "清空", bounds = box(0, 500, 100, 560))
        val namedInside = el("b7", "删除", bounds = box(700, 110, 790, 150))
        val idIconOutside = el("b8", bounds = box(0, 500, 100, 560)).copy(resourceId = "com.x:id/btn_send")
        val s = screen(input, inside, icon, next, byId, far, below, namedInside, idIconOutside)
        assertTrue(edits(inside, s))
        assertTrue(edits(icon, s))
        assertTrue(edits(next, s))
        assertTrue(edits(byId, s))
        assertFalse(edits(far, s))
        assertFalse(edits(below, s))
        assertFalse(edits(namedInside, s))
        assertFalse(edits(idIconOutside, s))
        assertFalse(edits(el("b10", bounds = box(0, 500, 100, 560)), s))
        // A clear button left of its field, within reach.
        val left = el("b9", "清空", bounds = box(0, 100, 50, 160))
        val shifted = field("f2", bounds = box(60, 100, 800, 160))
        assertTrue(edits(left, screen(shifted, left)))
        assertFalse(edits(left, screen(left)))
        // Too far left of its field.
        val wide = field("f3", bounds = box(200, 100, 800, 160))
        assertFalse(edits(left, screen(wide, left)))
        // An icon with no text clears only a field that holds text; any text on it decides instead.
        assertFalse(edits(icon, screen(input.copy(value = null), icon)))
        assertFalse(edits(icon, screen(input.copy(value = ""), icon)))
        val drawn = screen(input, icon, ocr = listOf(Fx.ocr("支付", box(610, 115, 680, 145))))
        assertFalse(edits(icon, drawn))
        val crossed = screen(input.copy(value = null), icon, ocr = listOf(Fx.ocr("清除", box(610, 115, 680, 145))))
        assertTrue(edits(icon, crossed))
        // Only a clearing word: "删除" or "删除聊天记录" drawn on it is a delete, not a clear button.
        for (drawnDelete in listOf("删除", "删除聊天记录")) {
            val deletes = screen(input, icon, ocr = listOf(Fx.ocr(drawnDelete, box(610, 115, 680, 145))))
            assertFalse(edits(icon, deletes), drawnDelete)
        }
        // A clear button's id or name does not outweigh a risk word on it.
        assertFalse(edits(byId, screen(input, byId, ocr = listOf(Fx.ocr("支付", box(725, 115, 785, 145))))))
        assertFalse(edits(inside, screen(input, inside, ocr = listOf(Fx.ocr("发送", box(705, 115, 785, 145))))))
        // Text that names no risk does not.
        assertTrue(edits(inside, screen(input, inside, ocr = listOf(Fx.ocr("abc", box(705, 115, 785, 145))))))
        val mixed =
            screen(
                input,
                icon,
                ocr = listOf(Fx.ocr("删除", box(610, 115, 680, 145)), Fx.ocr("发送", box(612, 116, 670, 140))),
            )
        assertFalse(edits(icon, mixed))
        // A field is not its own clear button.
        assertFalse(edits(input, screen(input)))
    }

    @Test
    fun `the typing target is the focused field, else the only field`() {
        val a = field("f1")
        val b = field("f2", focused = true)
        assertEquals(b, fields.typingTarget(screen(a, b)))
        assertEquals(a, fields.typingTarget(screen(a, text("t1", "x"))))
        assertNull(fields.typingTarget(screen(a, field("f3"))))
        assertNull(fields.typingTarget(plain))
    }
}
