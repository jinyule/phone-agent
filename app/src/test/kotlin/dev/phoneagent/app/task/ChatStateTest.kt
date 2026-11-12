package dev.phoneagent.app.task

import dev.phoneagent.core.agent.LoopEvent
import dev.phoneagent.core.agent.LoopTexts
import dev.phoneagent.core.agent.Phase
import dev.phoneagent.core.agent.WaitReason
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding a task's records into what the chat shows. */
class ChatStateTest {
    private val prompt = PromptId("prompt-1")

    private fun state(vararg events: SessionEvent): ChatState =
        ChatState.of(records(*events), Phase.Idle, PRODUCT_CARDS)

    private fun ChatState.step(id: String): ChatEntry.Step =
        entries.filterIsInstance<ChatEntry.Step>().single {
            it.callId ==
                ToolCallId(id)
        }

    private fun result(
        status: ToolStatus,
        text: String = "result",
        screen: String? = null,
    ): ToolResult =
        ToolResult(
            status,
            listOf(Part.Text(text)),
            screen?.let {
                buildJsonObject { put(ToolResult.SCREEN, it) }
            },
        )

    private fun reply(text: String): AssistantMessage =
        AssistantMessage(1, ProviderId("p"), ModelId("m"), listOf(Part.Text(text)), StopReason.END_TURN)

    @Test
    fun theConversationIsTheUsersCommandsTheModelsTextsAndOneStepPerCall() {
        val observe = call("call-1", "phone_observe")
        val chat =
            state(
                TaskStarted(SecurityLevel.STANDARD, unattended = false),
                TurnStarted(1),
                command("  open settings "),
                UserMessage(UserMessageKind.RUNTIME_CONTEXT, listOf(Part.Text("foreground: launcher"))),
                reply("Opening it."),
                reply("   "),
                ToolCallRecorded(observe),
                ToolResultRecorded(observe.id, observe.name, result(ToolStatus.OK, screen = "s1")),
                TurnEnded(1, TurnEndReason.COMPLETED),
            )

        assertEquals(
            listOf(
                ChatEntry.UserText("open settings"),
                ChatEntry.AssistantText("Opening it."),
                chat.step("call-1"),
            ),
            chat.entries,
        )
        assertEquals(1, chat.commands)
        assertFalse(chat.openTurn)
        assertEquals(9L, chat.lastSeq)
    }

    @Test
    fun aStepsCardIsTheToolsOwnFromTheCallThenFromItsResult() {
        val tap = call("call-1", "phone_tap", "screen" to "s1", "ref" to "e3")
        val running = state(ToolCallRecorded(tap))
        assertEquals(
            PRODUCT_TOOLS.tools.single { it.def.name == "phone_tap" }.presentCall(tap),
            running.step("call-1").card,
        )
        assertEquals(StepState.RUNNING, running.step("call-1").card?.state)

        val cancelled =
            running.recorded(
                SessionRecord(2, 0, ToolResultRecorded(tap.id, tap.name, result(ToolStatus.CANCELLED))),
            )
        // A tap may have reached the phone: the card says so, never that it failed.
        assertEquals(StepState.UNKNOWN, cancelled.step("call-1").card?.state)
        assertEquals(ToolStatus.CANCELLED, cancelled.step("call-1").status)

        val observe = call("call-2", "phone_observe")
        val read =
            state(ToolCallRecorded(observe), ToolResultRecorded(observe.id, observe.name, result(ToolStatus.CANCELLED)))
        assertEquals(StepState.FAILED, read.step("call-2").card?.state)
    }

    @Test
    fun aToolTheTaskDoesNotHaveHasNoCard() {
        val odd = call("call-1", "not_a_tool")
        val chat = state(ToolCallRecorded(odd), ToolResultRecorded(odd.id, odd.name, result(ToolStatus.ERROR)))
        assertNull(chat.step("call-1").card)
        assertEquals(ToolStatus.ERROR, chat.step("call-1").status)
    }

    @Test
    fun aRecordAlreadyFoldedInChangesNothing() {
        val log = records(TaskStarted(SecurityLevel.STANDARD, unattended = false), TurnStarted(1), command("go"))
        val once = ChatState.of(log, Phase.Idle)
        assertSame(once, once.recorded(log[2]))
        assertSame(once, once.recorded(log[0]))
        assertEquals(1, once.commands)

        assertTrue(once.follows(SessionRecord(4, 0, reply("x"))))
        assertTrue(once.follows(log[1]))
        assertFalse(once.follows(SessionRecord(5, 0, reply("x"))))

        val read = once.through(7)
        assertEquals(7L, read.lastSeq)
        assertSame(read, read.through(6))
        assertTrue(read.follows(SessionRecord(8, 0, reply("x"))))
        assertEquals(once, ChatState.of(log + log, Phase.Idle))
    }

