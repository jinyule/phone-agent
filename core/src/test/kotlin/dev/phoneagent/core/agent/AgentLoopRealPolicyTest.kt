@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.policy.rules.PolicyRules
import dev.phoneagent.core.policy.rules.Refusal
import dev.phoneagent.core.screen.ScreenComponents
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import dev.phoneagent.core.tools.PhaseOneTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The loop assembled as the app assembles it: the policy capability (WP05 [PolicyRules]: its
 * resolver and engine, the presenter's lexicon) and the phase-1 tools (WP06). A screen that cannot
 * be read leaves the policy no screen (phase1 §3.8), and the policy, not the runtime, refuses a
 * screen action then (`call.no_screen`): the device is not touched. Without that rule both runs
 * below would reach the device or the user again; the merge order (WP05 before WP02) rests on it.
 */
class AgentLoopRealPolicyTest {
    private val c1 = ToolCallId("c1")
    private val c2 = ToolCallId("c2")
    private val rules = PolicyRules(setOf("dev.phoneagent"), setOf("PhoneAgent"))
    private val tools =
        PhaseOneTools.create(ScreenComponents(lexicon = rules.lexicon, vision = false).presenter)
    private val noScreen = Verdict.Refuse(Refusal.NO_SCREEN.reason, Refusal.NO_SCREEN.hint)

    /** A harness with the real policy and tools, the chat page s12 on [screen], playing [model]. */
    private fun TestScope.assembled(
        model: FakeChatModel,
        screen: ScriptedScreen,
    ): LoopHarness =
        LoopHarness(
            this,
            model,
            tools = tools,
            policy = rules.engine,
            intents = rules.resolver,
            screen = screen,
        )

    private suspend fun LoopHarness.resultOf(id: ToolCallId): ToolResult =
        events().only<ToolResultRecorded>().single { it.callId == id }.result

    private suspend fun LoopHarness.verdictsOf(id: ToolCallId): List<Verdict> =
        events().only<PolicyVerdictRecorded>().filter { it.callId == id }.map { it.verdict }

    @Test
    fun `an approved send whose re-check cannot read the screen is refused by the policy, not run`() =
        runTest {
            val screen = ScriptedScreen(Screens.CHAT)
            val model =
                FakeChatModel()
                    .calls(call("c0", "phone_observe"))
                    .calls(call("c1", "phone_tap", """{"screen":"s12","ref":"e10","reason":"发送"}"""))
                    .answers("发不出去")
            val harness = assembled(model, screen)
            val loop = harness.start()
            loop.followup(text("把消息发出去"))
            val prompt = loop.awaitPrompt().promptId!!
            assertTrue(
                harness
                    .events()
                    .only<ApprovalAsked>()
                    .single()
                    .card.callId == c1,
                "the send asks first",
            )

            screen.unavailable = "display gone"
            assertTrue(loop.answer(prompt, UserReply.Approve))
            advanceUntilIdle()

            assertEquals(noScreen, harness.verdictsOf(c1).last(), "the re-check")
            val result = harness.resultOf(c1)
            assertEquals(ToolStatus.REFUSED, result.status)
            assertEquals(Refusal.NO_SCREEN.hint, result.text())
            assertTrue(harness.events().only<ToolStarted>().none { it.callId == c1 }, "never started")
            assertEquals(emptyList<Any>(), screen.actions, "the device was not touched")
            loop.awaitPhase(Phase.Done)
        }

    @Test
    fun `a tap after a handoff whose look cannot read the screen is refused by the policy, not run`() =
        runTest {
            val (harness, screen) = tapAfterHandoff(readable = false)

            assertEquals(noScreen, harness.verdictsOf(c2).single())
            assertEquals(ToolStatus.REFUSED, harness.resultOf(c2).status)
            assertTrue(harness.events().only<ToolStarted>().none { it.callId == c2 }, "never started")
            assertEquals(emptyList<Any>(), screen.actions, "the device was not touched")
        }

    @Test
    fun `the same tap runs when the look after the handoff reads the screen`() =
        runTest {
            // The control: it is the missing screen alone that stops the tap.
            val (harness, screen) = tapAfterHandoff(readable = true)

            assertEquals(listOf<Verdict>(Verdict.Allow), harness.verdictsOf(c2))
            assertEquals(ToolStatus.OK, harness.resultOf(c2).status)
            assertEquals(1, screen.actions.size)
        }

    /**
     * c0 observes s12, c1 hands off to log in (main-display mode: no move), the user finishes;
     * unless [readable], the look after the handoff fails. Then c2 taps 返回 (e1) on s12.
     */
    private suspend fun TestScope.tapAfterHandoff(readable: Boolean): Pair<LoopHarness, ScriptedScreen> {
        val screen = ScriptedScreen(Screens.CHAT)
        val model =
            FakeChatModel()
                .calls(call("c0", "phone_observe"))
                .calls(call("c1", "hand_to_user", """{"kind":"login","reason":"请登录"}"""))
                .calls(call("c2", "phone_tap", """{"screen":"s12","ref":"e1","reason":"返回"}"""))
                .answers("好了")
        val harness = assembled(model, screen)
        val loop = harness.start()
        loop.followup(text("登录以后返回"))
        val prompt = loop.awaitPrompt().promptId!!
        if (!readable) screen.unavailable = "display gone"
        assertTrue(loop.answer(prompt, UserReply.HandoffDone))
        advanceUntilIdle()
        assertEquals(ToolStatus.OK, harness.resultOf(c1).status, "handed back")
        loop.awaitPhase(Phase.Done)
        return harness to screen
    }
}
