@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.handOff
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.SurfaceAllocation
import dev.phoneagent.core.ports.SurfaceCapability
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * However a handoff ends, the screens from before it no longer count (phase1 §7.3, contracts
 * addendum §14): from `handoff/start` on, the next call is judged on a screen recorded after it,
 * or on none, and the handing-off call's result tells the model to observe first.
 */
class AgentLoopHandoffScreenTest {
    private val texts = LoopTexts()
    private val c1 = ToolCallId("c1")

    private val look =
        FakeTool("look") { _, ctx ->
            ctx.screen!!.snapshot()
            ToolResult.ok("看到了")
        }
    private val tap = FakeTool("tap", RiskClass.WRITE)

    /** Moves the task; [takeBackMs] is how long taking it back takes, [handFailure] what handing it over throws. */
    private class Surfaces(
        private val takeBackMs: Long = 0,
        private val handFailure: RuntimeException? = null,
    ) : SurfaceManager {
        override val capability: StateFlow<SurfaceCapability> = MutableStateFlow(SurfaceCapability.Available(true))

        override suspend fun agentDisplay(task: TaskId): SurfaceAllocation =
            SurfaceAllocation.Ready(SurfaceRef(7, SurfaceKind.AGENT))

        override suspend fun release(task: TaskId) = Unit

        override suspend fun handToUser(
            task: TaskId,
            kind: HandoffKind,
        ): HandoffTicket {
            handFailure?.let { throw it }
            return HandoffTicket(task, kind, SurfaceRef(7, SurfaceKind.AGENT), "t1")
        }

        override suspend fun takeBack(ticket: HandoffTicket): SurfaceRef {
            delay(takeBackMs)
            return ticket.from
        }
    }

    /** What a test changes in [harness]: the surfaces, the config, and the storage (to resume on). */
    private class Options(
        val surfaces: SurfaceManager? = null,
        val config: LoopConfig = LoopConfig(),
        val storage: InMemoryStoragePort = InMemoryStoragePort(),
    )

    /** look (records s1 under c0), then [handing] as c1, handed off by [policy] or by itself. */
    private fun TestScope.harness(
        handing: FakeTool,
        policy: ScriptedPolicy,
        model: FakeChatModel = FakeChatModel().calls(call("c0", "look")).calls(call("c1", handing.def.name)),
        options: Options = Options(),
    ): LoopHarness {
        val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"))
        return LoopHarness(
            this,
            model,
            tools = listOf(look, handing, tap),
            policy = policy,
            screen = screen,
            surfaces = options.surfaces,
            config = options.config,
            storage = options.storage,
        )
    }

