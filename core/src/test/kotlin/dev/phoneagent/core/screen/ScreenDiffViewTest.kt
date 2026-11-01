package dev.phoneagent.core.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the model reads after an action, from the real differ through the real formatter: the
 * diff must turn the lines it saw on the earlier page into the lines of the later page, so that
 * "not listed" really means "still as you saw it".
 */
class ScreenDiffViewTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)
    private val differ = ScreenDiffer()
    private val click = setOf(ElementState.CLICKABLE)

    /** The text after an action that turned [before] into [after]. */
    private fun shown(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): String =
        formatter.afterAction(ActionOutcome(Status.Ok, Effect.CHANGED, after, differ.diff(before, after)), before.id)

    /** [elements] on the chat page, so that a few changes stay below the full-page share. */
    private fun page(
        id: String,
        vararg elements: UiElement,
    ) = Screens.CHAT.copy(id = SnapshotId(id), elements = Screens.CHAT_ELEMENTS + elements)

    private fun body(text: String) =
        text.lines().filter {
            it.startsWith("+ ") || it.startsWith("~ ") ||
                it.startsWith("- ")
        }

    @Test
    fun `an absorbed text that disappears changes its row, and its unseen ref is not listed`() {
        val row = el("r1", ElementRole.CONTAINER, bounds = box(0, 500, 999, 560), states = click).under("e5")
        val number = el("k1", ElementRole.TEXT, "订单 2026110100").under("r1")
        val badge = el("k2", ElementRole.TEXT, "待付款").under("r1")
        val price = el("k3", ElementRole.TEXT, "¥128.00").under("r1")
        val text = shown(page("s1", row, number, badge, price), page("s2", row, number, price))
        assertEquals(listOf("~ @r1 \"订单 2026110100 · ¥128.00\" (499,530)"), body(text), text)
    }

    @Test
    fun `a text that moves to another unnamed button changes both rows`() {
        val first = el("b1", ElementRole.BUTTON, bounds = box(0, 500, 400, 560), states = click)
        val second = el("b2", ElementRole.BUTTON, bounds = box(500, 500, 999, 560), states = click)
        val label = el("t1", ElementRole.TEXT, "旧消息", box(20, 510, 300, 550))
        val text = shown(page("s1", first, second, label.under("b1")), page("s2", first, second, label.under("b2")))
        assertEquals(listOf("~ @b1 按钮 (200,530)", "~ @b2 按钮 \"旧消息\" (749,530)"), body(text), text)
    }

    @Test
    fun `a ref taken over by an element that is not printed is listed as gone`() {
        // The send button e10 disappeared; the surface gave e10 to an unnamed, plain container.
        val container = el("e10", ElementRole.CONTAINER, bounds = box(880, 930, 980, 980))
        val after = Screens.CHAT.copy(id = SnapshotId("s13"), elements = Screens.CHAT_ELEMENTS.dropLast(1) + container)
        val text = shown(Screens.CHAT, after)
        assertEquals(listOf("- @e10"), body(text), text)
        assertFalse(text.contains("没有可见变化"), text)
    }

    @Test
    fun `a ref taken over by a text absorbed into a row is listed as gone, and the row as changed`() {
        val absorbed = el("e10", ElementRole.TEXT, "3", box(950, 40, 970, 60)).under("e3")
        val after = Screens.CHAT.copy(id = SnapshotId("s13"), elements = Screens.CHAT_ELEMENTS.dropLast(1) + absorbed)
        val text = shown(Screens.CHAT, after)
        assertEquals(listOf("~ @e3 按钮 \"更多 · 3\" (940,60)", "- @e10"), body(text), text)
    }

    @Test
    fun `equal elements pair by ref first, so the one that went away is the one listed`() {
        val likes = (1..3).map { el("e2$it", ElementRole.BUTTON, "赞", box(800, it * 100, 900, it * 100 + 50), click) }
        val all = page("s1", *likes.toTypedArray())
        assertEquals(listOf("- @e21"), body(shown(all, page("s2", likes[1], likes[2]))))
        assertEquals(listOf("- @e22"), body(shown(all, page("s2", likes[0], likes[2]))))
        assertEquals(listOf("- @e23"), body(shown(all, page("s2", likes[0], likes[1]))))
    }

    @Test
    fun `an old ref that no longer exists after renumbering is listed as gone`() {
        val items = (1..20).map { el("e$it", ElementRole.TEXT, "项目 $it", box(0, it * 40, 999, it * 40 + 30)) }
        val renumbered = items.take(18) + items[19].copy(ref = ElementRef("e19"))
        val text = shown(snapshot("s1", items), snapshot("s2", renumbered))
        assertEquals(listOf("~ @e19 \"项目 20\"", "- @e20"), body(text), text)
    }

    @Test
    fun `printed coordinates count any move, unprinted places only a large one`() {
        val button = el("b1", ElementRole.BUTTON, "确定", box(400, 500, 600, 560), click)
        val label = el("t1", ElementRole.TEXT, "说明", box(400, 600, 600, 640))
        val before = page("s1", button, label)
        val nudged =
            page("s2", button.copy(bounds = box(400, 505, 600, 565)), label.copy(bounds = box(400, 605, 600, 645)))
        assertEquals(listOf("~ @b1 按钮 \"确定\" (500,535)"), body(shown(before, nudged)))
        val moved = page("s2", button, label.copy(bounds = box(400, 700, 600, 740)))
        assertEquals(listOf("~ @t1 \"说明\""), body(shown(before, moved)))
    }

    @Test
    fun `OCR lines that appear, move or go away are listed`() {
        val start = OcrLine("开始游戏", box(400, 500, 600, 560), 0.9)
        val rank = OcrLine("排行榜", box(400, 620, 600, 680), 0.9)
        val before = page("s1").copy(ocr = listOf(start, rank))
        val level = OcrLine("游戏中 第 1 关", box(300, 100, 700, 160), 0.9)
        val text = shown(before, page("s2").copy(ocr = listOf(level, rank.copy(bounds = box(400, 820, 600, 880)))))
        assertEquals(
            listOf("+ 屏上文字 \"游戏中 第 1 关\" (500,130)", "~ 屏上文字 \"排行榜\" (500,850)", "- 屏上文字 \"开始游戏\""),
            body(text),
            text,
        )
        assertFalse(text.contains("没有可见变化"), text)
    }

    @Test
    fun `OCR changes are listed together with tree changes`() {
        val before = page("s1").copy(ocr = listOf(OcrLine("加载中", box(400, 500, 600, 560), 0.9)))
        val elements = Screens.CHAT_ELEMENTS.map { if (it.ref.value == "e9") it.copy(value = "好的") else it }
        val after =
            before.copy(
                id = SnapshotId("s2"),
                elements = elements,
                ocr = listOf(OcrLine("已完成", box(400, 500, 600, 560), 0.9)),
            )
        assertEquals(
            listOf(
                "~ @e9 输入框 值:\"好的\" {可编辑,焦点} (480,955)",
                "+ 屏上文字 \"已完成\" (500,530)",
                "- 屏上文字 \"加载中\"",
            ),
            body(shown(before, after)),
        )
    }

    @Test
    fun `OCR changes count toward the full-page share`() {
        val lines = (1..12).map { OcrLine("第 $it 行", box(0, it * 60, 999, it * 60 + 40), 0.9) }
        val game = el("e1", ElementRole.BUTTON, "关闭", box(900, 30, 980, 90), click)
        val before = snapshot("s1", listOf(game)).copy(ocr = lines)
        val after = before.copy(id = SnapshotId("s2"), ocr = lines.map { it.copy(text = it.text + "（新）") })
        val text = shown(before, after)
        assertEquals("（与 s1 相比变化较大，下面是完整一屏，+ 是新出现的元素）", text.lines()[1], text)
        assertTrue(text.contains("\"第 12 行（新）\""), text)
    }
}
