package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ScreenDifferTest {
    private val differ = ScreenDiffer()
    private val before = Screens.CHAT

    @Test
    fun `same content is no change`() {
        assertTrue(differ.diff(before, before).isEmpty)
        // Parents that change nothing on the page change nothing in the diff.
        val listless =
            before.copy(
                elements =
                    before.elements.map {
                        if (it.parent?.value ==
                            "e5"
                        ) {
                            it.copy(parent = null)
                        } else {
                            it
                        }
                    },
            )
        assertTrue(differ.diff(before, listless).isEmpty)
    }

    @Test
    fun `a new parent that changes what a row absorbs changes the row`() {
        // e4 ("更多") stops being part of the button e3 and gets a line of its own.
        val reparented = before.copy(elements = before.elements.map { it.copy(parent = null) })
        val diff = differ.diff(before, reparented)
        assertEquals(listOf("e3"), diff.changed.map { it.ref.value })
        assertEquals(listOf("e4"), diff.added.map { it.ref.value })
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `a renumbered element is changed, because the model holds its old ref`() {
        // The new message shifts the refs of everything after it, as a surface without stable refs would.
        val shifted =
            before.elements.map { element ->
                val n =
                    element.ref.value
                        .drop(1)
                        .toInt()
                if (n >= 8) element.copy(ref = ElementRef("e${n + 1}")) else element
            } + el("e8", ElementRole.TEXT, "我：十点半", box(600, 400, 960, 460)).under("e5")
        val diff = differ.diff(before, before.copy(elements = shifted))
        assertEquals(listOf("e10", "e11", "e8"), (diff.changed + diff.added).map { it.ref.value })
        assertEquals(listOf("e8"), diff.added.map { it.ref.value })
        // e9 is not on the new page at all: the model must drop it.
        assertEquals(listOf(ElementRef("e9")), diff.removed)
    }

    @Test
    fun `reports added, removed and changed elements`() {
        val message = el("e11", ElementRole.TEXT, "我：十点半", box(600, 400, 960, 460)).under("e5")
        val after =
            before.copy(
                elements =
                    before.elements
                        .filterNot { it.ref.value == "e6" }
                        .map { if (it.ref.value == "e9") it.copy(value = "十点半") else it } + message,
            )
        val diff = differ.diff(before, after)
        assertEquals(listOf(message), diff.added)
        assertEquals(listOf(ElementRef("e6")), diff.removed)
        assertEquals(listOf("e9"), diff.changed.map { it.ref.value })
    }

    @Test
    fun `an editable field keeps its identity while its text changes, a text does not`() {
        val field = el("e1", ElementRole.INPUT, "搜索", states = setOf(ElementState.EDITABLE)).copy(value = "a")
        val label = el("e2", ElementRole.TEXT, "3 条新消息")
        val diff =
            differ.diff(
                snapshot("s1", listOf(field, label)),
                snapshot("s2", listOf(field.copy(value = "ab"), label.copy(name = "4 条新消息"))),
            )
        assertEquals(listOf("e1"), diff.changed.map { it.ref.value })
        // The renamed text is a new element under the same ref: listed as added, not as gone.
        assertEquals(listOf("e2"), diff.added.map { it.ref.value })
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `printed coordinates count any move, a line without coordinates only one beyond the tolerance`() {
        val button = el("e1", ElementRole.BUTTON, "发送", box(880, 930, 980, 980))
        val nudged = button.copy(bounds = box(880, 931, 980, 981))
        assertEquals(
            listOf(nudged),
            differ.diff(snapshot("s1", listOf(button)), snapshot("s2", listOf(nudged))).changed,
        )
        val text = el("e1", ElementRole.TEXT, "说明", box(100, 500, 300, 540))
        val shifted = text.copy(bounds = box(100, 510, 300, 550))
        val moved = text.copy(bounds = box(100, 600, 300, 640))
        assertTrue(differ.diff(snapshot("s1", listOf(text)), snapshot("s2", listOf(shifted))).isEmpty)
        assertEquals(listOf(moved), differ.diff(snapshot("s1", listOf(text)), snapshot("s2", listOf(moved))).changed)
        val horizontal = text.copy(bounds = box(300, 500, 500, 540))
        assertEquals(
            listOf(horizontal),
            differ.diff(snapshot("s1", listOf(text)), snapshot("s2", listOf(horizontal))).changed,
        )
        val strict = ScreenDiffer(DiffConfig(moveTolerance = 0))
        assertEquals(
            listOf(shifted),
            strict.diff(snapshot("s1", listOf(text)), snapshot("s2", listOf(shifted))).changed,
        )
    }

    @Test
    fun `hint, states and secret count as changes`() {
        val field = el("e1", ElementRole.TEXT, "密码")
        val cases =
            listOf(field.copy(hint = "h"), field.copy(states = setOf(ElementState.FOCUSED)), field.copy(secret = true))
        for (now in cases) {
            assertEquals(listOf(now), differ.diff(snapshot("s1", listOf(field)), snapshot("s2", listOf(now))).changed)
        }
    }

    @Test
    fun `equal elements are paired in reading order`() {
        val like = {
            ref: String,
            top: Int,
            ->
            el(ref, ElementRole.BUTTON, "赞", box(800, top, 900, top + 10)).copy(resourceId = "like")
        }
        val old = snapshot("s1", listOf(like("e1", 100), like("e2", 200), like("e3", 300)))
        val now = snapshot("s2", listOf(like("e1", 100), like("e2", 200)))
        assertEquals(ScreenDiff(removed = listOf(ElementRef("e3"))), differ.diff(old, now))
    }

    @Test
    fun `OCR lines are compared as printed`() {
        val loading = OcrLine("加载中", box(400, 500, 600, 560), 0.9)
        val done = OcrLine("已完成", box(400, 500, 600, 560), 0.8)
        val old = snapshot("s1", emptyList()).copy(ocr = listOf(loading, loading))
        assertTrue(differ.diff(old, old.copy(ocr = listOf(loading.copy(confidence = 0.5), loading))).isEmpty)
        val diff = differ.diff(old, old.copy(ocr = listOf(loading, done)))
        assertEquals(listOf(done), diff.ocrAdded)
        assertEquals(listOf(loading), diff.ocrRemoved, "repeated lines pair one to one")
        val moved = loading.copy(bounds = box(400, 600, 600, 660))
        val movedDiff = differ.diff(old.copy(ocr = listOf(loading)), old.copy(ocr = listOf(moved)))
        assertEquals(ScreenDiff(ocrAdded = listOf(moved), ocrRemoved = listOf(loading)), movedDiff)
        assertEquals(ScreenDiff(ocrRemoved = listOf(loading, loading)), differ.diff(old, old.copy(ocr = null)))
    }

    @Test
    fun `tolerance is validated`() {
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = -1) }
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = 1000) }
    }
}