    @Test
    fun cardsAndStreamingDoNotChangeTheState() {
        val chat = state(TurnStarted(1))
        assertSame(chat, chat.on(LoopEvent.Card(ToolCallId("call-9"), StepCard("phone_tap", "x"))))
        val record = SessionRecord(2, 0, command("go"))
        assertEquals(chat.recorded(record), chat.on(LoopEvent.Recorded(record)))
    }

    @Test
    fun promptsAreOpenFromTheirQuestionToTheirAnswer() {
        val card = ConfirmCard(ToolCallId("call-1"), ConfirmReason.OUTWARD, "send")
        val asked = state(ApprovalAsked(prompt, card, expiresAt = 100))
        assertEquals(mapOf(prompt to PendingPrompt.Confirm(prompt, card)), asked.prompts)
        assertNull(asked.waitingFor)
        assertEquals(
            PendingPrompt.Confirm(prompt, card),
            asked.phase(Phase.Waiting(WaitReason.USER, prompt)).waitingFor,
        )
        assertNull(asked.phase(Phase.Waiting(WaitReason.LOCK, null)).waitingFor)
        assertTrue(
            state(ApprovalAsked(prompt, card, 100), ApprovalDecided(prompt, ConfirmResult.APPROVED)).prompts.isEmpty(),
        )

        val question = Question("which?", listOf("a", "b"))
        val ask = QuestionAsked(prompt, ToolCallId("call-1"), question)
        assertEquals(PendingPrompt.Ask(prompt, question), state(ask).prompts[prompt])
        assertTrue(state(ask, QuestionAnswered(prompt, AskResult.Answered("a"))).prompts.isEmpty())
        assertTrue(state(ask, PromptWithdrawn(prompt)).prompts.isEmpty())

        val handoff = HandoffStarted(prompt, HandoffKind.LOGIN, "log in please", ToolCallId("call-1"))
        assertEquals(PendingPrompt.Handoff(prompt, HandoffKind.LOGIN, "log in please"), state(handoff).prompts[prompt])
        assertTrue(state(handoff, HandoffEnded(prompt, HandoffResult.DONE)).prompts.isEmpty())
    }

    @Test
    fun theTaskIsBusyWhileATurnIsOpenOrTheRunRunsOrWaits() {
        val open = state(TurnStarted(1))
        assertTrue(open.openTurn)
        assertTrue(open.busy)
        assertFalse(open.stoppable)

        val ended = state(TurnStarted(1), TurnEnded(1, TurnEndReason.COMPLETED))
        assertFalse(ended.busy)
        assertTrue(ended.phase(Phase.Running(1, 1)).stoppable)
        assertTrue(ended.phase(Phase.Running(1, 1)).busy)
        assertTrue(ended.phase(Phase.Waiting(WaitReason.HANDOFF, prompt)).busy)
        assertFalse(ended.phase(Phase.Done).busy)
        assertFalse(ended.phase(Phase.Stopped(StopCause.USER)).busy)

        val stopped = state(TurnStarted(1), TaskStopped(StopCause.SHUTDOWN))
        assertFalse(stopped.openTurn)
        assertEquals(ChatEntry.Stopped(StopCause.SHUTDOWN), stopped.entries.last())
    }

    @Test
    fun theCardShowsTheWithheldNoteForTheScreenItsResultNames() {
        val observe = call("call-1", "phone_observe")

        fun judged(
            snapshot: String,
            outcome: JudgmentOutcome,
        ) = ScreenJudged(
            observe.id,
            SnapshotId(snapshot),
            JudgmentPurpose.TOOL,
            outcome,
            rule = if (outcome == JudgmentOutcome.WITHHELD) "refused_app" else null,
        )
        val done = ToolResultRecorded(observe.id, observe.name, result(ToolStatus.OK, screen = "s2"))

        assertTrue(
            state(ToolCallRecorded(observe), judged("s2", JudgmentOutcome.WITHHELD), done).step("call-1").showsWithheld,
        )
        assertFalse(
            state(ToolCallRecorded(observe), judged("s2", JudgmentOutcome.SHOWN), done).step("call-1").showsWithheld,
        )
        assertFalse(
            state(ToolCallRecorded(observe), judged("s1", JudgmentOutcome.WITHHELD), done).step("call-1").showsWithheld,
        )
        // Without a screen in the result, any withheld screen of the call counts.
        assertTrue(
            state(ToolCallRecorded(observe), judged("s1", JudgmentOutcome.WITHHELD)).step("call-1").showsWithheld,
        )
        assertFalse(state(ToolCallRecorded(observe)).step("call-1").showsWithheld)
    }

