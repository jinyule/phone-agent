package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
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
        val reparented = before.copy(elements = before.elements.map { it.copy(parent = null) })
        assertTrue(differ.diff(before, reparented).isEmpty)
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
        assertTrue(diff.removed.isEmpty())
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
        assertEquals(listOf("e2"), diff.added.map { it.ref.value })
        assertEquals(listOf(ElementRef("e2")), diff.removed)
    }

    @Test
    fun `a move beyond the tolerance is a change, a small one is not`() {
        val button = el("e1", ElementRole.BUTTON, "发送", box(880, 930, 980, 980))
        val nudged = button.copy(bounds = box(880, 940, 980, 990))
        val moved = button.copy(bounds = box(880, 500, 980, 550))
        assertTrue(differ.diff(snapshot("s1", listOf(button)), snapshot("s2", listOf(nudged))).isEmpty)
        assertEquals(listOf(moved), differ.diff(snapshot("s1", listOf(button)), snapshot("s2", listOf(moved))).changed)
        val horizontal = button.copy(bounds = box(100, 930, 200, 980))
        assertEquals(
            listOf(horizontal),
            differ.diff(snapshot("s1", listOf(button)), snapshot("s2", listOf(horizontal))).changed,
        )
        val strict = ScreenDiffer(DiffConfig(moveTolerance = 0))
        assertEquals(
            listOf(nudged),
            strict.diff(snapshot("s1", listOf(button)), snapshot("s2", listOf(nudged))).changed,
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
    fun `tolerance is validated`() {
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = -1) }
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = 1000) }
    }
}
