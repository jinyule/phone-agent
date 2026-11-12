package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.Fx.tap
import dev.phoneagent.core.policy.rules.TextPaths.Path
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.tool.RiskClass
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The word characters of the filler and joiner words, the same on every platform (RULES_VERSION 7,
 * policy-rules.md §8): ASCII letters, digits and `_`, and a non-spacing mark after a letter or
 * digit. Any other letter or mark next to "also", "and" or "then" ends the word, where Android's
 * regexes, whose `\w` and `\b` are Unicode, read it as part of the word and these labels only sent
 * (Codex catch-up FWD S-1).
 */
class WordCharactersTest {
    private val policy = Fx.rules()
    private val paths = TextPaths(policy)

    /** What a tap on [path] saying [said] is read as, and what the engine shows for it once resolved. */
    private fun decide(
        path: Path,
        said: String,
    ): Pair<IntentKind?, Any> {
        val (s, tapped) = paths.on(path, said)
        val call = tap(tapped.ref.value)
        return policy.lexicon.activationRisk(tapped, s)?.kind to
            paths.shown(policy.engine.evaluate(call, policy.resolver.resolve(call, RiskClass.WRITE, s), paths.sent(s)))
    }

    @Test
    fun `a letter or mark outside ASCII ends a filler or joiner, on every path`() {
        val deletes = IntentKind.DELETE to Triple("confirm", ConfirmReason.DELETE, "明天见")
        val labels =
            listOf(
                // Codex's three: a letter and a spacing mark (Mc) after "also", a letter before "and".
                Triple("also, letter", "Send and also$E_ACUTE delete", deletes),
                Triple("also, Mc", "Send and also$VISARGA delete", deletes),
                // "sendé" is no "send" (a Latin letter goes on), so the card does not show the draft.
                Triple(
                    "and, letter",
                    "Send${E_ACUTE}and delete",
                    IntentKind.DELETE to Triple("confirm", ConfirmReason.DELETE, null),
                ),
                // An enclosing mark (Me) on either side, a spacing one before "and".
                Triple("also, Me", "Send and also$CIRCLE delete", deletes),
                Triple("and, Me", "Send${CIRCLE}and delete", deletes),
                Triple("and, Mc", "Send${VISARGA}and delete", deletes),
                // Chinese against "and": the Chinese words decide the first, the English part the second.
                Triple("and, Chinese", "发送and删除", deletes),
                Triple("and, Chinese, English", "发送and delete", deletes),
                // A non-spacing mark on a letter stays in its word, on every platform: one word, it only sends.
                Triple("and, Mn", "Send${LOW_LINE}and delete", IntentKind.OUTWARD to Verdict.Allow),
            )
        val cells =
            Path.entries.flatMap { path ->
                labels.map { (name, said, expected) ->
                    cell("$path $name") { assertEquals(expected, decide(path, said), "$path $name") }
                }
            }
        assertAll(cells)
    }

    @Test
    fun `the scans end a word where a character other than an ASCII one or a mark on it begins`() {
        val fillers =
            listOf(E_ACUTE, VISARGA, CIRCLE, "发").map { after ->
                cell("also $after") { assertEquals("delete", withoutFillers("also$after delete")) }
            } + cell("also, Mn") { assertEquals("also$LOW_LINE delete", withoutFillers("also$LOW_LINE delete")) }
        val joiners =
            listOf(E_ACUTE, VISARGA, CIRCLE, "送").map { before ->
                cell("$before and") {
                    assertEquals(listOf("send$before", " delete"), splitJoiners("send${before}and delete"))
                }
            } +
                listOf(
                    cell("Chinese") { assertEquals(listOf("发送", "删除"), splitJoiners("发送and删除")) },
                    cell("Mn and") {
                        assertEquals(listOf("send${LOW_LINE}and delete"), splitJoiners("send${LOW_LINE}and delete"))
                    },
                )
        assertAll(fillers + joiners)
    }
}

/** [code] as a string, so the test data has no escapes. */
private fun point(code: Int): String = String(Character.toChars(code))

/** U+00E9, a Latin letter outside ASCII. */
private val E_ACUTE = point(0xE9)

/** U+0903 DEVANAGARI SIGN VISARGA, a spacing mark (Mc). */
private val VISARGA = point(0x0903)

/** U+20DD COMBINING ENCLOSING CIRCLE, an enclosing mark (Me). */
private val CIRCLE = point(0x20DD)

/** U+0332 COMBINING LOW LINE, a non-spacing mark (Mn). */
private val LOW_LINE = point(0x0332)