    @Test
    fun anActionsWithheldNoteFollowsTheScreenAfterItNotTheOneItWasPlannedOn() {
        val tap = call("call-1", "phone_tap", "screen" to "s1", "ref" to "e3")

        fun withheld(snapshot: String) =
            ScreenJudged(tap.id, SnapshotId(snapshot), JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, "refused_app")
        val done = ToolResultRecorded(tap.id, tap.name, result(ToolStatus.OK, screen = "s2"))
        val card = state(ToolCallRecorded(tap), done).step("call-1").card
        assertEquals(listOf("s1", "s2"), card?.details?.filter { it.key == ToolResult.SCREEN }?.map { it.value })

        assertTrue(state(ToolCallRecorded(tap), withheld("s2"), done).step("call-1").showsWithheld)
        assertFalse(state(ToolCallRecorded(tap), withheld("s1"), done).step("call-1").showsWithheld)
    }

    private val handToUser = call("call-1", "hand_to_user", "kind" to "login")
    private val handedOver = HandoffStarted(prompt, HandoffKind.LOGIN, "log in", handToUser.id)
    private val lost = ScreenUnavailableException::class.java.name
    private val unknown = LoopTexts().screenUnknown

    private fun placeAfter(vararg events: SessionEvent): AppPlace? =
        state(ToolCallRecorded(handToUser), *events).step("call-1").place

    private fun handToUserResult(
        status: ToolStatus,
        text: String = "result",
    ) = ToolResultRecorded(handToUser.id, handToUser.name, result(status, text))

    @Test
    fun aHandOffStoppedMidwayLeavesTheAppWithTheUser() {
        assertEquals(AppPlace.LEFT_WITH_USER, placeAfter(handedOver, handToUserResult(ToolStatus.CANCELLED)))
        // Not handed over yet: nothing moved.
        assertNull(placeAfter(handToUserResult(ToolStatus.CANCELLED)))
    }

    @Test
    fun aScreenLostDuringTheHandOffMayLeaveTheAppWithTheUser() {
        val both = "$lost: display gone. $unknown"
        assertEquals(AppPlace.MAYBE_WITH_USER, placeAfter(handedOver, handToUserResult(ToolStatus.ERROR, both)))
        assertEquals(AppPlace.MAYBE_WITH_USER, placeAfter(handedOver, handToUserResult(ToolStatus.TIMEOUT, both)))
        // Each signal alone says nothing.
        assertNull(placeAfter(handedOver, handToUserResult(ToolStatus.ERROR, "$lost: display gone.")))
        assertNull(placeAfter(handedOver, handToUserResult(ToolStatus.ERROR, unknown)))
        assertNull(placeAfter(handedOver, handToUserResult(ToolStatus.OK, both)))
        assertNull(placeAfter(handedOver, handToUserResult(ToolStatus.REFUSED, both)))
        assertNull(placeAfter(handToUserResult(ToolStatus.ERROR, both)))
    }

    @Test
    fun aHandOffWhoseAppWasNotTakenBackLeavesItWithTheUser() {
        // handoff/end's contract: NOT_TAKEN_BACK names the take-back, FAILED the hand-over.
        val notBack = HandoffEnded(prompt, HandoffResult.NOT_TAKEN_BACK, "take back failed: no surface")
        assertEquals(AppPlace.WITH_USER, placeAfter(handedOver, notBack))
        assertEquals(
            AppPlace.WITH_USER,
            placeAfter(handedOver, notBack, handToUserResult(ToolStatus.ERROR, "not taken back")),
        )
        assertEquals(AppPlace.WITH_USER, placeAfter(handedOver, HandoffEnded(prompt, HandoffResult.NOT_TAKEN_BACK)))
        assertNull(placeAfter(handedOver, HandoffEnded(prompt, HandoffResult.FAILED, "hand over failed: busy")))
        assertNull(placeAfter(handedOver, HandoffEnded(prompt, HandoffResult.FAILED)))
        assertNull(placeAfter(handedOver, HandoffEnded(prompt, HandoffResult.DONE), handToUserResult(ToolStatus.OK)))
        // A hand-off that names no call marks none.
        assertNull(
            placeAfter(
                HandoffStarted(prompt, HandoffKind.LOGIN, "log in"),
                HandoffEnded(prompt, HandoffResult.NOT_TAKEN_BACK, "take back failed: x"),
            ),
        )
    }

    /** A call the policy handed off: its result is the runtime's (wp09-notes: the signals are hand_to_user's). */
    @Test
    fun onlyAHandToUserResultSaysWhereTheAppIs() {
        val tap = call("call-2", "phone_tap", "screen" to "s1", "ref" to "e3")
        val other = HandoffStarted(prompt, HandoffKind.LOGIN, "log in", tap.id)
        val chat =
            state(ToolCallRecorded(tap), other, ToolResultRecorded(tap.id, tap.name, result(ToolStatus.CANCELLED)))
        assertNull(chat.step("call-2").place)
        // handoff/end speaks for whichever call the hand-off was for.
        val notBack = state(ToolCallRecorded(tap), other, HandoffEnded(prompt, HandoffResult.NOT_TAKEN_BACK))
        assertEquals(AppPlace.WITH_USER, notBack.step("call-2").place)
    }
}
