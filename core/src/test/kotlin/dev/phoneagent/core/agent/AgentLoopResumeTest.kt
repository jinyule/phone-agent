@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * No stop is final (phase1 §3.7, D14): after any stop the task continues on a new runtime, which
 * writes `session/resumed` first and then waits for the user's own input, never on its own.
 */
class AgentLoopResumeTest {
    /** A first run, stopped for [cause] while its request was in flight. */
    private suspend fun TestScope.stoppedRun(cause: StopCause): LoopHarness {
        val first = LoopHarness(this, FakeChatModel().hangs(delivered = "正在"))
        val loop = first.start()
        loop.followup(text("go"))
        first.model.awaitStarted(1)
        loop.stop(cause)
        assertEquals(Phase.Stopped(cause), loop.awaitStopped())
        assertEquals(listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(cause)), first.events().takeLast(2))
        return first
    }

    /** The user messages of turn [turn] in [events], and whether its first step came after all of them. */
    private fun turnInputs(
        events: List<SessionEvent>,
        turn: Int,
    ): Pair<List<UserMessage>, Boolean> {
        val inTurn = events.dropWhile { it != TurnStarted(turn) }.takeWhile { it !is TurnEnded }
        val beforeStep = inTurn.takeWhile { it !is StepStarted }
        return beforeStep.only<UserMessage>() to (inTurn.only<UserMessage>() == beforeStep.only<UserMessage>())
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(StopCause::class)
    fun `after a stop the rebuilt process resumes, waits, and goes on only with the user's input`(cause: StopCause) =
        runTest {
            val first = stoppedRun(cause)

            val model = FakeChatModel().answers("好了")
            val second = LoopHarness(this, model, storage = first.storage)
            val resumed = second.resume()
            resumed.inject(ContextInput("timer", listOf(Part.Text("到点了"))))
            advanceUntilIdle()
            assertEquals(Phase.Idle, resumed.phase.value)
            assertEquals(0, model.started.value, "nothing continues on its own, nor on injected context")
            val events = second.events()
            assertEquals(TaskStopped(cause), events[events.indexOfFirst { it is SessionResumed } - 1])

            resumed.followup(text("继续"))
            resumed.awaitPhase(Phase.Done)
            val (inputs, stepAfterInputs) = turnInputs(second.events(), turn = 2)
            assertEquals(
                listOf(
                    UserMessage(UserMessageKind.INJECTED, listOf(Part.Text("到点了")), "timer"),
                    UserMessage(UserMessageKind.USER, listOf(Part.Text("继续"))),
                ),
                inputs,
            )
            assertEquals(true, stepAfterInputs, "the step starts after the user's input")
            assertEquals(1, model.started.value)
        }

    @Test
    fun `a stopped runtime takes no input, and a new runtime in the same process continues the task`() =
        runTest {
            val model = FakeChatModel().hangs(delivered = "正在").answers("好了")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            model.awaitStarted(1)
            loop.stop(StopCause.USER)
            loop.awaitStopped()
            val logged = harness.events().size
            loop.followup(text("ignored"))
            advanceUntilIdle()
            assertEquals(logged, harness.events().size, "the stopped runtime appends nothing more")

            val next = harness.resume()
            assertEquals(SessionResumed(0), harness.events().last())
            next.followup(text("继续"))
            next.awaitPhase(Phase.Done)
            val (inputs, _) = turnInputs(harness.events(), turn = 2)
            assertEquals(listOf(UserMessage(UserMessageKind.USER, listOf(Part.Text("继续")))), inputs)
        }

    @Test
    fun `an unfinished stop is resumed with task-stopped ERROR, then waits for the user`() =
        runTest {
            val first = stoppedRun(StopCause.USER)
            // The process ended between turn/end(STOPPED) and task/stopped.
            val log = first.files.log.value
            val bytes = first.storage.files.getValue(log)
            val lastLineStart = bytes.dropLast(1).indexOfLast { it == '\n'.code.toByte() } + 1
            first.storage.files[log] = bytes.copyOf(lastLineStart)
            assertEquals(TurnEnded(1, TurnEndReason.STOPPED), first.events().last())

            val model = FakeChatModel().answers("好了")
            val second = LoopHarness(this, model, storage = first.storage)
            val resumed = second.resume()
            advanceUntilIdle()
            assertEquals(
                listOf(TurnEnded(1, TurnEndReason.STOPPED), TaskStopped(StopCause.ERROR), SessionResumed(0)),
                second.events().takeLast(3),
                "the stop's cause was never written, so it is not guessed",
            )
            assertEquals(Phase.Idle, resumed.phase.value)
            assertEquals(0, model.started.value, "nothing continues on its own")

            resumed.followup(text("继续"))
            resumed.awaitPhase(Phase.Done)
            assertEquals(TurnEnded(2, TurnEndReason.COMPLETED), second.events().last())
        }
}
