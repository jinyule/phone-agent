package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.tools.Samples
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
    fun `controls named by the texts they absorb are named`() {
        // Four unnamed buttons, each with one text child: the screen reads them as named buttons.
        val buttons =
            (1..4).flatMap {
                val top = it * 100
                listOf(
                    el("b$it", ElementRole.BUTTON, bounds = box(0, top, 999, top + 80), states = click),
                    el(
                        "t$it",
                        ElementRole.TEXT,
                        "选项$it",
                        box(40, top + 20, 400, top + 60),
                    ).copy(parent = ElementRef("b$it")),
                )
            }
        assertEquals(TreeQuality.GOOD, assessor.assess(buttons))
        // A large card is not a blind area when it absorbs its texts either.
        val card = el("c1", ElementRole.CONTAINER, bounds = box(0, 100, 999, 399), states = click)
        val cardTexts =
            listOf("标题", "副标题").mapIndexed { i, name ->
                el(
                    "k${i + 1}",
                    ElementRole.TEXT,
                    name,
                    box(40, 150 + i * 60, 600, 190 + i * 60),
                ).copy(parent = ElementRef("c1"))
            }
        assertEquals(TreeQuality.GOOD, assessor.assess(listOf(card) + cardTexts + texts(1)))
        // Children that are images without names do not name their button.
        val iconButtons =
            (1..4).flatMap {
                listOf(
                    el("b$it", ElementRole.BUTTON, bounds = box(it * 200, 900, it * 200 + 150, 990), states = click),
                    el("i$it", ElementRole.IMAGE, bounds = box(it * 200, 900, it * 200 + 150, 990))
                        .copy(parent = ElementRef("b$it")),
                )
            }
        assertEquals(TreeQuality.SPARSE, assessor.assess(texts(5) + iconButtons))
    }

    @Test
    fun `everyday pages are good`() {
        assertEquals(TreeQuality.GOOD, assessor.assess(Samples.longList.elements), "conversation list")
        assertEquals(TreeQuality.GOOD, assessor.assess(Samples.chatInfo.elements), "chat info")
        assertEquals(TreeQuality.GOOD, assessor.assess(settingsPage()), "settings")
        assertEquals(TreeQuality.GOOD, assessor.assess(searchPage()), "search")
    }

    /** A settings page: rows are tappable containers with a title, a summary and maybe a switch. */
    private fun settingsPage() =
        buildList {
            add(el("e1", ElementRole.BUTTON, "返回", box(0, 30, 80, 90), click))
            add(el("e2", ElementRole.TEXT, "设置", box(400, 40, 600, 80)))
            add(el("e3", ElementRole.LIST, null, box(0, 100, 999, 999), setOf(ElementState.SCROLLABLE)))
            for (i in 1..8) {
                val top = 100 + i * 100
                add(
                    el(
                        "r$i",
                        ElementRole.CONTAINER,
                        null,
                        box(0, top, 999, top + 90),
                        click,
                    ).copy(parent = ElementRef("e3")),
                )
                add(
                    el(
                        "n$i",
                        ElementRole.TEXT,
                        "设置项$i",
                        box(40, top + 10, 600, top + 40),
                    ).copy(parent = ElementRef("r$i")),
                )
                add(
                    el(
                        "d$i",
                        ElementRole.TEXT,
                        "说明$i",
                        box(40, top + 50, 600, top + 80),
                    ).copy(parent = ElementRef("r$i")),
                )
            }
        }

    /** A search page: a field with a hint, a cancel button, and result rows with absorbed texts. */
    private fun searchPage() =
        buildList {
            add(
                el("e1", ElementRole.INPUT, bounds = box(0, 30, 850, 90), states = click + ElementState.EDITABLE)
                    .copy(hint = "搜索"),
            )
            add(el("e2", ElementRole.BUTTON, "取消", box(860, 30, 999, 90), click))
            add(el("e3", ElementRole.LIST, null, box(0, 100, 999, 999), setOf(ElementState.SCROLLABLE)))
            for (i in 1..6) {
                val top = 100 + i * 120
                add(
                    el(
                        "r$i",
                        ElementRole.BUTTON,
                        null,
                        box(0, top, 999, top + 110),
                        click,
                    ).copy(parent = ElementRef("e3")),
                )
                add(
                    el(
                        "a$i",
                        ElementRole.IMAGE,
                        null,
                        box(20, top + 10, 120, top + 100),
                    ).copy(parent = ElementRef("r$i")),
                )
                add(
                    el(
                        "t$i",
                        ElementRole.TEXT,
                        "结果$i",
                        box(140, top + 10, 900, top + 50),
                    ).copy(parent = ElementRef("r$i")),
                )
            }
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
