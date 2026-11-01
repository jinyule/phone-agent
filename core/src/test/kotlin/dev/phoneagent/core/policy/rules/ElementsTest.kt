package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElementsTest {
    @Test
    fun `a label is the name, else the text of children up to three levels down`() {
        val row = el("e1", " ")
        val level1 = el("e2").copy(parent = ElementRef("e1"))
        val level2 = el("e3", " ").copy(parent = ElementRef("e2"))
        val text2 = text("t1", "张三", parent = "e3")
        val level3 = el("e4").copy(parent = ElementRef("e3"))
        val tooDeep = text("t2", "太深", parent = "e4")
        val sibling = text("t3", "在线", parent = "e1")
        val s = screen(row, level1, level2, text2, level3, tooDeep, sibling)
        assertEquals("张三 在线", Elements.label(row, s))
        assertEquals("", Elements.label(el("e9"), screen(el("e9"))))
    }

    @Test
    fun `the element at a point is the smallest clickable one, else the smallest one`() {
        val big = el("e1", "卡片", bounds = box(0, 0, 999, 500))
        val small = el("e2", "按钮", bounds = box(100, 100, 200, 200))
        val tinyText = el("e3", "字", ElementRole.TEXT, box(110, 110, 150, 150), emptySet())
        assertEquals(small, Elements.at(screen(big, small, tinyText), 120, 120))
        assertEquals(small, Elements.at(screen(small, big), 120, 120))
        val image = el("e4", role = ElementRole.IMAGE, bounds = box(0, 600, 999, 999), states = emptySet())
        val badge = el("e5", role = ElementRole.IMAGE, bounds = box(0, 600, 100, 700), states = emptySet())
        assertEquals(badge, Elements.at(screen(image, badge), 50, 650))
        assertEquals(badge, Elements.at(screen(badge, image), 50, 650))
        assertNull(Elements.at(screen(big), 500, 800))
    }

    @Test
    fun `visible text and dialog context skip unnamed elements`() {
        val ok = el("e1", "确定").copy(window = 1)
        val s =
            screen(
                ok,
                el("e2").copy(window = 1),
                el("e3", " ").copy(window = 1),
                text("t1", "删除？", window = 1),
                text("t2", "背后", window = 0),
                ocr = listOf(ocr("图上的字")),
            )
        assertEquals(listOf("删除？", "图上的字"), Elements.context(ok, s))
        assertEquals(listOf("确定", "删除？", "背后", "图上的字"), Elements.pieces(s).map { it.text })
    }

    @Test
    fun `geometry - containment, rows and the text to the right`() {
        val parent = box(100, 100, 500, 500)
        assertTrue(Geometry.inside(box(100, 100, 500, 500), parent))
        assertFalse(Geometry.inside(box(99, 200, 200, 300), parent))
        assertFalse(Geometry.inside(box(200, 99, 300, 300), parent))
        assertFalse(Geometry.inside(box(200, 200, 501, 300), parent))
        assertFalse(Geometry.inside(box(200, 200, 300, 501), parent))
        val row = box(0, 100, 300, 200)
        assertTrue(Geometry.sameRow(row, box(400, 120, 500, 180)))
        assertFalse(Geometry.sameRow(row, box(400, 0, 500, 90)))
        assertFalse(Geometry.sameRow(row, box(400, 210, 500, 300)))
        // A tall box's center may be outside a short one even when the short one's is inside it.
        assertFalse(Geometry.sameRow(box(0, 100, 100, 120), box(200, 100, 300, 400)))
        assertFalse(Geometry.sameRow(box(0, 380, 100, 400), box(200, 100, 300, 400)))
        assertEquals(101L * 101L, Geometry.area(box(0, 0, 100, 100)))
        val label = Piece("实付", box(100, 900, 200, 950))
        val pieces =
            listOf(
                Piece("金额", box(0, 900, 90, 950)),
                label,
                Piece("¥5", box(300, 900, 400, 950)),
                Piece("别行", box(300, 0, 400, 50)),
            )
        assertEquals("实付¥5", Geometry.rowText(label, pieces))
    }

    @Test
    fun `resource id words`() {
        assertEquals(listOf("btn", "send", "msg"), Text.idWords("com.x:id/btn__send-msg_"))
        assertEquals(listOf("sms", "code"), Text.idWords("smsCode"))
        assertEquals("发送", Text.bare(" 发 送！ "))
        assertEquals("a b", Text.spaced("  A \n B "))
    }
}
