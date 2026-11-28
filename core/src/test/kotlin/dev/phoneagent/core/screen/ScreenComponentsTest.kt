package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The analyzer and the presenter come from one [ScreenComponents], so they read lines the same way. */
class ScreenComponentsTest {
    private val click = setOf(ElementState.CLICKABLE)
    private val row = el("e30", ElementRole.BUTTON, null, box(0, 600, 999, 660), click)
    private val number = el("e31", ElementRole.TEXT, "订单 A", box(40, 610, 400, 650)).under("e30")

    private val notes = (1..3).map { el("e$it", ElementRole.TEXT, "说明 $it", box(40, it * 100, 960, it * 100 + 40)) }

    private fun badge(text: String) = el("e32", ElementRole.TEXT, text, box(600, 610, 960, 650)).under("e30")

    @Test
    fun `a change is shown when absorption is not the default`() {
        // With one piece at most, the row absorbs neither text: the model reads e32 on its own line.
        val screens =
            ScreenComponents(ScreenFormatConfig(absorbMaxPieces = 1), lexicon = Screens.LEXICON, vision = true)
        val before = snapshot("s1", notes + listOf(row, number, badge("待付款")))
        assertTrue(screens.presenter.page(before, SnapshotDetail.BRIEF).contains("@e32 \"待付款\""))
        val after = snapshot("s2", notes + listOf(row, number, badge("已发货")))
        val text =
            screens.presenter.afterAction(
                ActionOutcome(Status.Ok, Effect.CHANGED, after, screens.analyzer.diff(before, after)),
                before.id,
            )
        assertEquals("screen s2 · 与 s1 相比的变化（其余不变）\n~ @e32 \"已发货\"", text)
        // The same rows under the default config: the row absorbs both, so the change is the row's.
        val defaults = ScreenComponents(lexicon = Screens.LEXICON, vision = true)
        val absorbed =
            defaults.presenter.afterAction(
                ActionOutcome(Status.Ok, Effect.CHANGED, after, defaults.analyzer.diff(before, after)),
                before.id,
            )
        assertEquals("screen s2 · 与 s1 相比的变化（其余不变）\n~ @e30 按钮 \"订单 A · 已发货\" (499,630)", absorbed)
    }

    @Test
    fun `quality follows the same absorption, and the lexicon and vision reach the presenter`() {
        // Four unnamed rows with two texts each: named rows by default, unnamed ones with one piece at most.
        val rows =
            (0..3).flatMap { i ->
                val top = 100 + i * 200
                listOf(
                    el("e${40 + i * 3}", ElementRole.BUTTON, null, box(0, top, 999, top + 80), click),
                    el(
                        "e${41 + i * 3}",
                        ElementRole.TEXT,
                        "会话 $i",
                        box(40, top + 10, 400, top + 40),
                    ).under("e${40 + i * 3}"),
                    el(
                        "e${42 + i * 3}",
                        ElementRole.TEXT,
                        "10:2$i",
                        box(800, top + 10, 960, top + 40),
                    ).under("e${40 + i * 3}"),
                )
            }
        val strict = ScreenComponents(ScreenFormatConfig(absorbMaxPieces = 1), lexicon = Screens.LEXICON, vision = true)
        val loose = ScreenComponents(lexicon = Screens.LEXICON, vision = true)
        assertEquals(TreeQuality.SPARSE, strict.analyzer.quality(rows, Screens.AGENT, emptySet()))
        assertEquals(TreeQuality.GOOD, loose.analyzer.quality(rows, Screens.AGENT, setOf(ScreenFlag.KEYBOARD)))
        assertEquals(ScreenFingerprint.of("p", "a", rows, null), strict.analyzer.fingerprint("p", "a", rows, null))
        val pay = snapshot("s3", listOf(el("e1", ElementRole.BUTTON, "支付", box(40, 880, 960, 960), click)))
        assertTrue(strict.presenter.page(pay, SnapshotDetail.BRIEF).contains("@e1 ⚠ 按钮 \"支付\""))
        assertEquals("「疑似银行卡 尾号7894」", strict.presenter.mask("6222021234567894"))
        val sparse = pay.copy(quality = TreeQuality.SPARSE)
        val blind = ScreenComponents(lexicon = Screens.LEXICON, vision = false)
        assertTrue(strict.presenter.page(sparse, SnapshotDetail.BRIEF).contains("截图"))
        assertFalse(blind.presenter.page(sparse, SnapshotDetail.BRIEF).contains("截图"))
    }
}
