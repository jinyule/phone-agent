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
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.RiskClass
import org.junit.jupiter.api.function.Executable

/**
 * One text on a chat with 张三 (draft 明天见), as a control's own label, a dialog's text or one text
 * of a row, read through every entry of [policy]: the tables of [FillersTest] and [JoinersTest].
 */
internal class TextPaths(
    private val policy: PolicyRules,
) {
    /** Where the text is: a control's own label, a dialog's text, one text of a row. */
    enum class Path { CONTROL, DIALOG, ROW }

    /** A chat with 张三 as its title and 明天见 as the draft, with [extra] on it. */
    fun chat(vararg extra: UiElement): ScreenSnapshot =
        screen(
            text("t1", "张三", box(0, 40, 500, 100)),
            field("e2", hint = "发消息", focused = true).copy(value = "明天见"),
            *extra,
        )

    /** The screen of [path] that says [said], and the element tapped on it. */
    fun on(
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
    fun sent(s: ScreenSnapshot): PolicyContext =
        ctx(SecurityLevel.STANDARD, s, operated = setOfNotNull(s.pkg), outwardConfirmed = setOfNotNull(s.pkg))

    /**
     * What each entry gives for a tap on [path] saying [said], without the parts that quote [said].
     * Each entry reads a screen of its own, so none finds what another left in the per-screen caches.
     */
    fun entries(
        path: Path,
        said: String,
    ): List<Pair<String, () -> Any?>> {
        val call = tap(on(path, said).second.ref.value)
        val resolved = { s: ScreenSnapshot -> policy.resolver.resolve(call, RiskClass.WRITE, s) }
        return listOf(
            "activationRisk" to { on(path, said).let { (s, tapped) -> policy.lexicon.activationRisk(tapped, s) } },
            "resolve" to { resolved(on(path, said).first).let { Triple(it.kind, it.pkg, it.target?.ref) } },
            "evaluate" to {
                val s = on(path, said).first
                shown(policy.engine.evaluate(call, ActionIntent(IntentKind.DELETE, Fx.APP), sent(s)))
            },
            "resolve, then evaluate" to {
                val s = on(path, said).first
                shown(policy.engine.evaluate(call, resolved(s), sent(s)))
            },
        )
    }

    /** The kind of [verdict], the reason it asks with and the draft it shows (it sends), not the label. */
    fun shown(verdict: Verdict): Any =
        when (verdict) {
            is Verdict.Confirm -> Triple("confirm", verdict.card.reason, verdict.card.content)
            Verdict.Allow, is Verdict.HandOff, is Verdict.Refuse -> verdict
        }
}

/** [check] as one cell of a table, named [name] also when it overflows the stack. */
fun cell(
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
