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
import dev.phoneagent.core.session.PolicyContextSamples
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolStarted
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
        call: String,
        reason: ConfirmReason,
        pkg: String?,
    ) = ConfirmCard(id(call), reason, "do", pkg = pkg)

    @Test
    fun `the package sets follow the execution facts of every sample`() {
        val wrong =
            PolicyContextSamples.cases.mapNotNull { case ->
                val context = policyContextOf(case.records, SessionSamples.task, screen = null, approved = null)
                val derived = context.operatedPackages to context.outwardConfirmedPackages
                if (derived ==
                    case.operatedPackages to case.outwardConfirmedPackages
                ) {
                    null
                } else {
                    "${case.name}: $derived"
                }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    /** A log under construction for one call at a time; [add] returns the seq it gave. */
    private class Log {
        val events = mutableListOf<SessionEvent>(TaskStarted(SecurityLevel.STANDARD, unattended = true))
        private var prompts = 0

        fun add(event: SessionEvent): Long {
            events += event
            return events.size.toLong()
        }

        fun approve(
            card: ConfirmCard,
            result: ConfirmResult = ConfirmResult.APPROVED,
        ) {
            val prompt = PromptId("p${++prompts}")
            add(ApprovalAsked(prompt, card, expiresAt = 0))
            add(ApprovalDecided(prompt, result))
        }

        /** [call] starts on an Allow for [kind] in [pkg] and returns [status]. */
        fun ran(
            call: String,
            kind: IntentKind,
            pkg: String?,
            status: ToolStatus? = ToolStatus.OK,
        ) {
            val allowed =
                add(
                    PolicyVerdictRecorded(
                        ToolCallId(call),
                        SecurityLevel.STANDARD,
                        ActionIntent(kind, pkg),
                        Verdict.Allow,
                    ),
                )
            add(ToolStarted(ToolCallId(call), allowed))
            status?.let { add(ToolExecuted(ToolCallId(call), it)) }
        }
    }

    @Test
    fun `operated packages are those of started non-READ calls by the verdict they started on`() {
        val log = Log()
        log.add(verdict("refused", IntentKind.INPUT, "app.refused"))
        log.ran("tap", IntentKind.INPUT, "app.tap", ToolStatus.ERROR)
        log.ran("look", IntentKind.READ, "app.look")
        log.ran("anywhere", IntentKind.NAVIGATE, null)
        log.ran("hung", IntentKind.INPUT, "app.hung", status = null)
        val context =
            policyContextOf(log(*log.events.toTypedArray()), SessionSamples.task, screen = null, approved = null)
        assertEquals(setOf("app.tap", "app.hung"), context.operatedPackages)
        assertEquals(emptySet<String>(), context.outwardConfirmedPackages, "no approval, no qualification")
    }

    @Test
    fun `outward qualification comes from the approval a sent call ran on`() {
        val log = Log()
        // c1: approved for A, re-confirmed and approved for B, sent in B.
        log.approve(card("c1", ConfirmReason.OUTWARD, "app.a"))
        log.approve(card("c1", ConfirmReason.OUTWARD, "app.b"))
        log.ran("c1", IntentKind.OUTWARD, "app.b")
        // c2: an OUTWARD approval replaced by a SPEND one.
        log.approve(card("c2", ConfirmReason.OUTWARD, "app.c"))
        log.approve(card("c2", ConfirmReason.SPEND, "app.c"))
        log.ran("c2", IntentKind.OUTWARD, "app.c")
        // c3: approved for D, ran in E; c4: approved without a package; c5: rejected, then an
        // answer to a prompt never asked; c6: approved and started, not executed.
        log.approve(card("c3", ConfirmReason.OUTWARD, "app.d"))
        log.ran("c3", IntentKind.OUTWARD, "app.e")
        log.approve(card("c4", ConfirmReason.OUTWARD, null))
        log.ran("c4", IntentKind.OUTWARD, null)
        log.approve(card("c5", ConfirmReason.OUTWARD, "app.f"), ConfirmResult.REJECTED)
        log.add(ApprovalDecided(PromptId("never-asked"), ConfirmResult.APPROVED))
        log.ran("c5", IntentKind.OUTWARD, "app.f")
        log.approve(card("c6", ConfirmReason.OUTWARD, "app.g"))
        log.ran("c6", IntentKind.OUTWARD, "app.g", status = null)
        // c8: an approval after the verdict it ran on is not the one it ran on.
        log.ran("c8", IntentKind.OUTWARD, "app.i")
        log.approve(card("c8", ConfirmReason.OUTWARD, "app.i"))
        val approved = card("c7", ConfirmReason.OUTWARD, "app.h")
        val records = log(*log.events.toTypedArray(), TaskLevelChanged(SecurityLevel.CAUTIOUS))
        val context = policyContextOf(records, SessionSamples.task, s1, approved)

        assertEquals(setOf("app.b"), context.outwardConfirmedPackages)
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

    @Test
    fun `a handoff that never ended hides earlier screens from its start`() {
        val c1 = ToolCallId("c1")
        val asked = PromptId("prompt-3")
        val cut =
            log(
                TaskStarted(SecurityLevel.STANDARD, false),
                ScreenObserved(ToolCallId("c0"), snapshotOf("s1")),
                answered("c0"),
                HandoffStarted(asked, HandoffKind.LOGIN, "log in", c1),
            )
        assertNull(latestSnapshot(cut + log(answered("c1"))), "stopped, timed out or broken: s1 is hidden")
        assertEquals(true, screenLostAfterHandoff(cut, c1))

        val later = cut + log(answered("c1"), ScreenObserved(ToolCallId("c2"), snapshotOf("s3")), answered("c2"))
        assertEquals("s3", latestSnapshot(later)?.id?.value, "a screen recorded after it counts again")
        assertEquals(true, screenLostAfterHandoff(later, c1), "c2's screen is not c1's")
        assertEquals(false, screenLostAfterHandoff(cut + log(ScreenObserved(c1, snapshotOf("s2"))), c1))

        val during = cut + log(ScreenObserved(ToolCallId("c2"), snapshotOf("s3")), answered("c2"))
        assertEquals("s3", latestSnapshot(during)?.id?.value)
        assertNull(
            latestSnapshot(during + log(HandoffEnded(asked, HandoffResult.DONE))),
            "handoff/end hides the screens recorded while handed over too",
        )
    }
}
