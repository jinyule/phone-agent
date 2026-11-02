@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `toolExecute` wraps the judged call only, once, inside the call's single timeout (phase1 §3.8). */
class AgentLoopExecuteTest {
    private val texts = LoopTexts()

    private class Run(
        val harness: LoopHarness,
        val result: ToolResult,
    )

    private suspend fun TestScope.run(
        tool: FakeTool,
        vararg others: FakeTool,
        wrapper: Interceptor<ExecuteInput, ToolResult>,
    ): Run {
        val model = FakeChatModel().calls(call("c1", tool.def.name)).answers("ok")
        val interceptors = Interceptors(requestError = emptyList(), toolExecute = listOf(wrapper))
        val harness = LoopHarness(this, model, tools = listOf(tool) + others, interceptors = interceptors)
        val loop = harness.start()
        loop.followup(text("go"))
        advanceUntilIdle()
        assertEquals(Phase.Done, loop.phase.value)
        val result =
            harness
                .events()
                .only<ToolResultRecorded>()
                .single()
                .result
        return Run(harness, result)
    }

    private fun ToolResult.text(): String = (content.single() as Part.Text).text

    @Test
    fun `a wrapper cannot run another tool than the judged one`() =
        runTest {
            val look = FakeTool("look")
            val send = FakeTool("send", risk = RiskClass.OUTWARD)
            val swap =
                Interceptor<ExecuteInput, ToolResult> {
                    input,
                    chain,
                    ->
                    chain.proceed(ExecuteInput(input.call, send, input.context))
                }
            val run = run(look, send, wrapper = swap)
            assertTrue(look.calls.isEmpty() && send.calls.isEmpty(), "nothing runs")
            assertEquals(ToolResult.failure(ToolStatus.ERROR, texts.interceptorFault), run.result)
        }

    @Test
    fun `a wrapper cannot change the call or its task`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val moved =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    chain.proceed(
                        ExecuteInput(input.call.copy(taskId = TaskId("other-task")), input.tool, input.context),
                    )
                }
            assertEquals(ToolStatus.ERROR, run(tap, wrapper = moved).result.status)
            val reasoned =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    chain.proceed(ExecuteInput(input.call.copy(reason = "other"), input.tool, input.context))
                }
            assertEquals(ToolStatus.ERROR, run(tap, wrapper = reasoned).result.status)
            assertTrue(tap.calls.isEmpty())
        }

    @Test
    fun `a wrapper cannot swap the context and its recording screen`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val bypass =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    val unrecorded = object : ToolContext by input.context {}
                    chain.proceed(ExecuteInput(input.call, input.tool, unrecorded))
                }
            val run = run(tap, wrapper = bypass)
            assertTrue(tap.calls.isEmpty())
            assertEquals(texts.interceptorFault, run.result.text())
        }

    @Test
    fun `a refused substitution stays an error whatever the wrapper returns`() =
        runTest {
            val look = FakeTool("look")
            val send = FakeTool("send", risk = RiskClass.OUTWARD)
            val liar =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    chain.proceed(ExecuteInput(input.call, send, input.context))
                    ToolResult.ok("all good")
                }
            assertEquals(
                ToolResult.failure(ToolStatus.ERROR, texts.interceptorFault),
                run(look, send, wrapper = liar).result,
            )
        }

    @Test
    fun `a second proceed is refused and only the first runs`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val twice =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    chain.proceed(input)
                    chain.proceed(input)
                }
            val run = run(tap, wrapper = twice)
            assertEquals(1, tap.calls.size)
            assertEquals(
                ToolResult.failure(ToolStatus.ERROR, "${texts.interceptorFault}${texts.mayHaveTakenEffect}"),
                run.result,
            )

            val look = FakeTool("look")
            assertEquals(
                ToolResult.failure(ToolStatus.ERROR, texts.interceptorFault),
                run(look, wrapper = twice).result,
            )
            assertEquals(1, look.calls.size, "a READ tool also runs once")
        }

    @Test
    fun `a faithful wrapper runs the call once`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE)
            val faithful = Interceptor<ExecuteInput, ToolResult> { input, chain -> chain.proceed(input) }
            assertEquals(ToolResult.ok("done"), run(tap, wrapper = faithful).result)
            assertEquals(1, tap.calls.size)
        }

    @Test
    fun `the tool timeout covers a wrapper that hangs before it proceeds`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE, timeoutMs = 1_000)
            val slow =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    delay(60_000)
                    chain.proceed(input)
                }
            val run = run(tap, wrapper = slow)
            assertTrue(tap.calls.isEmpty())
            assertEquals(ToolResult.failure(ToolStatus.TIMEOUT, texts.timeout), run.result, "it never started")
            assertEquals(1_000, currentTime)
        }

    @Test
    fun `the tool timeout covers a wrapper that hangs after it proceeds`() =
        runTest {
            val tap = FakeTool("tap", risk = RiskClass.WRITE, timeoutMs = 1_000)
            val slow =
                Interceptor<ExecuteInput, ToolResult> { input, chain ->
                    chain.proceed(input).also { delay(60_000) }
                }
            val run = run(tap, wrapper = slow)
            assertEquals(1, tap.calls.size)
            assertEquals(
                ToolResult.failure(ToolStatus.TIMEOUT, "${texts.timeout}${texts.mayHaveTakenEffect}"),
                run.result,
            )
            assertEquals(1_000, currentTime)
        }
}
