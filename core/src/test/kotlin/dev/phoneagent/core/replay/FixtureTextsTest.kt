package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolStarted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/** The decision sequence a replay compares and asks for a RULES_VERSION bump on (engineering-baseline §3.2). */
class FixtureTextsTest {
    private val call = ToolCallId("call-1")
    private val card =
        ConfirmCard(call, ConfirmReason.OUTWARD, "发送回复", "张三", "十点半", Money(100, "CNY"), "com.tencent.mm")

    private fun line(event: SessionEvent): String = FixtureTexts.decisions(listOf(SessionRecord(1, 0, event))).single()

    private fun decided(verdict: Verdict) =
        PolicyVerdictRecorded(call, SecurityLevel.STANDARD, ActionIntent(IntentKind.OUTWARD, "com.tencent.mm"), verdict)

    private fun judged(
        outcome: JudgmentOutcome,
        rule: String?,
        hint: String? = null,
    ) = ScreenJudged(call, SnapshotId("s1"), JudgmentPurpose.TOOL, outcome, rule, hint)

    @Test
    fun `every part of a verdict is in its line`() {
        val confirm = line(decided(Verdict.Confirm(card)))
        val cards =
            mapOf(
                "reason" to card.copy(reason = ConfirmReason.DELETE),
                "action" to card.copy(action = "删除消息"),
                "target" to card.copy(target = "李四"),
                "content" to card.copy(content = "十一点"),
                "amount" to card.copy(amount = Money(200, "CNY")),
                "pkg" to card.copy(pkg = "com.example.chat"),
            )
        val refuse = line(decided(Verdict.Refuse("hard.finance_app", "不能操作")))
        assertAll(
            cards.map { (field, other) ->
                { assertNotEquals(confirm, line(decided(Verdict.Confirm(other))), "card $field") }
            } +
                listOf(
                    {
                        assertNotEquals(
                            line(decided(Verdict.HandOff(HandoffKind.LOGIN, "log in"))),
                            line(decided(Verdict.HandOff(HandoffKind.PAYMENT, "log in"))),
                            "hand-off kind",
                        )
                    },
                    {
                        assertNotEquals(refuse, line(decided(Verdict.Refuse("hard.blocked_app", "不能操作"))), "reason")
                    },
                    {
                        assertNotEquals(refuse, line(decided(Verdict.Refuse("hard.finance_app", "换个说法"))), "hint")
                    },
                ),
        )
    }

    @Test
    fun `every part of a screen judgment is in its line`() {
        val withheld = line(judged(JudgmentOutcome.WITHHELD, "hard.finance_app", "不能看"))
        assertAll(
            { assertNotEquals(line(judged(JudgmentOutcome.SHOWN, null)), withheld, "outcome") },
            { assertNotEquals(line(judged(JudgmentOutcome.WITHHELD, ScreenJudged.CONFIRM)), withheld, "rule") },
            { assertNotEquals(line(judged(JudgmentOutcome.WITHHELD, "hard.finance_app", "换个说法")), withheld, "hint") },
        )
    }

    @Test
    fun `the decisions are the verdicts and the screen judgments, in log order`() {
        val records =
            listOf(
                TaskStarted(SecurityLevel.STANDARD, unattended = false),
                judged(JudgmentOutcome.SHOWN, null),
                decided(Verdict.Allow),
                ToolStarted(call, 3),
            ).mapIndexed { i, event -> SessionRecord(i + 1L, 0, event) }
        assertEquals(
            listOf("screen/judged", "policy/verdict"),
            FixtureTexts.decisions(records).map { it.substringBefore(' ') },
        )
    }
}
