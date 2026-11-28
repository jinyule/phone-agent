package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.StopLogs.Companion.call
import dev.phoneagent.core.session.StopLogs.Companion.goOn
import dev.phoneagent.core.session.StopLogs.Companion.replyWith
import dev.phoneagent.core.session.StopLogs.Companion.result
import dev.phoneagent.core.session.StopLogs.Companion.verdict
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Recovery writes what `SessionStore.open` lists, in that order: a result for each call that needs
 * one (NOT_STARTED without a `tool/started`, OUTCOME_UNKNOWN with one), `step/end` for an open
 * step, `turn/end` for an open turn, `task/stopped(ERROR)` after an unfinished stop, then
 * `session/resumed`. Wherever the process ended, the reopened store accepts that recovery and
 * then the user's input in a new turn.
 */
class RecoveryOrderTest {
    private val logs = StopLogs()

    /** One run through calls that ran, were skipped and were stopped, a stop, a resume and a second turn. */
    private val run: List<SessionEvent> =
        buildList {
            addAll(listOf(TurnStarted(1), goOn, StepStarted(1, 1), replyWith("c1", "c2")))
            dispatch("c1")
            add(ToolExecuted(ToolCallId("c1"), ToolStatus.OK))
            addAll(listOf(result("c1", ToolStatus.OK), result("c2", ToolStatus.SKIPPED)))
            addAll(listOf(StepEnded(1, 1, StepOutcome.COMPLETED), StepStarted(1, 2), replyWith("c3")))
            dispatch("c3")
            addAll(listOf(result("c3", ToolStatus.CANCELLED), StepEnded(1, 2, StepOutcome.CANCELLED)))
            addAll(listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(StopCause.USER), SessionResumed(0)))
            addAll(listOf(TurnStarted(2), goOn, StepStarted(2, 1), done))
            addAll(listOf(StepEnded(2, 1, StepOutcome.COMPLETED), TurnEnded(2, TurnEndReason.COMPLETED)))
        }

    @Test
    fun `a run cut after any record recovers in the documented order, then takes the user's input`() =
        runTest {
            (0..run.size).forEach { cut ->
                val written = run.take(cut)
                val store = logs.reopened(written).store
                (recovery(written) + nextTurn(written)).forEach { event ->
                    assertDoesNotThrow("cut after $cut records: $event") { store.append(event) }
                }
            }
        }

    @Test
    fun `closing the open step before answering its calls is refused`() =
        runTest {
            val cuts =
                (0..run.size).filter { cut ->
                    val repair = recovery(run.take(cut))
                    repair.any { it is ToolResultRecorded } && repair.any { it is StepEnded }
                }
            assertTrue(cuts.isNotEmpty(), "the run has a cut with an open step and unanswered calls")
            cuts.forEach { cut ->
                val store = logs.reopened(run.take(cut)).store
                val stepEnd = recovery(run.take(cut)).first { it is StepEnded }
                assertThrows<IllegalArgumentException>("cut after $cut records") { store.append(stepEnd) }
            }
        }

    @Test
    fun `the recovery answers by the persisted start and closes what is open`() {
        val cutInStep = run.indexOfFirst { it is ToolExecuted }
        assertEquals(
            listOf(
                result("c1", ToolStatus.OUTCOME_UNKNOWN),
                result("c2", ToolStatus.NOT_STARTED),
                StepEnded(1, 1, StepOutcome.FAILED),
                TurnEnded(1, TurnEndReason.FAILED),
                SessionResumed(0),
            ),
            recovery(run.take(cutInStep)),
        )
        val unfinishedStop = run.indexOfFirst { it is TaskStopped }
        assertEquals(listOf(TaskStopped(StopCause.ERROR), SessionResumed(0)), recovery(run.take(unfinishedStop)))
    }

    private fun MutableList<SessionEvent>.dispatch(id: String) {
        add(ToolCallRecorded(call(id)))
        val verdictSeq = size + 2L
        add(verdict(id))
        add(ToolStarted(ToolCallId(id), verdictSeq))
    }

    private companion object {
        val done =
            AssistantMessage(
                1,
                SessionSamples.provider,
                SessionSamples.model,
                listOf(Part.Text("好了")),
                StopReason.END_TURN,
            )

        /** The recovery `SessionStore.open` lists, for a log of `task/start` and [events]. */
        fun recovery(events: List<SessionEvent>): List<SessionEvent> {
            val needed =
                events.flatMap { event ->
                    when {
                        event is AssistantMessage -> event.parts.filterIsInstance<Part.ToolCall>().map { it.id }
                        event is ToolCallRecorded -> listOf(event.call.id)
                        else -> emptyList()
                    }
                }
            val answered = events.filterIsInstance<ToolResultRecorded>().map { it.callId }.toSet()
            val started = events.filterIsInstance<ToolStarted>().map { it.callId }.toSet()
            val results =
                needed.distinct().filterNot { it in answered }.map { id ->
                    result(id.value, if (id in started) ToolStatus.OUTCOME_UNKNOWN else ToolStatus.NOT_STARTED)
                }
            val step = openStep(events)?.let { StepEnded(it.turn, it.step, StepOutcome.FAILED) }
            val turn = openTurn(events)?.let { TurnEnded(it.turn, TurnEndReason.FAILED) }
            val unfinishedStop = (events.lastOrNull() as? TurnEnded)?.reason == TurnEndReason.STOPPED
            val stop = TaskStopped(StopCause.ERROR).takeIf { unfinishedStop }
            return results + listOfNotNull(step, turn, stop) + SessionResumed(0)
        }

        /** The user's input opening the turn after every turn in [events], and its first step. */
        fun nextTurn(events: List<SessionEvent>): List<SessionEvent> {
            val turn = (events.filterIsInstance<TurnStarted>().maxOfOrNull { it.turn } ?: 0) + 1
            return listOf(TurnStarted(turn), goOn, StepStarted(turn, 1))
        }

        fun openStep(events: List<SessionEvent>): StepStarted? =
            events.fold(null as StepStarted?) { open, event ->
                when {
                    event is StepStarted -> event
                    event is StepEnded -> null
                    else -> open
                }
            }

        fun openTurn(events: List<SessionEvent>): TurnStarted? =
            events.fold(null as TurnStarted?) { open, event ->
                when {
                    event is TurnStarted -> event
                    event is TurnEnded -> null
                    else -> open
                }
            }
    }
}
