@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.testing.ScriptedScreen.Companion.snapshotOf
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A screen that may have changed is no `ctx.screen` (contracts addendum §14, phase1 §3.8): a
 * resume, the start of a non-READ call and an action or launch hide every screen recorded before
 * them. An action's or launch's own read-back counts after it; without one there is no screen
 * until the next observation. Each case below judges its last call on no screen.
 */
class AgentLoopScreenBoundaryTest {
    private val unchanged = ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED, after = null)

    private val look =
        FakeTool("look") { _, ctx ->
            ctx.screen!!.snapshot()
            ToolResult.ok("看到了")
        }

    /** A WRITE tool named [name] doing [work] on its screen. */
    private fun writing(
        name: String,
        timeoutMs: Long = 10_000,
        work: suspend (ScreenPort) -> Unit,
    ) = FakeTool(name, RiskClass.WRITE, timeoutMs) { _, ctx ->
        work(ctx.screen!!)
        ToolResult.ok("做了")
    }

    private val press = FakeTool("press", RiskClass.WRITE)

    /**
     * Runs [calls] (tool names, as c0, c1, …; on a [storage] that already has a log, from c100 on)
     * in one turn on [screen].
     */
    private fun TestScope.harness(
        tools: List<FakeTool>,
        calls: List<String>,
        screen: ScriptedScreen,
        policy: ScriptedPolicy,
        storage: InMemoryStoragePort = InMemoryStoragePort(),
    ): LoopHarness {
        val model = FakeChatModel()
        val from = storage.files.size * FRESH_IDS
        calls.forEachIndexed { i, name -> model.calls(call("c${from + i}", name)) }
        model.answers("好了")
        return LoopHarness(this, model, tools + press, policy, screen = screen, storage = storage)
    }

    @Test
    fun `a resume after a clean shutdown hides the screen from before it`() =
        runTest {
            val first = harness(listOf(look), listOf("look"), ScriptedScreen(snapshotOf("s1")), ScriptedPolicy())
            first.start().followup(text("看一下"))
            advanceUntilIdle()
            first.loopScope.cancel()
            advanceUntilIdle()

            val policy = ScriptedPolicy()
            val second =
                harness(listOf(look), listOf("press"), ScriptedScreen(snapshotOf("s2")), policy, first.storage)
            val resumed = second.resume()
            resumed.followup(text("点一下"))
            advanceUntilIdle()

            assertEquals(listOf<String?>(null), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a resume after dying while a write ran hides the screen from before it`() =
        runTest {
            val running = CompletableDeferred<Unit>()
            val hung =
                writing("tap") {
                    running.complete(Unit)
                    awaitCancellation()
                }
            val first =
                harness(listOf(look, hung), listOf("look", "tap"), ScriptedScreen(snapshotOf("s1")), ScriptedPolicy())
            first.start().followup(text("点一下"))
            running.await()
            // The process dies while tap runs: what was stored by then is all there is.
            val crashed = InMemoryStoragePort()
            first.storage.files.forEach { (path, bytes) -> crashed.files[path] = bytes.copyOf() }

            val policy = ScriptedPolicy()
            val second = harness(listOf(look, hung), listOf("press"), ScriptedScreen(snapshotOf("s2")), policy, crashed)
            val resumed = second.resume()
            resumed.followup(text("继续"))
            advanceUntilIdle()

            assertEquals(listOf<String?>(null), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a write that timed out hides the screen from before it`() =
        runTest {
            val policy = ScriptedPolicy()
            val slow = writing("tap", timeoutMs = 1_000) { awaitCancellation() }
            val harness =
                harness(listOf(look, slow), listOf("look", "tap", "press"), ScriptedScreen(snapshotOf("s1")), policy)
            val loop = harness.start()
            loop.followup(text("点两下"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `a write that ran without reading the screen back hides the screen from before it`() =
        runTest {
            val policy = ScriptedPolicy()
            val tap = writing("tap") { it.act(UiAction.Key(SystemKey.BACK), on = null) }
            val screen = ScriptedScreen(snapshotOf("s1")).answers(unchanged)
            val harness = harness(listOf(look, tap), listOf("look", "tap", "press"), screen, policy)
            val loop = harness.start()
            loop.followup(text("返回再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `an action or a launch without a read-back hides even the screen its call read first`() =
        runTest {
            for (move in listOf<suspend (ScreenPort) -> Unit>(
                { it.act(UiAction.Key(SystemKey.BACK), on = null) },
                { it.launch(LaunchTarget.App("com.example.chat")) },
            )) {
                val policy = ScriptedPolicy()
                val tap =
                    writing("tap") {
                        it.snapshot()
                        move(it)
                    }
                val screen = ScriptedScreen(snapshotOf("s1"), snapshotOf("s2")).answers(unchanged)
                val harness = harness(listOf(look, tap), listOf("look", "tap", "press"), screen, policy)
                val loop = harness.start()
                loop.followup(text("看了再点"))
                loop.awaitPhase(Phase.Done)

                assertEquals(listOf(null, "s1", null), policy.contexts.map { it.screen?.id?.value })
            }
        }

    @Test
    fun `control - an action's read-back is the next call's screen`() =
        runTest {
            val policy = ScriptedPolicy()
            val tap = writing("tap") { it.act(UiAction.Key(SystemKey.BACK), on = null) }
            val screen = ScriptedScreen(snapshotOf("s1"), snapshotOf("s3"))
            val harness = harness(listOf(look, tap), listOf("look", "tap", "press"), screen, policy)
            val loop = harness.start()
            loop.followup(text("返回再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", "s3"), policy.contexts.map { it.screen?.id?.value })
        }

    @Test
    fun `control - a READ call that ran keeps the screen it read`() =
        runTest {
            val policy = ScriptedPolicy()
            val glance = FakeTool("glance")
            val harness =
                harness(
                    listOf(look, glance),
                    listOf("look", "glance", "press"),
                    ScriptedScreen(snapshotOf("s1")),
                    policy,
                )
            val loop = harness.start()
            loop.followup(text("看看再点"))
            loop.awaitPhase(Phase.Done)

            assertEquals(listOf(null, "s1", "s1"), policy.contexts.map { it.screen?.id?.value })
        }

    private companion object {
        /** Call ids of a resumed task start here, past those of the first run. */
        const val FRESH_IDS = 100
    }
}
