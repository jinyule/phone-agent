package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.LogRecords.answered
import dev.phoneagent.core.agent.LogRecords.dispatched
import dev.phoneagent.core.agent.LogRecords.log
import dev.phoneagent.core.agent.LogRecords.reply
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.tool.ToolStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [repairOf]: what closes an abnormal end (phase1 §3.7, D14). */
class SessionRepairTest {
    private val texts = LoopTexts()

    private fun repair(
        cause: RepairCause,
        vararg events: SessionEvent,
    ): List<Repair> = repairOf(log(*events), cause, "why", texts)

    private fun prompt(n: Int) = PromptId("prompt-$n")

    @Test
    fun `a finished log needs no repair`() {
        val closed =
            arrayOf(
                TurnStarted(1),
                StepStarted(1, 1),
                reply("c1"),
                dispatched("c1"),
                answered("c1"),
                StepEnded(1, 1, StepOutcome.COMPLETED),
                TurnEnded(1, TurnEndReason.COMPLETED),
            )
        assertEquals(emptyList<Repair>(), repair(RepairCause.FAILED, *closed))
        assertEquals(emptyList<Repair>(), repair(RepairCause.STOPPED))
    }

    @Test
    fun `open prompts are withdrawn and closed ones are left alone`() {
        val card =
            SessionSamples.events
                .filterIsInstance<ApprovalAsked>()
                .single()
                .card
        val repairs =
            repair(
                RepairCause.STOPPED,
                ApprovalAsked(prompt(1), card, expiresAt = 1),
                ApprovalDecided(prompt(1), ConfirmResult.APPROVED),
                QuestionAsked(prompt(2), ToolCallId("c1"), Question("?")),
                QuestionAnswered(prompt(2), AskResult.Declined),
                HandoffStarted(prompt(3), HandoffKind.LOGIN, "log in"),
                HandoffEnded(prompt(3), HandoffResult.DONE),
                ApprovalAsked(prompt(4), card, expiresAt = 1),
                PromptWithdrawn(prompt(4)),
                QuestionAsked(prompt(5), ToolCallId("c1"), Question("?")),
                HandoffStarted(prompt(6), HandoffKind.LOGIN, "log in"),
            )
        assertEquals(listOf(PromptWithdrawn(prompt(5)), PromptWithdrawn(prompt(6))), repairs.map { it.event })
    }

    @Test
    fun `missing results say whether the call may have run, by its tool-started alone`() {
        val events =
            arrayOf(
                TurnStarted(1),
                StepStarted(1, 1),
                reply("ran", "queued", "never", "done"),
                dispatched("ran"),
                ToolStarted(ToolCallId("ran"), verdictSeq = 4),
                dispatched("queued"),
                dispatched("done"),
                answered("done"),
            )
        val stopped = repair(RepairCause.STOPPED, *events)
        val results = stopped.mapNotNull { it.event as? ToolResultRecorded }
        assertEquals(listOf("ran", "queued", "never"), results.map { it.callId.value })
        assertEquals(
            listOf(ToolStatus.CANCELLED, ToolStatus.CANCELLED, ToolStatus.CANCELLED),
            results.map { it.result.status },
        )
        assertEquals(
            listOf(
                "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}",
                texts.stoppedBeforeRun,
                texts.stoppedBeforeRun,
            ),
            results.map {
                it.result.content.single().let { part ->
                    (part as dev.phoneagent.core.model.Part.Text).text
                }
            },
        )
        assertEquals(
            listOf(listOf(4L), listOf(6L), listOf(3L)),
            stopped.take(3).map { it.sources },
            "call record, else reply",
        )
        assertEquals(
            listOf(StepEnded(1, 1, StepOutcome.CANCELLED), TurnEnded(1, TurnEndReason.STOPPED, "why")),
            stopped.drop(3).map { it.event },
        )

        val failed = repair(RepairCause.FAILED, *events)
        assertEquals(
            listOf(ToolStatus.OUTCOME_UNKNOWN, ToolStatus.NOT_STARTED, ToolStatus.NOT_STARTED),
            failed.mapNotNull { (it.event as? ToolResultRecorded)?.result?.status },
        )
        assertEquals(
            listOf(StepEnded(1, 1, StepOutcome.FAILED), TurnEnded(1, TurnEndReason.FAILED, "why")),
            failed.drop(3).map { it.event },
        )
    }

    @Test
    fun `only the last step and turn count as open`() {
        val between =
            repair(
                RepairCause.FAILED,
                TurnStarted(1),
                StepStarted(1, 1),
                StepEnded(1, 1, StepOutcome.COMPLETED),
                TurnEnded(1, TurnEndReason.COMPLETED),
                TurnStarted(2),
            )
        assertEquals(listOf(TurnEnded(2, TurnEndReason.FAILED, "why")), between.map { it.event })
        assertEquals(TurnStarted(2), openTurn(log(TurnStarted(1), TurnStarted(2))))
        assertEquals(
            StepStarted(1, 2),
            openStep(log(StepStarted(1, 1), StepEnded(1, 1, StepOutcome.COMPLETED), StepStarted(1, 2))),
        )
    }
}
