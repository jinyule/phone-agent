package dev.phoneagent.core.session

import dev.phoneagent.core.session.StopLogs.Companion.goOn
import dev.phoneagent.core.session.StopLogs.Companion.resumed
import dev.phoneagent.core.session.StopLogs.Companion.stoppedRun
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Turns and steps pair up: one turn is open at a time and each turn has a number above every
 * earlier one; one step is open at a time, in the open turn, numbered upward within it; `step/end`
 * ends the open step and `turn/end` the open turn once its step has ended. The rules hold where
 * records are written and where they are read.
 */
class TurnPairingTest {
    private val logs = StopLogs()

    private val turn = listOf(TurnStarted(1), goOn)
    private val stepDone = turn + StepStarted(1, 1) + StepEnded(1, 1, StepOutcome.COMPLETED)

    @Test
    fun `a turn starts with no turn open and a number above every earlier turn's`() =
        runTest {
            logs.assertRejectsLast("in the open turn", turn + TurnStarted(2))
            logs.assertRejectsLast(
                "twice while waiting",
                stoppedRun(StopCause.USER) + resumed + TurnStarted(2) + TurnStarted(3),
            )
            val ended = turn + TurnEnded(1, TurnEndReason.COMPLETED)
            logs.assertRejectsLast("the same number again", ended + TurnStarted(1))
            logs.assertRejectsLast(
                "a lower number",
                listOf(TurnStarted(3), goOn, TurnEnded(3, TurnEndReason.COMPLETED), TurnStarted(2)),
            )
            logs.assertRejectsLast("the stopped turn's number", stoppedRun(StopCause.USER) + resumed + TurnStarted(1))
            logs.assertAccepts("a higher number", ended + TurnStarted(3) + goOn + StepStarted(3, 1))
        }

    @Test
    fun `a turn ends when it is the open one and its step has ended`() =
        runTest {
            logs.assertRejectsLast("no turn open", listOf(TurnEnded(1, TurnEndReason.COMPLETED)))
            logs.assertRejectsLast("another turn", turn + TurnEnded(2, TurnEndReason.COMPLETED))
            logs.assertRejectsLast(
                "twice",
                turn + TurnEnded(1, TurnEndReason.COMPLETED) + TurnEnded(1, TurnEndReason.COMPLETED),
            )
            logs.assertRejectsLast(
                "no turn open while waiting",
                stoppedRun(StopCause.USER) + resumed + TurnEnded(1, TurnEndReason.STOPPED),
            )
            logs.assertRejectsLast(
                "its step still open",
                turn + StepStarted(1, 1) + TurnEnded(1, TurnEndReason.COMPLETED),
            )
            logs.assertAccepts(
                "a turn without steps, and one whose steps ended",
                turn + TurnEnded(1, TurnEndReason.PRE_STEP_REFUSED) + TurnStarted(2) + goOn + StepStarted(2, 1) +
                    StepEnded(2, 1, StepOutcome.COMPLETED) + TurnEnded(2, TurnEndReason.COMPLETED),
            )
        }

    @Test
    fun `a step starts in the open turn, with no step open and a number above the turn's earlier steps`() =
        runTest {
            logs.assertRejectsLast("no turn open", listOf(StepStarted(1, 1)))
            logs.assertRejectsLast("another turn", turn + StepStarted(2, 1))
            logs.assertRejectsLast("in the open step", turn + StepStarted(1, 1) + StepStarted(1, 2))
            logs.assertRejectsLast("the same number again", stepDone + StepStarted(1, 1))
            logs.assertRejectsLast(
                "a lower number",
                turn + StepStarted(1, 2) + StepEnded(1, 2, StepOutcome.COMPLETED) + StepStarted(1, 1),
            )
            logs.assertRejectsLast(
                "after its turn ended",
                stepDone + TurnEnded(1, TurnEndReason.COMPLETED) + StepStarted(1, 2),
            )
            logs.assertAccepts(
                "the next step, then the first step of the next turn",
                stepDone + StepStarted(1, 3) + StepEnded(1, 3, StepOutcome.COMPLETED) +
                    TurnEnded(1, TurnEndReason.COMPLETED) + TurnStarted(2) + goOn + StepStarted(2, 1),
            )
        }

    @Test
    fun `a step ends when it is the open one`() =
        runTest {
            logs.assertRejectsLast("no turn open", listOf(StepEnded(1, 1, StepOutcome.COMPLETED)))
            logs.assertRejectsLast("no step open", turn + StepEnded(1, 1, StepOutcome.COMPLETED))
            logs.assertRejectsLast("another step", turn + StepStarted(1, 1) + StepEnded(1, 2, StepOutcome.COMPLETED))
            logs.assertRejectsLast(
                "the same step in another turn",
                turn + StepStarted(1, 1) + StepEnded(2, 1, StepOutcome.COMPLETED),
            )
            logs.assertRejectsLast("twice", stepDone + StepEnded(1, 1, StepOutcome.COMPLETED))
        }

    @Test
    fun `a step does not cross turns`() =
        runTest {
            val open = turn + StepStarted(1, 1)
            logs.assertRejectsLast("its turn ends first", open + TurnEnded(1, TurnEndReason.COMPLETED))
            logs.assertRejectsLast("the next turn starts in it", open + TurnStarted(2))
            logs.assertRejectsLast(
                "it ends in the next turn",
                turn + TurnEnded(1, TurnEndReason.COMPLETED) + TurnStarted(2) + goOn +
                    StepEnded(1, 1, StepOutcome.COMPLETED),
            )
        }

    @Test
    fun `the logs that kept a turn open are refused where the pairing breaks`() =
        runTest {
            val nested =
                listOf(
                    TurnStarted(1),
                    goOn,
                    TurnStarted(2),
                    TurnEnded(2, TurnEndReason.COMPLETED),
                    TaskStopped(StopCause.USER),
                )
            val endWithoutStart =
                listOf(TurnEnded(7, TurnEndReason.COMPLETED), TaskStopped(StopCause.USER), SessionResumed(0))
            val waited = stoppedRun(StopCause.USER) + resumed
            val startedTwice = waited + TurnStarted(2) + TurnStarted(3) + goOn + StepStarted(3, 1)
            listOf(
                Triple("a nested turn, then a stop", nested, 2),
                Triple("turn/end without turn/start", endWithoutStart, 0),
                Triple("turn/start twice while waiting", startedTwice, waited.size + 1),
            ).forEach { (name, events, breaks) ->
                assertEquals(breaks + 3, logs.corruptLine(events), "$name: line")
                logs.assertRejectsLast(name, events.take(breaks + 1))
            }
        }
}
