package dev.phoneagent.core.screen

import dev.phoneagent.core.ids.SnapshotId
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
        assertTrue(differ.diff(before, before.copy(id = SnapshotId("s13"))).isEmpty)
    }

    @Test
    fun `a parent change is a change even when the line reads the same`() {
        // The messages leave the list e5: same text, but the ref is now somewhere else in the tree.
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
        val diff = differ.diff(before, listless)
        assertEquals(listOf("e6", "e7"), diff.changed.map { it.ref.value })
        assertTrue(diff.added.isEmpty() && diff.removed.isEmpty())
        val box1 = el("e1", ElementRole.CONTAINER, null, box(0, 0, 999, 499))
        val box2 = el("e2", ElementRole.CONTAINER, null, box(0, 500, 999, 999))
        val ok = el("e3", ElementRole.BUTTON, "好", box(400, 450, 600, 550), setOf(ElementState.CLICKABLE))
        val moved =
            differ.diff(
                snapshot("s1", listOf(box1, box2, ok.under("e1"))),
                snapshot("s2", listOf(box1, box2, ok.under("e2"))),
            )
        assertEquals(ScreenDiff(base = SnapshotId("s1"), changed = listOf(ok.under("e2"))), moved)
    }

    @Test
    fun `a new parent that changes what a row absorbs changes the row`() {
        // e4 ("更多") stops being part of the button e3 and gets a line of its own.
        val reparented =
            before.copy(
                elements =
                    before.elements.map {
                        if (it.ref.value ==
                            "e4"
                        ) {
                            it.copy(parent = null)
                        } else {
                            it
                        }
                    },
            )
        val diff = differ.diff(before, reparented)
        assertEquals(listOf("e3"), diff.changed.map { it.ref.value })
        assertEquals(listOf("e4"), diff.added.map { it.ref.value })
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `a ref that changes is one element removed and another added`() {
        // The port gave the field a new ref (it could not tell it is the same field): e9 is void.
        val renumbered = before.elements.map { if (it.ref.value == "e9") it.copy(ref = ElementRef("e12")) else it }
        val diff = differ.diff(before, before.copy(elements = renumbered))
        assertEquals(listOf(ElementRef("e9")), diff.removed)
        assertEquals(listOf("e12"), diff.added.map { it.ref.value })
        assertTrue(diff.changed.isEmpty())
        // An equal element under a new ref is not taken for the old one.
        val like = el("e1", ElementRole.BUTTON, "赞", box(800, 100, 900, 150), setOf(ElementState.CLICKABLE))
        val replaced = like.copy(ref = ElementRef("e9"))
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), added = listOf(replaced), removed = listOf(ElementRef("e1"))),
            differ.diff(snapshot("s1", listOf(like)), snapshot("s2", listOf(replaced))),
        )
    }

    @Test
    fun `a row that goes away leaves the equal rows after it paired with themselves`() {
        // Alice's row e2 goes away; Bob's row keeps e4 and moves up into its place.
        val list = el("e1", ElementRole.LIST, "会话", box(0, 100, 999, 900), setOf(ElementState.SCROLLABLE))
        val click = setOf(ElementState.CLICKABLE)
        val alice = el("e2", ElementRole.BUTTON, null, box(0, 100, 999, 199), click).under("e1")
        val aliceText = el("e3", ElementRole.TEXT, "Alice", box(20, 120, 400, 180)).under("e2")
        val bob = el("e4", ElementRole.BUTTON, null, box(0, 200, 999, 299), click).under("e1")
        val bobText = el("e5", ElementRole.TEXT, "Bob", box(20, 220, 400, 280)).under("e4")
        val bobUp = bob.copy(bounds = box(0, 100, 999, 199))
        val diff =
            differ.diff(
                snapshot("s1", listOf(list, alice, aliceText, bob, bobText)),
                snapshot("s2", listOf(list, bobUp, bobText.copy(bounds = box(20, 120, 400, 180)))),
            )
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), removed = listOf(ElementRef("e2")), changed = listOf(bobUp)),
            diff,
        )
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
    fun `the same ref is the same element, whatever it reads now`() {
        val field = el("e1", ElementRole.INPUT, "搜索", states = setOf(ElementState.EDITABLE)).copy(value = "a")
        val label = el("e2", ElementRole.TEXT, "3 条新消息")
        val diff =
            differ.diff(
                snapshot("s1", listOf(field, label)),
                snapshot("s2", listOf(field.copy(value = "ab"), label.copy(name = "4 条新消息"))),
            )
        assertEquals(listOf("e1", "e2"), diff.changed.map { it.ref.value })
        assertTrue(diff.added.isEmpty() && diff.removed.isEmpty())
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
    fun `equal elements pair only by ref`() {
        val like = {
            ref: String,
            top: Int,
            ->
            el(ref, ElementRole.BUTTON, "赞", box(800, top, 900, top + 10)).copy(resourceId = "like")
        }
        val three = snapshot("s1", listOf(like("e1", 100), like("e2", 200), like("e3", 300)))
        // The last one goes away.
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), removed = listOf(ElementRef("e3"))),
            differ.diff(three, snapshot("s2", listOf(like("e1", 100), like("e2", 200)))),
        )
        // The middle one goes away and the last moves up into its place.
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), removed = listOf(ElementRef("e2")), changed = listOf(like("e3", 200))),
            differ.diff(three, snapshot("s2", listOf(like("e1", 100), like("e3", 200)))),
        )
        // The first one goes away; the others stay where they were.
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), removed = listOf(ElementRef("e1"))),
            differ.diff(three, snapshot("s2", listOf(like("e2", 200), like("e3", 300)))),
        )
        // A new equal button above e1 is the added one; e1 moved and changed.
        val inserted = snapshot("s3", listOf(like("e7", 50), like("e1", 150)))
        val diff = differ.diff(snapshot("s1", listOf(like("e1", 100))), inserted)
        assertEquals(listOf("e7"), diff.added.map { it.ref.value })
        assertEquals(listOf("e1"), diff.changed.map { it.ref.value })
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
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), ocrAdded = listOf(moved), ocrRemoved = listOf(loading)),
            movedDiff,
        )
    }

    @Test
    fun `OCR lines are compared only when the later screen was read with OCR`() {
        val expired = OcrLine("二维码已过期", box(300, 400, 700, 440), 0.9)
        val refresh = OcrLine("点击刷新", box(400, 460, 600, 500), 0.9)
        val read = snapshot("s1", emptyList()).copy(ocr = listOf(expired, refresh))
        // Not run on the later screen: the old lines are neither gone nor counted.
        val unread = differ.diff(read, read.copy(ocr = null))
        assertEquals(emptyList<OcrLine>(), unread.ocrRemoved)
        assertTrue(unread.isEmpty)
        assertEquals(ScreenDiff(base = SnapshotId("s1"), ocrUnverified = true), unread)
        // Ran and found nothing: the old lines are gone.
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), ocrRemoved = listOf(expired, refresh)),
            differ.diff(read, read.copy(ocr = emptyList())),
        )
        // Not run on the earlier screen: what is found now is new.
        assertEquals(
            ScreenDiff(base = SnapshotId("s1"), ocrAdded = listOf(expired)),
            differ.diff(read.copy(ocr = null), read.copy(ocr = listOf(expired))),
        )
        // Nothing was read before: nothing is left unverified.
        assertEquals(ScreenDiff(base = SnapshotId("s1")), differ.diff(read.copy(ocr = null), read.copy(ocr = null)))
        assertEquals(
            ScreenDiff(base = SnapshotId("s1")),
            differ.diff(read.copy(ocr = emptyList()), read.copy(ocr = null)),
        )
    }

    @Test
    fun `a diff is based on the earlier snapshot, whether or not the later one was read with OCR`() {
        val line = OcrLine("已完成", box(400, 500, 600, 560), 0.9)
        val earlier = snapshot("s4", emptyList()).copy(ocr = listOf(line))
        val read = snapshot("s7", emptyList()).copy(ocr = emptyList())
        val unread = read.copy(ocr = null)
        assertEquals(SnapshotId("s4"), differ.diff(earlier, read).base, "compared OCR")
        assertEquals(SnapshotId("s4"), differ.diff(earlier, unread).base, "OCR not run on the later screen")
        assertEquals(SnapshotId("s7"), differ.diff(read, earlier).base, "the other way round")
        assertEquals(SnapshotId("s7"), differ.diff(unread, earlier.copy(ocr = null)).base, "neither read with OCR")
    }

    @Test
    fun `tolerance is validated`() {
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = -1) }
        assertThrows<IllegalArgumentException> { DiffConfig(moveTolerance = 1000) }
    }
}
