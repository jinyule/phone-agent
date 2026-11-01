package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TreeQualityAssessorTest {
    private val assessor = TreeQualityAssessor()
    private val click = setOf(ElementState.CLICKABLE)

    private fun texts(n: Int) = (1..n).map { el("t$it", ElementRole.TEXT, "文字$it", box(0, it * 20, 300, it * 20 + 10)) }

    @Test
    fun `a tree without any text is empty`() {
        assertEquals(TreeQuality.EMPTY, assessor.assess(emptyList()))
        assertEquals(
            TreeQuality.EMPTY,
            assessor.assess(listOf(el("e1", ElementRole.BUTTON, states = click), el("e2", ElementRole.IMAGE))),
        )
        assertEquals(TreeQuality.EMPTY, assessor.assess(listOf(el("e1", ElementRole.TEXT, "  "))))
    }

    @Test
    fun `an ordinary page is good`() {
        assertEquals(TreeQuality.GOOD, assessor.assess(Screens.CHAT_ELEMENTS))
        assertEquals(TreeQuality.GOOD, assessor.assess(texts(3)))
    }

    @Test
    fun `too few named elements is sparse`() {
        assertEquals(TreeQuality.SPARSE, assessor.assess(texts(2)))
        // Value and hint count as text too.
        val withHint = texts(2) + el("i1", ElementRole.INPUT, states = setOf(ElementState.EDITABLE)).copy(hint = "搜索")
        assertEquals(TreeQuality.GOOD, assessor.assess(withHint))
    }

    @Test
    fun `mostly unnamed controls is sparse`() {
        val unnamed = (1..3).map { el("b$it", ElementRole.BUTTON, states = click) }
        val named = el("b4", ElementRole.BUTTON, "确定", states = click)
        assertEquals(TreeQuality.SPARSE, assessor.assess(texts(5) + unnamed + named))
        // Below the minimum number of controls the share does not apply.
        assertEquals(TreeQuality.GOOD, assessor.assess(texts(5) + unnamed.take(2) + named.copy(name = null)))
        // Half unnamed is below the 2/3 share.
        assertEquals(
            TreeQuality.GOOD,
            assessor.assess(
                texts(5) + unnamed.take(2) + named +
                    named.copy(
                        ref =
                            dev.phoneagent.core.ports
                                .ElementRef("b5"),
                    ),
            ),
        )
    }

    @Test
    fun `a large unnamed surface with little text inside is sparse`() {
        val canvas = el("c1", ElementRole.OTHER, bounds = box(0, 100, 999, 800))
        val outside = (1..3).map { el("t$it", ElementRole.TEXT, "外面$it", box(0, 850 + it * 30, 300, 870 + it * 30)) }
        assertEquals(TreeQuality.SPARSE, assessor.assess(outside + canvas))
        // A container that groups enough named elements is a backdrop, not a blind area.
        val inside = (1..3).map { el("i$it", ElementRole.TEXT, "里面$it", box(100, 200 + it * 50, 400, 230 + it * 50)) }
        assertEquals(TreeQuality.GOOD, assessor.assess(outside + canvas.copy(role = ElementRole.CONTAINER) + inside))
        // Small unnamed images are fine; named surfaces are fine.
        assertEquals(TreeQuality.GOOD, assessor.assess(outside + canvas.copy(bounds = box(0, 100, 300, 300))))
        assertEquals(TreeQuality.GOOD, assessor.assess(outside + canvas.copy(name = "地图")))
        assertEquals(
            TreeQuality.GOOD,
            assessor.assess(outside + canvas.copy(role = ElementRole.BUTTON, states = click)),
        )
    }

    @Test
    fun `config is validated`() {
        assertThrows<IllegalArgumentException> { TreeQualityConfig(minNamedElements = 0) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(minControlsForShare = 0) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(unnamedControlShare = 0.0) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(unnamedControlShare = 1.5) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(blindAreaShare = 0.0) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(blindAreaShare = 1.01) }
        assertThrows<IllegalArgumentException> { TreeQualityConfig(namedInsideBlindArea = 0) }
        assertEquals(TreeQuality.SPARSE, TreeQualityAssessor(TreeQualityConfig(minNamedElements = 10)).assess(texts(9)))
    }
}
