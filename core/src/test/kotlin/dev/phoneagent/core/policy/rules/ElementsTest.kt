package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElementsTest {
    @Test
    fun `a label is the name, else the text inside it at any depth`() {
        val row = el("e1", " ")
        val level1 = el("e2").copy(parent = ElementRef("e1"))
        val level2 = el("e3", " ").copy(parent = ElementRef("e2"))
        val text2 = text("t1", "张三", parent = "e3")
        val level3 = el("e4").copy(parent = ElementRef("e3"))
        val deep = text("t2", "第四层", parent = "e4")
        val sibling = text("t3", "在线", parent = "e1")
        val s = screen(row, level1, level2, text2, level3, deep, sibling)
        assertEquals("张三 第四层 在线", Elements.label(row, s))
        assertEquals("", Elements.label(el("e9"), screen(el("e9"))))
        // A cycle in the tree ends.
        val a = el("e1").copy(parent = ElementRef("e2"))
        val b = text("e2", "环", parent = "e1")
        assertEquals("环", Elements.label(a, screen(a, b)))
    }

    @Test
    fun `a control inside another one that is away from its center is not part of its label`() {
        val row = el("e1", bounds = box(0, 300, 999, 400))
        val name = text("t2", "张三", box(0, 300, 400, 400), parent = "e1")
        val delete = el("e3", "删除", bounds = box(800, 310, 990, 390)).copy(parent = ElementRef("e1"))
        val inDelete = text("t4", "红色", box(810, 320, 980, 380), parent = "e3")
        val cover = el("e5", "覆盖", bounds = box(0, 300, 999, 400)).copy(parent = ElementRef("e1"))
        assertEquals(listOf("张三", "覆盖"), Elements.content(row, screen(row, name, delete, inDelete, cover)))
    }

    @Test
    fun `a tap by ref clicks the element or the nearest clickable ancestor covering its center`() {
        val button = el("e1", "支付", bounds = box(600, 900, 999, 960))
        val label = text("t2", "¥20", box(700, 910, 800, 950), parent = "e1")
        val s = screen(button, label)
        assertEquals(button, Tree.clickTarget(button, s))
        assertEquals(button, Tree.clickTarget(label, s))
        assertTrue(Tree.inControl(label, s))
        assertFalse(Tree.inControl(button, s))
        // An ancestor that does not cover the element's center does not take the click.
        val away = text("t3", "外面", box(0, 100, 100, 140), parent = "e1")
        assertNull(Tree.clickTarget(away, screen(button, away)))
        // A disabled control takes no click.
        val off = el("e1", "支付", states = Fx.CLICK + ElementState.DISABLED)
        assertNull(Tree.clickTarget(off, screen(off)))
        // Deeper than the actuator climbs.
        val chain = (1..10).map { i -> el("e$i", states = emptySet()).copy(parent = ElementRef("e${i + 1}")) }
        val top = el("e11")
        assertEquals(top, Tree.clickTarget(chain[2], screen(*chain.toTypedArray(), top)))
        assertNull(Tree.clickTarget(chain[0], screen(*chain.toTypedArray(), top)))
    }

    @Test
    fun `a touch may land on every innermost clickable element there, smallest first`() {
        val big = el("e1", "卡片", bounds = box(0, 0, 999, 500))
        val small = el("e2", "按钮", bounds = box(100, 100, 200, 200))
        val tinyText = el("e3", "字", ElementRole.TEXT, box(110, 110, 150, 150), emptySet())
        assertEquals(listOf(small, big), Tree.under(screen(big, small, tinyText), 120, 120))
        assertEquals(listOf(small, big), Tree.under(screen(small, big), 120, 120))
        // Inside the card in the tree: the inner one takes the touch.
        val inner = small.copy(parent = ElementRef("e1"))
        assertEquals(listOf(inner), Tree.under(screen(big, inner), 120, 120))
        // An overlapping control that is not its ancestor still may take the touch.
        val overlay = el("e6", "浮层", bounds = box(50, 50, 300, 300))
        assertEquals(listOf(inner, overlay), Tree.under(screen(big, inner, overlay), 120, 120))
        val image = el("e4", role = ElementRole.IMAGE, bounds = box(0, 600, 999, 999), states = emptySet())
        val badge = el("e5", role = ElementRole.IMAGE, bounds = box(0, 600, 100, 700), states = emptySet())
        assertEquals(listOf(badge), Tree.under(screen(image, badge), 50, 650))
        assertEquals(listOf(badge), Tree.under(screen(badge, image), 50, 650))
        assertEquals(emptyList<Any>(), Tree.under(screen(big), 500, 800))
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
        assertEquals("¥5", Geometry.rightOf(label, pieces))
        assertEquals(Aim.Point(50, 125), Geometry.center(box(0, 100, 101, 151)))
    }

    @Test
    fun `resource id words`() {
        assertEquals(listOf("btn", "send", "msg"), Text.idWords("com.x:id/btn__send-msg_"))
        assertEquals(listOf("sms", "code"), Text.idWords("smsCode"))
        assertEquals("发送", Text.bare(" 发 送！ "))
        assertEquals("a b", Text.spaced("  A \n B "))
    }
}
