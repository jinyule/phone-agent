@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.session.InboxEntry
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.InboxQueue
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.TaskLevelChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Input accepted before a stop is recorded, then cleared, before the log closes (phase1 §3.7). */
class AgentLoopShutdownTest {
    private fun added(value: String): InboxSpliced =
        InboxSpliced(
            InboxQueue.NEXT_TURN,
            removed = 0,
            added = listOf(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text(value)))),
        )

    private fun cleared(count: Int): InboxSpliced = InboxSpliced(InboxQueue.NEXT_TURN, removed = count)

    private class Held(
        val harness: LoopHarness,
        val loop: AgentLoop,
        val gate: CompletableDeferred<Unit>,
    )

    /** An idle loop whose next log write waits for [Held.gate]. */
    private suspend fun TestScope.heldAtNextWrite(): Held {
        val harness = LoopHarness(this)
        val gate = CompletableDeferred<Unit>()
        val loop = harness.start()
        advanceUntilIdle()
        harness.storage.beforeAppend = {
            harness.storage.beforeAppend = {}
            gate.await()
        }
        return Held(harness, loop, gate)
    }

    @Test
    fun `input accepted before the scope is cancelled is recorded, then cleared`() =
        runTest {
            val harness = LoopHarness(this)
            val loop = harness.start()
            loop.followup(text("a"))
            loop.changeLevel(SecurityLevel.CAUTIOUS)
            harness.loopScope.cancel()
            runCurrent()

            assertEquals(Phase.Stopped(StopCause.SHUTDOWN), loop.phase.value)
            assertEquals(
                listOf(LoopHarness.ATTENDED, added("a"), TaskLevelChanged(SecurityLevel.CAUTIOUS), cleared(1)),
                harness.events(),
            )
            assertEquals(emptyList<Throwable>(), harness.uncaught)
        }

    @Test
    fun `a record cut by the scope's cancellation completes and the rest is recorded after it`() =
        runTest {
            val held = heldAtNextWrite()
            val harness = held.harness
            val loop = held.loop
            loop.followup(text("a"))
            loop.followup(text("b"))
            runCurrent()
            harness.loopScope.cancel()
            runCurrent()
            held.gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(Phase.Stopped(StopCause.SHUTDOWN), loop.phase.value)
            assertEquals(listOf(added("a"), added("b"), cleared(2)), harness.events().only<InboxSpliced>())
            assertEquals(emptyList<Throwable>(), harness.uncaught)
        }

    @Test
    fun `a stop waits for the record in progress, then records and clears the rest before closing`() =
        runTest {
            val held = heldAtNextWrite()
            val harness = held.harness
            val loop = held.loop
            loop.followup(text("a"))
            loop.followup(text("b"))
            runCurrent()
            loop.stop(StopCause.USER)
            runCurrent()
            held.gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(Phase.Stopped(StopCause.USER), loop.phase.value)
            assertEquals(listOf(added("a"), added("b"), cleared(2)), harness.events().only<InboxSpliced>())
            assertEquals(cleared(2), harness.events().last(), "nothing is written after the inbox is cleared")
            assertEquals(emptyList<Throwable>(), harness.uncaught)
        }
}
