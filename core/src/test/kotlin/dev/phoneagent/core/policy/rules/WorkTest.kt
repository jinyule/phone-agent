package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ElementRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The step counts that [BoundsTest] bounds (policy-rules.md §8) count what they say: each path
 * once per point, element or field of a snapshot, and nothing when asked again.
 */
class WorkTest {
    private val s =
        screen(
            el("e1", "确定", bounds = box(0, 0, 999, 999)),
            el("e2", "取消", bounds = box(400, 400, 600, 450)).copy(parent = ElementRef("e1")),
            text("t1", "提示", box(0, 0, 500, 40)),
            ocr = listOf(ocr("删除", box(450, 410, 550, 440)), ocr("角落", box(0, 0, 10, 10))),
        )

    private fun counts(read: () -> Unit): Map<String, Long> {
        Work.reset()
        read()
        return Work.counts().filterValues { it != 0L }
    }

    @Test
    fun `a touch looks at the screen once, and at nothing when asked again`() {
        val risks = Reading(PolicyTuning()).risks
        val point = Aim.Point(500, 425)
        // Three elements looked at in five passes, and e1 marked once as the ancestor of e2; the
        // two OCR lines at the point; e2 alone takes the touch, with one text of its own and one drawn.
        val touch =
            mapOf(
                "touches" to 1L,
                "under" to 16L,
                "points" to 1L,
                "ocr" to 2L,
                "owns" to 1L,
                "judged" to 1L,
                "texts" to 2L,
            )
        assertEquals(touch, counts { risks.at(point, long = false, s) })
        assertEquals(emptyMap<String, Long>(), counts { risks.at(point, long = false, s) })
        // A click of e1: its center, its own text, its one child looked at for its content, and its
        // dialog read once (three names, two OCR lines).
        val click =
            mapOf(
                "points" to 1L,
                "ocr" to 2L,
                "owns" to 1L,
                "judged" to 1L,
                "texts" to 1L,
                "dialog" to 5L,
                "content" to 1L,
            )
        assertEquals(click, counts { risks.activation(s.elements[0], s) })
        assertEquals(emptyMap<String, Long>(), counts { risks.activation(s.elements[0], s) })
        // What the touch shows: e2's text and the line drawn at the point, then that line again.
        assertEquals(mapOf("gathered" to 3L), counts { risks.at(point, long = false, s).texts })
        assertEquals(emptyMap<String, Long>(), counts { risks.at(point, long = false, s).texts })
    }

    @Test
    fun `a field's row and a control's place among the fields are looked at once`() {
        val reading = Reading(PolicyTuning())
        val input = field("f1", hint = "搜索", bounds = box(0, 900, 800, 960))
        val row = screen(input, el("e1", "搜索", bounds = box(820, 900, 999, 960)))
        val looked = counts { reading.searches.isSearch(input, row) }
        assertEquals(2L, looked["row"])
        assertEquals(1L, looked["fields"])
        assertEquals(emptyMap<String, Long>(), counts { reading.searches.isSearch(input, row) })
        assertEquals(0L, Work.counts().getValue("row"))
    }
}
