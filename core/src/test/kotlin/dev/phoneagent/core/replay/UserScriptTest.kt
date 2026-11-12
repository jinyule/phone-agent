package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.UserReply
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UserScriptTest {
    private val call = ToolCallId("call-1")
    private val followup = InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("去发消息")))
    private val card = ConfirmCard(call, ConfirmReason.OUTWARD, "发送")

    private fun log(vararg events: SessionEvent): List<SessionRecord> =
        events.mapIndexed { index, event -> SessionRecord(index + 1L, 0, event) }

    private fun script(vararg events: SessionEvent) = UserScript.of(log(*events))

    private val started = TaskStarted(SecurityLevel.STANDARD, unattended = false)

    @Test
    fun `an input is given right after the record before it`() {
        assertEquals(
            listOf(Stimulus.Input(followup, after = 1)),
            script(started, InboxSpliced(InboxQueue.NEXT_TURN, removed = 0, added = listOf(followup))),
        )
    }

    @Test
    fun `an inbox splice that only takes entries is no input`() {
        assertEquals(emptyList<Stimulus>(), script(started, InboxSpliced(InboxQueue.NEXT_TURN, removed = 1)))
    }

    @Test
    fun `a splice of two inputs cannot be given as one and fails`() {
        assertThrows<IllegalArgumentException> {
            script(started, InboxSpliced(InboxQueue.NEXT_TURN, removed = 0, added = listOf(followup, followup)))
        }
    }

    @Test
    fun `a level change is given where it was recorded`() {
        assertEquals(
            listOf(Stimulus.Level(SecurityLevel.CAUTIOUS, after = 1)),
            script(started, TaskLevelChanged(SecurityLevel.CAUTIOUS)),
        )
    }

    @Test
    fun `answers name their prompt by its number in the session`() {
        val first = PromptId("p-a")
        val second = PromptId("p-b")
        val third = PromptId("p-c")
        assertEquals(
            listOf(
                Stimulus.Answer(2, UserReply.Answer("十点")),
                Stimulus.Answer(3, UserReply.Reject),
            ),
            script(
                started,
                ApprovalAsked(first, card, expiresAt = 0),
                ApprovalDecided(first, ConfirmResult.EXPIRED),
                QuestionAsked(second, call, Question("几点？")),
                QuestionAnswered(second, AskResult.Answered("十点")),
                ApprovalAsked(third, card, expiresAt = 0),
                ApprovalDecided(third, ConfirmResult.REJECTED),
            ),
        )
    }

    @Test
    fun `an approval approved is an Approve`() {
        val prompt = PromptId("p")
        assertEquals(
            listOf(Stimulus.Answer(1, UserReply.Approve)),
            script(started, ApprovalAsked(prompt, card, 0), ApprovalDecided(prompt, ConfirmResult.APPROVED)),
        )
    }

    @Test
    fun `an unavailable approval and a withdrawn prompt need no answer`() {
        val prompt = PromptId("p")
        val other = PromptId("q")
        assertEquals(
            emptyList<Stimulus>(),
            script(
                started,
                ApprovalAsked(prompt, card, 0),
                ApprovalDecided(prompt, ConfirmResult.UNAVAILABLE),
                ApprovalAsked(other, card, 0),
                PromptWithdrawn(other),
            ),
        )
    }

    @Test
    fun `a declined question is a Decline, an expired or unavailable one nothing`() {
        val (a, b, c) = listOf(PromptId("a"), PromptId("b"), PromptId("c"))
        assertEquals(
            listOf(Stimulus.Answer(1, UserReply.Decline)),
            script(
                started,
                QuestionAsked(a, call, Question("?")),
                QuestionAnswered(a, AskResult.Declined),
                QuestionAsked(b, call, Question("?")),
                QuestionAnswered(b, AskResult.Expired),
                QuestionAsked(c, call, Question("?")),
                QuestionAnswered(c, AskResult.Unavailable),
            ),
        )
    }

    @Test
    fun `a hand-off done or declined is the user's, expired or unavailable the runtime's`() {
        val ids = listOf("a", "b", "c", "d").map(::PromptId)
        val results =
            listOf(HandoffResult.DONE, HandoffResult.DECLINED, HandoffResult.EXPIRED, HandoffResult.UNAVAILABLE)
        val events =
            ids.zip(results).flatMap { (id, result) ->
                listOf(HandoffStarted(id, HandoffKind.LOGIN, "登录"), HandoffEnded(id, result))
            }
        assertEquals(
            listOf(Stimulus.Answer(1, UserReply.HandoffDone), Stimulus.Answer(2, UserReply.Decline)),
            script(started, *events.toTypedArray()),
        )
    }

    private fun failedMove(result: HandoffResult): String {
        val prompt = PromptId("p")
        return assertThrows<IllegalArgumentException> {
            script(
                started,
                HandoffStarted(prompt, HandoffKind.LOGIN, "登录"),
                HandoffEnded(prompt, result, "move failed: x"),
            )
        }.message.orEmpty()
    }

    @Test
    fun `a hand-off that could not be handed over needs a SurfaceManager and fails the load`() {
        val message = failedMove(HandoffResult.FAILED)
        assertTrue("SurfaceManager" in message && "FAILED" in message, message)
    }

    @Test
    fun `a hand-off that could not be taken back needs a SurfaceManager and fails the load`() {
        val message = failedMove(HandoffResult.NOT_TAKEN_BACK)
        assertTrue("SurfaceManager" in message && "NOT_TAKEN_BACK" in message, message)
    }

    @Test
    fun `an answer to a prompt never asked fails`() {
        assertThrows<IllegalArgumentException> {
            script(
                started,
                ApprovalDecided(PromptId("p"), ConfirmResult.APPROVED),
            )
        }
    }

    @Test
    fun `a stop when the turn was done is given after the turn's end`() {
        val stop =
            script(
                started,
                TurnStarted(1),
                TurnEnded(1, TurnEndReason.COMPLETED),
                TaskStopped(StopCause.USER),
            )
        assertEquals(listOf(Stimulus.Stop(StopCause.USER, after = 3)), stop)
    }

    @Test
    fun `a stop is given before everything the stop itself wrote`() {
        val cut =
            ActionOutcome(ActionOutcome.Status.Failed(DeviceScript.UNFINISHED), ActionOutcome.Effect.UNKNOWN, null)
        val stimuli =
            script(
                started,
                TurnStarted(1),
                InboxSpliced(InboxQueue.NEXT_STEP, removed = 1),
                ScreenActed(call, null, UiAction.Tap(Target.Ref(ElementRef("e1"))), cut),
                ToolResultRecorded(call, "phone_tap", ToolResult.failure(ToolStatus.CANCELLED, "stopped")),
                StepEnded(1, 1, StepOutcome.CANCELLED),
                TurnEnded(1, TurnEndReason.STOPPED),
                TaskStopped(StopCause.SHUTDOWN),
            )
        assertEquals(listOf(Stimulus.Shutdown(after = 2)), stimuli)
    }

    @Test
    fun `a stop of the limit is the app's, one of an error the runtime's`() {
        assertEquals(listOf(Stimulus.Stop(StopCause.LIMIT, after = 1)), script(started, TaskStopped(StopCause.LIMIT)))
        assertEquals(emptyList<Stimulus>(), script(started, TaskStopped(StopCause.ERROR)))
    }

    @Test
    fun `a resume right after a stop is given there`() {
        assertEquals(
            listOf(Stimulus.Stop(StopCause.USER, after = 1), Stimulus.Resume(after = 2)),
            script(started, TaskStopped(StopCause.USER), SessionResumed(0)),
        )
    }

    @Test
    fun `a resume after no stop is a crash and fails`() {
        val message = assertThrows<IllegalArgumentException> { script(started, SessionResumed(0)) }.message.orEmpty()
        assertTrue("crash" in message, message)
    }
}
