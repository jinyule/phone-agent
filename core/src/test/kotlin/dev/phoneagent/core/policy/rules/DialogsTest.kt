package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ElementRisk
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.ocr
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The dialog around an element ([Elements.context]) as the generic buttons and the app-management
 * rule read it (policy-rules.md §3.2, §6): from tables built once per snapshot, the same as reading
 * the dialog of each button again.
 */
class DialogsTest {
    private val reading = Reading(PolicyTuning())

    /** What a generic button does, read from its dialog directly. */
    private fun direct(
        button: UiElement,
        s: ScreenSnapshot,
    ): ElementRisk? {
        val hits = Elements.context(button, s).mapNotNull(reading.lexicon::inContext)
        val spend =
            hits.firstOrNull { it.kind == IntentKind.SPEND }
                ?: Hit(IntentKind.SPEND, "payment page").takeIf { reading.pages.isPayment(s) }
        val found = spend ?: hits.firstOrNull { it.kind == IntentKind.DELETE } ?: hits.firstOrNull()
        return found?.let { ElementRisk(it.kind, "${Text.bare(button.name.orEmpty())}:${it.matched}") }
    }

    @Test
    fun `generic buttons and app-management dialogs read the dialog Elements_context names`() {
        val rnd = Random(DIALOG_SEED)
        repeat(ROUNDS) { round ->
            val buttons =
                (1..rnd.nextInt(1, 4)).map {
                    val name = GENERIC[rnd.nextInt(GENERIC.size)]
                    el("e$it", name, bounds = box(it * 100, 900, it * 100 + 90, 950)).copy(window = WINDOWS.random(rnd))
                }
            val texts =
                (1..rnd.nextInt(0, 6)).map {
                    text("t$it", SAID.random(rnd), box(0, it * 50, 999, it * 50 + 40), window = WINDOWS.random(rnd))
                }
            val drawn =
                (
                    0 until
                        rnd.nextInt(
                            0,
                            3,
                        )
                ).map { ocr(SAID.random(rnd), box(0, 600 + it * 50, 999, 640 + it * 50)) }
            val blank = el("e9", bounds = box(0, 800, 100, 850)).copy(window = WINDOWS.random(rnd))
            val s = screen(*(buttons + texts + blank).toTypedArray(), ocr = drawn.ifEmpty { null })
            for (button in buttons) {
                assertEquals(direct(button, s), reading.risks.activation(button, s).risk, "round $round ${button.ref}")
                val manages = reading.settings.managesApp(Elements.context(button, s))
                assertEquals(manages, reading.settings.dialogManages(button, s), "round $round ${button.ref}")
            }
            for (other in texts) {
                val manages = reading.settings.managesApp(Elements.context(other, s))
                assertEquals(manages, reading.settings.dialogManages(other, s), "round $round ${other.ref}")
            }
        }
    }

    @Test
    fun `an element's own words are not its dialog`() {
        // Read as six characters, "确定，，，，，删除" is "确定"; the whole of it says "删除".
        val small = Reading(PolicyTuning(labelRead = 6))
        val ok = el("e1", "确定，，，，，删除").copy(window = 1)
        assertTrue(small.lexicon.isGeneric(ok.name.orEmpty()))
        assertEquals(IntentKind.DELETE, small.risks.activation(ok, screen(ok)).kind)
        val dialog = screen(ok, text("t1", "删除全部记录？", window = 1), text("t2", "删除", window = 1))
        assertEquals(ElementRisk(IntentKind.DELETE, "删除"), small.risks.activation(ok, dialog).risk)
        val stop = el("e1", "强行停止").copy(window = 1)
        assertFalse(reading.settings.dialogManages(stop, screen(stop)))
        assertTrue(reading.settings.dialogManages(stop, screen(stop, text("t1", "卸载", window = 1))))
        assertFalse(reading.settings.dialogManages(stop, screen(stop, text("t1", "卸载", window = 2))))
        assertTrue(reading.settings.dialogManages(stop, screen(stop, ocr = listOf(ocr("卸载")))))
    }

    private companion object {
        const val DIALOG_SEED = 20261103
        const val ROUNDS = 3000
        val WINDOWS = listOf(null, 1, 2)
        val GENERIC = listOf("确定", "OK", "好的", "继续")
        val SAID =
            listOf(
                "确认支付？",
                "删除全部记录？",
                "发送给张三？",
                "要停用此应用吗？",
                "卸载",
                "强行停止",
                "提示",
                "好的",
                "确定删除",
                "立即支付",
                "清除数据",
                " ",
                "",
            )
    }
}
