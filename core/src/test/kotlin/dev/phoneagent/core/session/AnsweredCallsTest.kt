package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.StopLogs.Companion.call
import dev.phoneagent.core.session.StopLogs.Companion.goOn
import dev.phoneagent.core.session.StopLogs.Companion.nextTurn
import dev.phoneagent.core.session.StopLogs.Companion.replyWith
import dev.phoneagent.core.session.StopLogs.Companion.result
import dev.phoneagent.core.session.StopLogs.Companion.verdict
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The calls that need a result: every tool call of a complete `assistant/message`, dispatched or
 * not, and every dispatched call (`tool/call`). A stop, an unfinished stop and a resume leave none
 * of them without a `tool/result`, and a result written for a missing one follows the persisted
 * `tool/started`.
 */
class AnsweredCallsTest {
    private val logs = StopLogs()

    /** The first turn up to a complete reply asking for [ids] (seq 5). */
    private fun replied(vararg ids: String): List<SessionEvent> =
        listOf(TurnStarted(1), goOn, StepStarted(1, 1), replyWith(*ids))

    /** [id] dispatched, judged, started and executed (`tool/started` points at seq [verdictSeq]). */
    private fun ran(
        id: String,
        verdictSeq: Long,
    ): List<SessionEvent> =
        listOf(
            ToolCallRecorded(call(id)),
            verdict(id),
            ToolStarted(ToolCallId(id), verdictSeq),
            ToolExecuted(ToolCallId(id), ToolStatus.OK),
        )

    private val stopped = listOf(StepEnded(1, 1, StepOutcome.CANCELLED), TurnEnded(1, TurnEndReason.STOPPED))
    private val failed = listOf(StepEnded(1, 1, StepOutcome.FAILED), TurnEnded(1, TurnEndReason.FAILED))

    @Test
    fun `a call of a complete reply needs a result before anything closes, even when it was never dispatched`() =
        runTest {
            logs.assertRejectsLast("step/end", replied("c1") + StepEnded(1, 1, StepOutcome.CANCELLED))
            logs.assertRejectsLast(
                "turn/end, the reply outside a step",
                listOf(TurnStarted(1), goOn, replyWith("c1"), TurnEnded(1, TurnEndReason.STOPPED)),
            )
            logs.assertRejectsLast("task/stopped", listOf(replyWith("c1"), TaskStopped(StopCause.ERROR)))
            logs.assertRejectsLast("session/resumed", listOf(replyWith("c1"), SessionResumed(0)))
            logs.assertAccepts(
                "cancelled without a tool/call",
                replied("c1") + result("c1", ToolStatus.CANCELLED) + stopped + TaskStopped(StopCause.USER),
            )
        }

    @Test
    fun `every call of a reply needs its own result before the step ends, not just the one that ran`() =
        runTest {
            val firstRan = replied("c1", "c2") + ran("c1", 7) + result("c1", ToolStatus.OK)
            logs.assertRejectsLast("the second call unanswered", firstRan + StepEnded(1, 1, StepOutcome.COMPLETED))
            logs.assertAccepts(
                "the second call skipped",
                firstRan +
                    result(
                        "c2",
                        ToolStatus.SKIPPED,
                    ) + StepEnded(1, 1, StepOutcome.COMPLETED) + StepStarted(1, 2),
            )
            logs.assertAccepts(
                "the second call cancelled",
                firstRan + result("c2", ToolStatus.CANCELLED) + stopped + TaskStopped(StopCause.SHUTDOWN) +
                    SessionResumed(0),
            )
        }

    @Test
    fun `a dispatched call needs a result before its step ends, whether it started or not`() =
        runTest {
            val dispatched = replied("c1") + ToolCallRecorded(call("c1")) + verdict("c1")
            logs.assertRejectsLast("dispatched, not started", dispatched + StepEnded(1, 1, StepOutcome.CANCELLED))
            logs.assertRejectsLast(
                "started, no result",
                dispatched + ToolStarted(ToolCallId("c1"), 7) + StepEnded(1, 1, StepOutcome.CANCELLED),
            )
            logs.assertAccepts(
                "started and cancelled",
                dispatched + ToolStarted(ToolCallId("c1"), 7) + result("c1", ToolStatus.CANCELLED) + stopped +
                    TaskStopped(StopCause.USER),
            )
        }

