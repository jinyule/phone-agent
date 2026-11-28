package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cancellation is never turned into a tool result (AGENTS §4): a port or the user interaction
 * throwing [CancellationException] ends the call with it, and cancelling `phone_wait` while it
 * waits ends it at once, with no result and no further reads.
 */
class CancellationTest {
    private val tools = PhaseOneTools.create(ScreenFormatter(lexicon = Screens.LEXICON)).associateBy { it.def.name }

    /** [name] running in a child of the test; [Run.result] is set only if `execute` returned. */
    private fun TestScope.start(
        name: String,
        args: String,
        ctx: ToolContext,
    ): Run {
        val run = Run()
        run.job = launch { run.result = tools.getValue(name).execute(call(name, args), ctx) }
        return run
    }

    private class Run {
        lateinit var job: Job
        var result: ToolResult? = null
    }

    private suspend fun assertCancelled(run: Run) {
        run.job.join()
        assertTrue(run.job.isCancelled)
        assertNull(run.result, "a cancelled call must not return a result")
    }

    /** Throws [CancellationException] from every prompt, as when the task is stopped while the user sees it. */
    private class CancelledInteraction : UserInteraction {
        override suspend fun ask(question: Question): AskResult = throw CancellationException("task stopped")

        override suspend fun confirm(card: ConfirmCard): ConfirmResult = throw CancellationException("task stopped")

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ): HandoffResult = throw CancellationException("task stopped")
    }

    @Test
    fun `a question cancelled while the user sees it ends the call`() =
        runTest {
            assertCancelled(
                start("ask_user", """{"question":"几点见？"}""", FakeContext(interaction = CancelledInteraction())),
            )
        }

    @Test
    fun `a handoff cancelled while the user has the screen ends the call`() =
        runTest {
            assertCancelled(
                start("hand_to_user", """{"kind":"login"}""", FakeContext(interaction = CancelledInteraction())),
            )
        }

    @Test
    fun `an action cancelled in the port ends the call`() =
        runTest {
            val screen = FakeScreen().apply { onAct = { _, _ -> throw CancellationException("task stopped") } }
            assertCancelled(start("phone_tap", """{"screen":"s12","ref":"e10"}""", FakeContext(screen)))
            assertEquals(1, screen.acts.size)
        }

    @Test
    fun `an observation cancelled in the port ends the call`() =
        runTest {
            val screen = FakeScreen().apply { onSnapshot = { throw CancellationException("task stopped") } }
            assertCancelled(start("phone_observe", "{}", FakeContext(screen)))
        }

    @Test
    fun `cancelling a stable wait while it waits for a change ends it`() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            val screen =
                FakeScreen().apply {
                    onAwaitChange = {
                        waiting.complete(Unit)
                        awaitCancellation()
                    }
                }
            val run = start("phone_wait", """{"condition":"stable","timeoutMs":5000}""", FakeContext(screen))
            waiting.await()
            val reads = screen.snapshotOptions.size
            run.job.cancelAndJoin()
            assertCancelled(run)
            assertEquals(reads, screen.snapshotOptions.size)
        }

    @Test
    fun `cancelling an appear wait while it reads again ends it`() =
        runTest {
            val waiting = CompletableDeferred<Unit>()
            var reads = 0
            val screen =
                FakeScreen().apply {
                    onSnapshot = {
                        reads++
                        if (reads == 2) {
                            waiting.complete(Unit)
                            awaitCancellation()
                        }
                        current
                    }
                }
            val run =
                start("phone_wait", """{"condition":"appear","text":"已送达","timeoutMs":5000}""", FakeContext(screen))
            waiting.await()
            run.job.cancelAndJoin()
            assertCancelled(run)
            assertEquals(2, reads)
        }
}
