@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * "May have taken effect" ([LoopTexts.mayHaveTakenEffect]) only follows a call that started on a
 * non-READ verdict (phase1 §3.7): the intent of the verdict at `tool/started`'s `verdictSeq`, not
 * the tool's declared risk. A READ call that was stopped, timed out or cut off by a failure keeps
 * its status (CANCELLED, TIMEOUT, OUTCOME_UNKNOWN; it did start) without the suffix.
 */
class AgentLoopReadOutcomeTest {
    private val texts = LoopTexts()

    /** c1 calls [tool], judged as [kind] whatever its risk class. */
    private fun TestScope.harness(
        tool: FakeTool,
        kind: IntentKind,
        interceptors: Interceptors = Interceptors(requestError = emptyList()),
    ): LoopHarness =
        LoopHarness(
            this,
            FakeChatModel().calls(call("c1", tool.def.name)).answers("好了"),
            tools = listOf(tool),
            intents = IntentResolver { _, _, seen -> ActionIntent(kind, seen?.pkg) },
            interceptors = interceptors,
        )

    private suspend fun LoopHarness.outcome(): Pair<ToolStatus, String> =
        events()
            .only<ToolResultRecorded>()
            .single()
            .result
            .let { it.status to it.text() }

    @Test
    fun `a READ call stopped while it runs is CANCELLED without may-have-taken-effect`() =
        runTest {
            for ((kind, expected) in listOf(
                IntentKind.READ to texts.stoppedWhileRunning,
                IntentKind.INPUT to "${texts.stoppedWhileRunning}${texts.mayHaveTakenEffect}",
            )) {
                val running = CompletableDeferred<Unit>()
                val hung =
                    FakeTool("look", RiskClass.READ) { _, _ ->
                        running.complete(Unit)
                        awaitCancellation()
                    }
                val harness = harness(hung, kind)
                val loop = harness.start()
                loop.followup(text("看一下"))
                running.await()
                loop.stop(StopCause.USER)
                loop.awaitStopped()

                assertEquals(ToolStatus.CANCELLED to expected, harness.outcome(), "judged $kind")
            }
        }

    @Test
    fun `a READ call whose step failed after it ran is OUTCOME_UNKNOWN without may-have-taken-effect`() =
        runTest {
            for ((kind, expected) in listOf(
                IntentKind.READ to texts.outcomeUnknown,
                IntentKind.INPUT to "${texts.outcomeUnknown}${texts.mayHaveTakenEffect}",
            )) {
                val crash = Interceptor<PostExecuteInput, PostExecuteResult> { _, _ -> error("post bug") }
                val interceptors = Interceptors(requestError = emptyList(), toolPostExecute = listOf(crash))
                val harness = harness(FakeTool("look", RiskClass.READ), kind, interceptors)
                harness.start().followup(text("看一下"))
                advanceUntilIdle()

                val (status, said) = harness.outcome()
                val acting = kind != IntentKind.READ
                assertEquals(acting, texts.mayHaveTakenEffect in said, "judged $kind: $said")
                assertEquals(ToolStatus.OUTCOME_UNKNOWN to expected, status to said, "judged $kind")
            }
        }

    @Test
    fun `a timed-out call may have taken effect by its verdict, not by the tool's declared risk`() =
        runTest {
            for ((kind, expected) in listOf(
                IntentKind.READ to texts.timeout,
                IntentKind.NAVIGATE to "${texts.timeout}${texts.mayHaveTakenEffect}",
            )) {
                val slow = FakeTool("look", RiskClass.READ, timeoutMs = 1_000) { _, _ -> awaitCancellation() }
                val harness = harness(slow, kind)
                val loop = harness.start()
                loop.followup(text("看一下"))
                loop.awaitPhase(Phase.Done)

                assertEquals(ToolStatus.TIMEOUT to expected, harness.outcome(), "judged $kind")
            }
        }
}
