@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.session.PolicyRulesRecorded
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFlushException
import dev.phoneagent.core.session.SessionResumed
import dev.phoneagent.core.session.SessionWriteException
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskStopped
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The rules a run judges with (`policy/rules`) open every run: right after `task/start`, flushed
 * before the task takes input, and right after every `session/resumed`, where a new build may
 * bring new rules. A setup without rules records none.
 */
class AgentLoopRunRulesTest {
    private val first = rules(version = 7, labelMax = 12)
    private val updated = rules(version = 8, labelMax = 16)

    @Test
    fun `a new task records its rules right after task-start, flushed before any input`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel().answers("好了"), rules = first)
            val loop = harness.start()

            assertEquals(listOf<SessionEvent>(LoopHarness.ATTENDED, first), harness.events())
            assertEquals(1, harness.storage.syncCount, "flushed with the start")
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            assertEquals(1, harness.events().count { it is PolicyRulesRecorded }, "once per run")
        }

    @Test
    fun `every resumed run records the rules it judges with right after session-resumed`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel().answers("好了").answers("又好了"), rules = first)
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            val after = LoopHarness(this, harness.model, storage = harness.storage, rules = updated)
            val next = after.resume()
            assertEquals(
                listOf(TaskStopped(StopCause.USER), SessionResumed(0), updated),
                after.events().takeLast(3),
            )
            next.followup(text("继续"))
            next.awaitPhase(Phase.Done)
            assertEquals(listOf(first, updated), after.events().filterIsInstance<PolicyRulesRecorded>())
        }

    @Test
    fun `a setup without rules records none`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel().answers("好了"))
            val loop = harness.start()
            assertEquals(listOf<SessionEvent>(LoopHarness.ATTENDED), harness.events())
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            harness.resume()
            assertEquals(SessionResumed(0), harness.events().last())
            assertEquals(0, harness.events().count { it is PolicyRulesRecorded })
        }

    @Test
    fun `a start whose rules cannot be written throws and closes the log`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel(), rules = first)
            harness.storage.appendFailures = 1

            assertThrows<SessionWriteException> { harness.start() }
            assertEquals(0, harness.storage.openAppenders, "the log is closed")
            assertEquals(listOf<SessionEvent>(LoopHarness.ATTENDED), harness.events())
        }

    @Test
    fun `a start whose rules cannot be flushed throws and closes the log`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel(), rules = first)
            harness.storage.syncFailures = 1

            assertThrows<SessionFlushException> { harness.start() }
            assertEquals(0, harness.storage.openAppenders, "the log is closed")
        }

    private fun rules(
        version: Int,
        labelMax: Int,
    ): PolicyRulesRecorded = PolicyRulesRecorded(version, buildJsonObject { put("labelMax", labelMax) })
}
