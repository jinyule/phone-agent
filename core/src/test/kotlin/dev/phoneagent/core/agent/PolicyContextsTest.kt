package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.answered
import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.testing.ScriptedScreen.Companion.snapshotOf
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The policy's inputs derived from the log (contracts addendum §8, §14). */
class PolicyContextsTest {
    private val s1 = snapshotOf("s1")
    private val s2 = snapshotOf("s2")
    private val s3 = snapshotOf("s3")

    private val start = TaskStarted(SecurityLevel.STANDARD, unattended = false)

    private fun id(value: String) = ToolCallId(value)

    private fun outcome(after: dev.phoneagent.core.ports.ScreenSnapshot?) =
        ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED, after)

    private fun acted(
        call: String,
        after: dev.phoneagent.core.ports.ScreenSnapshot?,
    ) = ScreenActed(id(call), on = null, action = UiAction.Key(SystemKey.BACK), outcome = outcome(after))

    private fun launched(
        call: String,
        after: dev.phoneagent.core.ports.ScreenSnapshot?,
    ) = ScreenLaunched(id(call), LaunchTarget.App("com.example.chat"), outcome(after))

    @Test
    fun `the latest screen is the newest one a finished call showed the model`() {
        assertNull(latestSnapshot(log()))
        assertNull(latestSnapshot(log(ScreenObserved(id("c1"), s1))), "its call has no result yet")
        assertEquals(s1, latestSnapshot(log(ScreenObserved(id("c1"), s1), answered("c1"))))
        assertEquals(
            s1,
            latestSnapshot(log(ScreenObserved(id("c1"), s1), answered("c1"), ScreenObserved(null, s2), TurnStarted(2))),
            "a runtime observation is not shown to the model",
        )
        assertEquals(s2, latestSnapshot(log(acted("c1", s1), launched("c2", s2), answered("c1"), answered("c2"))))
        assertEquals(s1, latestSnapshot(log(acted("c1", s1), launched("c2", s2), answered("c1"))))
        assertEquals(s2, latestSnapshot(log(launched("c2", s2), acted("c1", s1), answered("c2"))))
        assertEquals(
            s3,
            latestSnapshot(
                log(
                    ScreenObserved(id("c0"), s3),
                    acted("c1", null),
                    launched("c2", null),
                    answered("c0"),
                    answered("c1"),
                    answered("c2"),
                ),
            ),
            "an action without a screen afterwards shows none",
        )
    }

    private fun verdict(
        call: String,
        kind: IntentKind,
        pkg: String?,
    ) = PolicyVerdictRecorded(id(call), SecurityLevel.STANDARD, ActionIntent(kind, pkg), Verdict.Allow)

    private fun card(
        reason: ConfirmReason,
        pkg: String?,
    ) = ConfirmCard(id("c9"), reason, "do", pkg = pkg)

    @Test
    fun `operated packages are those of successful non-READ calls by their last verdict`() {
        val records =
            log(
                start,
                verdict("tap", IntentKind.INPUT, "first"),
                verdict("tap", IntentKind.INPUT, "app.tap"),
                answered("tap"),
                verdict("look", IntentKind.READ, "app.look"),
                answered("look"),
                verdict("broken", IntentKind.INPUT, "app.broken"),
                answered("broken", ToolResult.failure(ToolStatus.ERROR, "no")),
                verdict("anywhere", IntentKind.NAVIGATE, null),
                answered("anywhere"),
                verdict("pending", IntentKind.INPUT, "app.pending"),
            )
        val context = policyContextOf(records, SessionSamples.task, screen = null, approved = null)
        assertEquals(setOf("app.tap"), context.operatedPackages)
    }

    @Test
    fun `outward-confirmed packages come from approved OUTWARD cards only`() {
        fun asked(
            n: Int,
            card: ConfirmCard,
        ) = ApprovalAsked(PromptId("p$n"), card, expiresAt = 0)

        fun decided(
            n: Int,
            result: ConfirmResult = ConfirmResult.APPROVED,
        ) = ApprovalDecided(PromptId("p$n"), result)
        val records =
            log(
                TaskStarted(SecurityLevel.STANDARD, unattended = true),
                asked(1, card(ConfirmReason.OUTWARD, "app.sent")),
                decided(1),
                asked(2, card(ConfirmReason.OUTWARD, "app.rejected")),
                decided(2, ConfirmResult.REJECTED),
                asked(3, card(ConfirmReason.SPEND, "app.paid")),
                decided(3),
                asked(4, card(ConfirmReason.OUTWARD, null)),
                decided(4),
                decided(5),
                asked(6, card(ConfirmReason.OUTWARD, "app.open")),
                TaskLevelChanged(SecurityLevel.AUTONOMOUS),
                TaskLevelChanged(SecurityLevel.CAUTIOUS),
            )
        val approved = card(ConfirmReason.OUTWARD, "app.sent")
        val context = policyContextOf(records, SessionSamples.task, s1, approved)
        assertEquals(setOf("app.sent"), context.outwardConfirmedPackages)
        assertEquals(SessionSamples.task, context.taskId)
        assertEquals(SecurityLevel.CAUTIOUS, context.level)
        assertEquals(true, context.unattended)
        assertEquals(s1, context.screen)
        assertEquals(approved, context.approved)
    }

    @Test
    fun `the level and attendance come from the log`() {
        assertEquals(SecurityLevel.CAUTIOUS, levelOf(log(TaskStarted(SecurityLevel.CAUTIOUS, unattended = false))))
        assertEquals(
            SecurityLevel.AUTONOMOUS,
            levelOf(log(start, TurnStarted(1), TaskLevelChanged(SecurityLevel.AUTONOMOUS), TurnStarted(2))),
        )
        assertEquals(true, settingsOf(log(TaskStarted(SecurityLevel.STANDARD, unattended = true))).unattended)
        val headless = assertThrows<IllegalStateException> { settingsOf(log(TurnStarted(1))) }
        assertEquals("a session log starts with task/start, not TurnStarted(turn=1)", headless.message)
    }

    @Test
    fun `a handoff hides earlier screens until the runtime records one for the call after it`() {
        val c1 = ToolCallId("c1")
        val c2 = ToolCallId("c2")
        val asked = PromptId("prompt-3")
        val other = PromptId("prompt-5")
        val before =
            log(
                TaskStarted(SecurityLevel.STANDARD, false),
                ScreenObserved(ToolCallId("c0"), snapshotOf("s1")),
                answered("c0"),
                HandoffStarted(asked, HandoffKind.LOGIN, "log in", c1),
                HandoffStarted(other, HandoffKind.LOGIN, "log in"),
                HandoffEnded(asked, HandoffResult.DONE),
            )
        assertNull(latestSnapshot(before + log(answered("c1"))), "s1 is hidden")
        assertEquals(true, screenLostAfterHandoff(before, c1))
        assertEquals(false, screenLostAfterHandoff(before, c2), "c2 did not hand off")

        val seen = before + log(ScreenObserved(c1, snapshotOf("s2")))
        assertEquals(false, screenLostAfterHandoff(seen, c1))
        assertEquals("s2", latestSnapshot(seen + log(answered("c1")))?.id?.value)
    }
}
