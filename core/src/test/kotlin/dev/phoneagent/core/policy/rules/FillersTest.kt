package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.TextPaths.Path
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.tool.RiskClass
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "also" left after a joiner ("Send and also delete", [Words.ENGLISH_FILLER]), written any number
 * of times: every entry reads it as written once, on a control, in a dialog and in a row (Codex
 * catch-up FWD-B1: a regex that recursed once per "also" threw StackOverflowError at 5,000).
 */
class FillersTest {
    private val tuning = PolicyTuning()
    private val policy = Fx.rules()
    private val paths = TextPaths(policy)

    /** "Send and also … also [tail]" with [times] "also". */
    private fun said(
        times: Int,
        tail: String,
    ): String = "Send and " + "also ".repeat(times) + tail

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
                    val once = paths.entries(path, said(1, tail))
                    listOfNotNull(5_000, most.takeIf { tail == "delete" }).flatMap { times ->
                        val long = said(times, tail)
                        val readable =
                            cell(
                                "$path $times readable",
                            ) { assertTrue(Reading(tuning).readable(paths.on(path, long).first)) }
                        listOf(readable) +
                            paths.entries(path, long).zip(once).map { (many, one) ->
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
            val (s, tapped) = paths.on(path, said)
            val call = tap(tapped.ref.value)
            policy.lexicon.activationRisk(tapped, s)?.kind to
                paths.shown(
                    policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, s), paths.sent(s)),
                )
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
        val (s, tapped) = paths.on(Path.CONTROL, "Send and alsodelete")
        assertEquals(IntentKind.OUTWARD, policy.lexicon.activationRisk(tapped, s)?.kind)
        val (spaced, button) = paths.on(Path.CONTROL, "Send and also delete")
        assertEquals(IntentKind.DELETE, policy.lexicon.activationRisk(button, spaced)?.kind)
    }
}
