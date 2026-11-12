package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.box
import dev.phoneagent.core.policy.rules.Fx.ctx
import dev.phoneagent.core.policy.rules.Fx.el
import dev.phoneagent.core.policy.rules.Fx.field
import dev.phoneagent.core.policy.rules.Fx.screen
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.Fx.text
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * "also" left after a joiner ("Send and also delete", [Words.ENGLISH_FILLER]), written any number
 * of times: every entry reads it as written once, on a control, in a dialog and in a row (Codex
 * catch-up FWD-B1: a regex that recursed once per "also" threw StackOverflowError at 5,000).
 */
class FillersTest {
    private val tuning = PolicyTuning()
    private val policy = Fx.rules()

    /** Where the text is: a control's own label, a dialog's text, one text of a row. */
    private enum class Path { CONTROL, DIALOG, ROW }

    /** A chat with 张三 as its title and 明天见 as the draft, with [extra] on it. */
    private fun chat(vararg extra: UiElement): ScreenSnapshot =
        screen(
            text("t1", "张三", box(0, 40, 500, 100)),
            field("e2", hint = "发消息", focused = true).copy(value = "明天见"),
            *extra,
        )

    /** The screen of [path] that says [said], and the element tapped on it. */
    private fun on(
        path: Path,
        said: String,
    ): Pair<ScreenSnapshot, UiElement> {
        val tapped =
            when (path) {
                Path.CONTROL -> el("e1", said, bounds = box(820, 900, 999, 960))
                Path.DIALOG -> el("b1", "OK").copy(window = 1)
                Path.ROW -> el("e1", bounds = box(0, 300, 999, 400))
            }
        val texts =
            when (path) {
                Path.CONTROL -> {
                    emptyList()
                }

                Path.DIALOG -> {
                    listOf(text("t3", "$said?", window = 1))
                }

                Path.ROW -> {
                    listOf(
                        text("t4", "Alice", box(0, 300, 500, 340), parent = "e1"),
                        text("t5", said, box(0, 350, 500, 390), parent = "e1"),
                    )
                }
            }
        return chat(tapped, *texts.toTypedArray()) to tapped
    }

    /** At 标准, in an app that has had a send confirmed: a part that deletes asks, one that only sends does not. */
    private fun sent(s: ScreenSnapshot): PolicyContext =
        ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))

    /** What each entry gives for a tap on [path] saying [said], without the parts that quote [said]. */
    private fun entries(
        path: Path,
        said: String,
    ): List<Pair<String, () -> Any?>> {
        val (s, tapped) = on(path, said)
        val call = tap(tapped.ref.value)
        return listOf(
            "activationRisk" to { policy.lexicon.activationRisk(tapped, s) },
            "resolve" to {
                policy.resolver.resolve(call, RiskClass.WRITE, s).let { Triple(it.kind, it.pkg, it.target?.ref) }
            },
            "evaluate" to { shown(policy.engine.evaluate(call, ActionIntent(IntentKind.DELETE, Fx.APP), sent(s))) },
            "resolve, then evaluate" to {
                shown(policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, s), sent(s)))
            },
        )
    }

    /** The kind of [verdict], the reason it asks with and the draft it shows (it sends), not the label. */
    private fun shown(verdict: Verdict): Any =
        when (verdict) {
            is Verdict.Confirm -> Triple("confirm", verdict.card.reason, verdict.card.content)
            Verdict.Allow, is Verdict.HandOff, is Verdict.Refuse -> verdict
        }

    /** "Send and also … also [tail]" with [times] "also". */
    private fun said(
        times: Int,
        tail: String,
    ): String = "Send and " + "also ".repeat(times) + tail

    /** [check] as one cell of a table, named [name] also when it overflows the stack. */
    private fun cell(
        name: String,
        check: () -> Unit,
    ): Executable =
        Executable {
            try {
                check()
            } catch (overflow: StackOverflowError) {
                // The failure this class is about: say which cell, as its trace does not.
                throw AssertionError("$name: $overflow", overflow)
            }
        }

    /** Parts after the fillers at the length limit of a row's texts and of a dialog's, and one past it. */
    private val tails: List<String> =
        listOf(tuning.labelMax, tuning.contextMax).flatMap { max ->
            listOf("delete " + "x".repeat(max - "delete".length), "delete " + "x".repeat(max - "delete".length + 1))
        } + "delete"

    @Test
    fun `also written thousands of times reads as written once, on every entry and path`() {
        // Codex's 5,000 with each tail, and as many as fit in the text a screen may have (§8 maxScreenText).
        val most = (tuning.maxScreenText - 200) / "also ".length
        val cells =
            Path.entries.flatMap { path ->
                tails.flatMap { tail ->
                    val once = entries(path, said(1, tail))
                    listOfNotNull(5_000, most.takeIf { tail == "delete" }).flatMap { times ->
                        val long = said(times, tail)
                        val readable =
                            cell("$path $times readable") { assertTrue(Reading(tuning).readable(on(path, long).first)) }
                        listOf(readable) +
                            entries(path, long).zip(once).map { (many, one) ->
                                val name = "$path ${one.first} $times ${tail.length}"
                                cell(name) { assertEquals(one.second(), many.second(), name) }
                            }
                    }
                }
            }
        assertAll(cells)
    }

    @Test
    fun `the most severe part decides, and the card shows the draft it also sends`() {
        // What the cells above compare with: one "also", then thousands, on each path.
        val deletes = Triple("confirm", ConfirmReason.DELETE, "明天见")
        val decide = { path: Path, said: String ->
            val (s, tapped) = on(path, said)
            val call = tap(tapped.ref.value)
            policy.lexicon.activationRisk(tapped, s)?.kind to
                shown(policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, s), sent(s)))
        }
        val joined =
            Path.entries.flatMap { path ->
                listOf(1, 5_000).map { times ->
                    val name = "$path $times"
                    cell(name) { assertEquals(IntentKind.DELETE to deletes, decide(path, said(times, "delete")), name) }
                }
            }
        // At the limit a row's text and a dialog's still split; one past it, they are read by their start only.
        val limits =
            listOf(Path.ROW to tuning.labelMax, Path.DIALOG to tuning.contextMax).flatMap { (path, max) ->
                listOf(1, 5_000).flatMap { times ->
                    val at = said(times, "delete " + "x".repeat(max - "delete".length))
                    val past = said(times, "delete " + "x".repeat(max - "delete".length + 1))
                    listOf(
                        cell("$path $times at $max") { assertEquals(IntentKind.DELETE to deletes, decide(path, at)) },
                        cell("$path $times past $max") {
                            assertEquals(IntentKind.OUTWARD to Verdict.Allow, decide(path, past))
                        },
                    )
                }
            }
        assertAll(joined + limits)
    }

    @Test
    fun `also is a filler only as a whole word`() {
        // "alsodelete" is one word, not "also delete": the part names nothing, the label only sends.
        val (s, tapped) = on(Path.CONTROL, "Send and alsodelete")
        assertEquals(IntentKind.OUTWARD, policy.lexicon.activationRisk(tapped, s)?.kind)
        val (spaced, button) = on(Path.CONTROL, "Send and also delete")
        assertEquals(IntentKind.DELETE, policy.lexicon.activationRisk(button, spaced)?.kind)
    }
}
