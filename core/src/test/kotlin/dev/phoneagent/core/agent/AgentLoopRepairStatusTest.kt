@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * NOT_STARTED and OUTCOME_UNKNOWN are written by the repair only, and `tool/executed` refuses them.
 * A tool that returns one has a bug: its `tool/executed` cannot be written, so the turn fails
 * (`failed with java.lang.IllegalArgumentException`) and the repair answers the call, which
 * started, OUTCOME_UNKNOWN. The tool ran once; the next input starts a new turn as usual.
 */
class AgentLoopRepairStatusTest {
    private val texts = LoopTexts()

    @ParameterizedTest(name = "{0}")
    @EnumSource(ToolStatus::class, names = ["NOT_STARTED", "OUTCOME_UNKNOWN"])
    fun `a tool returning a repair-only status fails the turn, and its call is answered OUTCOME_UNKNOWN`(
        status: ToolStatus,
    ) = runTest {
        val buggy = FakeTool("pay", RiskClass.WRITE) { _, _ -> ToolResult.failure(status, "工具自己写的") }
        val model = FakeChatModel().calls(call("c1", "pay"))
        val harness = LoopHarness(this, model, tools = listOf(buggy))
        val loop = harness.start()
        loop.followup(text("付款"))
        advanceUntilIdle()

        val events = harness.events()
        assertEquals(1, buggy.calls.size, "the tool ran once")
        assertEquals(1, events.only<ToolStarted>().size)
        assertTrue(events.only<ToolExecuted>().isEmpty(), "no tool/executed with a repair-only status")
        val result = events.only<ToolResultRecorded>().single().result
        assertEquals(ToolStatus.OUTCOME_UNKNOWN, result.status)
        assertEquals(texts.outcomeUnknown, (result.content.single() as Part.Text).text)
        assertEquals(StepOutcome.FAILED, events.only<StepEnded>().single().outcome)
        val ended = events.only<TurnEnded>().single()
        assertEquals(TurnEndReason.FAILED, ended.reason)
        assertEquals("failed with java.lang.IllegalArgumentException", ended.detail)
        assertEquals(Phase.Idle, loop.phase.value)

        model.answers("先看一下屏幕")
        loop.followup(text("继续"))
        loop.awaitPhase(Phase.Done)
        assertEquals(
            TurnEndReason.COMPLETED,
            harness
                .events()
                .only<TurnEnded>()
                .last()
                .reason,
        )
    }
}
