@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stop or a timeout that arrives while `tool/started` is written or flushed runs nothing: the
 * tool never executes after its call was given up (phase1 §3.7). The log keeps what it knows:
 * `tool/started` without `tool/executed`, so the result says the action may have taken effect.
 */
class AgentLoopStartFlushTest {
    private val texts = LoopTexts()

    /**
     * [tap] as c1; [onStart] runs once in the storage while its `tool/started` is synced
     * ([hookSync]) or appended.
     */
    private fun TestScope.harness(
        tap: FakeTool,
        hookSync: Boolean,
        onStart: suspend () -> Unit,
    ): LoopHarness {
        var armed = false
        val arm =
            Interceptor<ExecuteInput, ToolResult> { input, chain ->
                armed = true
                chain.proceed(input)
            }
        val harness =
            LoopHarness(
                this,
                FakeChatModel().calls(call("c1", "tap")).answers("好了"),
                tools = listOf(tap),
                interceptors = Interceptors(requestError = emptyList(), toolExecute = listOf(arm)),
            )
        val once: suspend () -> Unit = {
            if (armed) {
                armed = false
                onStart()
            }
        }
        if (hookSync) harness.storage.beforeSync = once else harness.storage.beforeAppend = once
        return harness
    }

    private suspend fun LoopHarness.resultText(): Pair<ToolStatus, String> {
        val result = events().only<ToolResultRecorded>().single().result
        return result.status to (result.content.single() as Part.Text).text
    }

    @Test
    fun `a stop while tool-started is flushed runs nothing`() =
        runTest {
            val tap = FakeTool("tap", RiskClass.WRITE)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness =
                harness(tap, hookSync = true) {
                    reached.complete(Unit)
                    release.await()
                }
            val loop = harness.start()
            loop.followup(text("点一下"))
            reached.await()
            loop.stop(StopCause.USER)
            release.complete(Unit)
            loop.awaitStopped()

            assertEquals(0, tap.calls.size, "the tool does not run after the stop")
            val events = harness.events()
            assertEquals(1, events.only<ToolStarted>().size)
            assertTrue(events.only<ToolExecuted>().isEmpty())
            assertEquals(
                ToolStatus.CANCELLED to "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}",
                harness.resultText(),
            )
        }

    @Test
    fun `a budget that runs out while tool-started is flushed runs nothing`() =
        runTest {
            var ranAt = -1L
            val tap =
                FakeTool("tap", RiskClass.WRITE, timeoutMs = 1_000) { _, _ ->
                    ranAt = testScheduler.currentTime
                    ToolResult.ok("点了")
                }
            val harness = harness(tap, hookSync = true) { delay(5_000) }
            val loop = harness.start()
            loop.followup(text("点一下"))
            loop.awaitPhase(Phase.Done)

            assertEquals(0, tap.calls.size, "the tool does not run after its budget ran out (ran at $ranAt)")
            assertEquals(ToolStatus.TIMEOUT to "${texts.timeout}${texts.mayHaveTakenEffect}", harness.resultText())
        }

    @Test
    fun `control - a stop while tool-started is appended runs nothing`() =
        runTest {
            val tap = FakeTool("tap", RiskClass.WRITE)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness =
                harness(tap, hookSync = false) {
                    reached.complete(Unit)
                    release.await()
                }
            val loop = harness.start()
            loop.followup(text("点一下"))
            reached.await()
            loop.stop(StopCause.USER)
            release.complete(Unit)
            loop.awaitStopped()

            assertEquals(0, tap.calls.size)
            assertEquals(1, harness.events().only<ToolStarted>().size)
        }
}