    private suspend fun LoopHarness.resultOf(id: ToolCallId): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId == id }.result

    private fun ToolResult.text(): String = (content.single() as Part.Text).text

    /** In [resumed], the user says to go on and the model taps; that tap is judged on no screen. */
    private suspend fun tapAfter(
        resumed: AgentLoop,
        policy: ScriptedPolicy,
        model: FakeChatModel,
    ) {
        model.calls(call("c2", "tap")).answers("好了")
        resumed.followup(text("继续"))
        resumed.awaitPhase(Phase.Done)
        assertTappedOnNoScreen(policy)
    }

    private fun assertTappedOnNoScreen(policy: ScriptedPolicy) {
        assertEquals(1, tap.calls.size)
        assertNull(policy.contexts.last().screen, "tap is not judged on the screen from before the handoff")
    }

    /** look, [handing] as c1, then tap as c2 in the same turn. */
    private fun sameTurn(handing: String): FakeChatModel =
        FakeChatModel()
            .calls(call("c0", "look"))
            .calls(call("c1", handing))
            .calls(call("c2", "tap"))
            .answers("好了")

    @Test
    fun `a stop during a handoff hides the earlier screen and the result says to observe`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Allow, handOff)
            val harness = harness(FakeTool("send", RiskClass.OUTWARD), policy)
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            loop.stop(StopCause.USER)
            loop.awaitStopped()

            assertTrue(harness.events().only<HandoffEnded>().isEmpty(), "the handoff never ended")
            assertNull(latestSnapshot(harness.records()))
            assertEquals("${texts.stoppedBeforeRun}\n${texts.screenUnknown}", harness.resultOf(c1).text())
        }

    @Test
    fun `a shutdown during a handoff, then a resume - the next call is judged on no screen`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Allow, handOff)
            val first = harness(FakeTool("send", RiskClass.OUTWARD), policy)
            val loop = first.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            first.loopScope.cancel()
            loop.awaitStopped()

            val model = FakeChatModel()
            val second = harness(FakeTool("send", RiskClass.OUTWARD), policy, model, Options(storage = first.storage))
            tapAfter(second.resume(), policy, model)
        }

    @Test
    fun `a process that died during a handoff is repaired with the note, and the next call sees no screen`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Allow, handOff)
            val first = harness(FakeTool("send", RiskClass.OUTWARD), policy)
            val loop = first.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            val crashed =
                InMemoryStoragePort().also { copy ->
                    first.storage.files.forEach { (k, v) ->
                        copy.files[k] =
                            v.copyOf()
                    }
                }

            val model = FakeChatModel()
            val second = harness(FakeTool("send", RiskClass.OUTWARD), policy, model, Options(storage = crashed))
            val resumed = second.resume()
            assertEquals(ToolStatus.NOT_STARTED, second.resultOf(c1).status)
            assertEquals("${texts.notStarted}\n${texts.screenUnknown}", second.resultOf(c1).text())
            tapAfter(resumed, policy, model)
        }

    @Test
    fun `taking the task back past the tool's budget times out, and the next call sees no screen`() =
        runTest {
            val hand =
                FakeTool("hand", RiskClass.WRITE, timeoutMs = 5_000) { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val policy = ScriptedPolicy()
            val harness = harness(hand, policy, sameTurn("hand"), Options(surfaces = Surfaces(takeBackMs = 60_000)))
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            val waiting = loop.awaitPrompt()
            assertTrue(loop.answer(waiting.promptId!!, UserReply.HandoffDone))
            advanceUntilIdle()

            assertTrue(harness.events().only<HandoffStarted>().isNotEmpty())
            assertTrue(harness.events().only<HandoffEnded>().isEmpty(), "cut off while taking the task back")
            val result = harness.resultOf(c1)
            assertEquals(ToolStatus.TIMEOUT, result.status)
            assertEquals("${texts.timeout}${texts.mayHaveTakenEffect}\n${texts.screenUnknown}", result.text())
            assertEquals(Phase.Done, loop.phase.value)
            assertTappedOnNoScreen(policy)
        }

    @Test
    fun `an unexpected hand-over failure ends as a failed take-back, and the next call sees no screen`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Allow, handOff)
            val surfaces = Surfaces(handFailure = IllegalStateException("surface bug"))
            val harness =
                harness(FakeTool("send", RiskClass.OUTWARD), policy, sameTurn("send"), Options(surfaces = surfaces))
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            advanceUntilIdle()

            assertEquals(
                ToolResult.failure(ToolStatus.ERROR, "${texts.handoffFailed}\n${texts.screenUnknown}"),
                harness.resultOf(c1),
                "the app may be with the user: not refused as if nothing moved",
            )
            assertEquals(Phase.Done, loop.phase.value, "the turn goes on")
            assertTappedOnNoScreen(policy)
        }

    @Test
    fun `control - a handoff that expires and cannot read the screen ends normally with the note`() =
        runTest {
            val policy = ScriptedPolicy(Verdict.Allow, handOff)
            val screenless = LoopConfig(handoffTimeoutMs = 1_000)
            val harness =
                harness(FakeTool("send", RiskClass.OUTWARD), policy, sameTurn("send"), Options(config = screenless))
            val loop = harness.start()
            loop.followup(text("登录以后点一下"))
            loop.awaitPrompt()
            (harness.screen as ScriptedScreen).unavailable = "secure window"
            advanceUntilIdle()

            assertEquals(1, harness.events().only<HandoffEnded>().size)
            assertEquals("${texts.handoffExpired}\n${texts.screenUnknown}", harness.resultOf(c1).text())
            assertEquals(Phase.Done, loop.phase.value)
            assertTappedOnNoScreen(policy)
        }
}
