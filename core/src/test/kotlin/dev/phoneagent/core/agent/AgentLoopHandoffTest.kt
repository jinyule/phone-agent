@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.agent.PolicySamples.handOff
import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * After a handoff the runtime looks at the screen again before the next request, and the next
 * call is judged on what it saw, or on no screen (phase1 §7.2, §7.3).
 */
class AgentLoopHandoffTest {
    private val c1 = ToolCallId("c1")
    private val texts = LoopTexts()

    /** Observes the screen, as `phone_observe` does. */
    private val look =
        FakeTool("look") { _, ctx ->
            ctx.screen!!.snapshot()
            ToolResult.ok("看到了")
        }
    private val tap = FakeTool("tap", RiskClass.WRITE)

    private class Run(
        val harness: LoopHarness,
        val policy: ScriptedPolicy,
        val screen: ScriptedScreen,
    ) {
        suspend fun events(): List<SessionEvent> = harness.events()

        suspend fun resultOf(id: ToolCallId): ToolResult =
            events().only<ToolResultRecorded>().single { it.callId == id }.result
    }

    /**
     * look (sees s1) → [handing] as c1, handed off by [verdicts] or by itself → tap. The user
     * finishes the handoff; [lose] makes the screen unreadable meanwhile.
     */
    private suspend fun TestScope.handOver(
        handing: Tool,
        vararg verdicts: Verdict,
        lose: Boolean = false,
    ): Run {
        val screen = ScriptedScreen(ScriptedScreen.snapshotOf("s1"), ScriptedScreen.snapshotOf("s2"))
        val policy = ScriptedPolicy(*verdicts)
        val model =
            FakeChatModel()
                .calls(call("c0", "look"))
                .calls(call("c1", handing.def.name))
                .calls(call("c2", "tap"))
                .answers("好了")
        val harness = LoopHarness(this, model, tools = listOf(look, handing, tap), policy = policy, screen = screen)
        val loop = harness.start()
        loop.followup(text("登录以后点一下"))
        val waiting = loop.awaitPrompt()
        if (lose) screen.unavailable = "secure window"
        assertTrue(loop.answer(waiting.promptId!!, UserReply.HandoffDone))
        advanceUntilIdle()
        assertEquals(Phase.Done, loop.phase.value)
        return Run(harness, policy, screen)
    }

    @Test
    fun `after a handoff the next call is judged on the screen the runtime saw then`() =
        runTest {
            val run = handOver(FakeTool("send", RiskClass.OUTWARD), Verdict.Allow, handOff)

            val events = run.events()
            val ended = events.indexOfFirst { it is HandoffEnded }
            assertEquals(ScreenObserved(c1, ScriptedScreen.snapshotOf("s2")), events[ended + 1])
            assertEquals(
                SnapshotId("s2"),
                run.policy.contexts[2]
                    .screen
                    ?.id,
                "tap is judged on s2, not on s1",
            )
            assertEquals(texts.handoffDone, run.resultOf(c1).text())
        }

    @Test
    fun `when the screen cannot be read after a handoff the next call is judged on none and the model is told`() =
        runTest {
            val run = handOver(FakeTool("send", RiskClass.OUTWARD), Verdict.Allow, handOff, lose = true)

            assertNull(run.policy.contexts[2].screen, "tap is not judged on the stale s1")
            assertEquals("${texts.handoffDone} ${texts.screenUnknown}", run.resultOf(c1).text())
            assertEquals(1, run.events().only<ScreenObserved>().size, "only look's")
        }

    @Test
    fun `a tool's own handoff is followed by a look at the screen too`() =
        runTest {
            val hand =
                FakeTool("hand") { _, ctx ->
                    ctx.interaction.handOff(HandoffKind.LOGIN, "请登录")
                    ToolResult.ok("用户处理完了")
                }
            val seen = handOver(hand)
            assertEquals(
                SnapshotId("s2"),
                seen.policy.contexts[2]
                    .screen
                    ?.id,
            )
            assertEquals("用户处理完了", seen.resultOf(c1).text())

            val lost = handOver(FakeTool("hand", run = hand::execute), lose = true)
            assertNull(lost.policy.contexts[2].screen)
            assertEquals("用户处理完了 ${texts.screenUnknown}", lost.resultOf(c1).text())
        }

    @Test
    fun `a note joins the result's last text, or stands as a text of its own`() {
        val image = Part.Image(BlobRef("a".repeat(64)), "image/jpeg", 720, 1280)
        assertEquals(listOf(Part.Text("看到了 注意")), ToolResult.ok("看到了").noted("注意").content)
        assertEquals(listOf(image, Part.Text("注意")), ToolResult(ToolStatus.OK, listOf(image)).noted("注意").content)
        assertEquals(listOf(Part.Text("注意")), ToolResult(ToolStatus.OK, emptyList()).noted("注意").content)
    }
}
