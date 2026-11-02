@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.c1
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `tool/executed` is written when `Tool.execute` returns, before `toolPostExecute` (phase1 §3.7):
 * a post-execute extension already sees it, and one that fails cannot take it away.
 */
class AgentLoopExecutedFirstTest {
    private val executed = ToolExecuted(c1, ToolStatus.OK)

    /** tap (WRITE) as c1, then an answer; [post] runs after it. */
    private fun TestScope.harness(post: Interceptor<PostExecuteInput, PostExecuteResult>): LoopHarness =
        LoopHarness(
            this,
            FakeChatModel().calls(call("c1", "tap")).answers("好了"),
            tools = listOf(FakeTool("tap", RiskClass.WRITE)),
            interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(post)),
        )

    @Test
    fun `a post-execute extension already sees the tool's own status in the log`() =
        runTest {
            lateinit var harness: LoopHarness
            var seen: List<SessionEvent> = emptyList()
            harness =
                harness { input, chain ->
                    seen = harness.events()
                    chain.proceed(input)
                }
            val loop = harness.start()
            loop.followup(text("点一下"))
            loop.awaitPhase(Phase.Done)

            assertEquals(executed, seen.last(), "tool/executed is the last record before toolPostExecute")
        }

    @Test
    fun `a post-execute extension that fails leaves the tool's own status recorded`() =
        runTest {
            val harness = harness { _, _ -> error("post-execute bug") }
            val loop = harness.start()
            loop.followup(text("点一下"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(listOf(executed), events.only<ToolExecuted>())
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)
        }
}