    @Test
    fun `a dispatched call needs a result on its own, since the store ties no tool call to a reply`() =
        runTest {
            val dispatched = listOf(TurnStarted(1), goOn, StepStarted(1, 1), ToolCallRecorded(call("c1")))
            logs.assertRejectsLast("named by no recorded reply", dispatched + StepEnded(1, 1, StepOutcome.CANCELLED))
            logs.assertAccepts(
                "answered",
                dispatched + result("c1", ToolStatus.CANCELLED) + StepEnded(1, 1, StepOutcome.CANCELLED),
            )
        }

    @Test
    fun `a reply cut off at the output cap gets a skipped result for each call`() =
        runTest {
            val capped = replyWith("c1", "c2").copy(stopReason = StopReason.MAX_TOKENS)
            val cut = listOf(TurnStarted(1), goOn, StepStarted(1, 1), capped)
            logs.assertRejectsLast(
                "one call left",
                cut + result("c1", ToolStatus.SKIPPED) + StepEnded(1, 1, StepOutcome.COMPLETED),
            )
            val skipped = cut + result("c1", ToolStatus.SKIPPED) + result("c2", ToolStatus.SKIPPED)
            logs.assertAccepts(
                "both skipped, then the user's input",
                skipped + StepEnded(1, 1, StepOutcome.COMPLETED) + TurnEnded(1, TurnEndReason.MAX_TOKENS) + nextTurn,
            )
        }

    @Test
    fun `an interrupted reply has no calls to answer`() =
        runTest {
            val prefix =
                AssistantMessage(
                    1,
                    SessionSamples.provider,
                    SessionSamples.model,
                    listOf(Part.Text("我先")),
                    null,
                    interrupted = true,
                )
            logs.assertAccepts(
                "a stop after a prefix",
                listOf(TurnStarted(1), goOn, StepStarted(1, 1), prefix) + stopped + TaskStopped(StopCause.USER),
            )
        }

    @Test
    fun `a crash repair answers every call before it closes the step and resumes the log`() =
        runTest {
            logs.assertRejectsLast(
                "resumed in the open turn",
                replied("c1") + result("c1", ToolStatus.NOT_STARTED) + SessionResumed(0),
            )
            val crashed = logs.reopened(replied("c1", "c2") + ran("c1", 7)).store
            assertThrows<IllegalArgumentException>("nothing answered") { crashed.append(SessionResumed(0)) }
            assertThrows<IllegalArgumentException>("step closed first") { crashed.append(failed.first()) }
            crashed.append(result("c1", ToolStatus.OUTCOME_UNKNOWN))
            assertThrows<IllegalArgumentException>("one call answered") { crashed.append(failed.first()) }
            crashed.append(result("c2", ToolStatus.NOT_STARTED))
            failed.forEach { crashed.append(it) }
            crashed.append(SessionResumed(0))
            nextTurn.forEach { crashed.append(it) }
        }

    @Test
    fun `a result written for a missing one follows the persisted start`() =
        runTest {
            val started =
                replied("c1") + ToolCallRecorded(call("c1")) + verdict("c1") + ToolStarted(ToolCallId("c1"), 7)
            logs.assertRejectsLast("not started after a start", started + result("c1", ToolStatus.NOT_STARTED))
            val dispatched = replied("c1") + ToolCallRecorded(call("c1")) + verdict("c1")
            logs.assertRejectsLast(
                "outcome unknown without a start",
                dispatched + result("c1", ToolStatus.OUTCOME_UNKNOWN),
            )
            logs.assertRejectsLast(
                "outcome unknown, never dispatched",
                replied("c1") + result("c1", ToolStatus.OUTCOME_UNKNOWN),
            )
            logs.assertAccepts("outcome unknown after a start", started + result("c1", ToolStatus.OUTCOME_UNKNOWN))
            logs.assertAccepts("not started, dispatched", dispatched + result("c1", ToolStatus.NOT_STARTED))
            logs.assertAccepts("not started, never dispatched", replied("c1") + result("c1", ToolStatus.NOT_STARTED))
        }
}
