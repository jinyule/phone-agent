@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.QuestionAsked
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.session.TurnStarted
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.testing.ScriptedScreen.Companion.snapshotOf
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The policy's history and the repair follow what the log says ran (phase1 §3.7, addendum §8). */
class AgentLoopExecutionHistoryTest {
    /**
     * The standard level's outward rule in brief: sending in an App the task already sent in with
     * the user's approval is allowed; otherwise the card (target from the screen) is confirmed,
     * and a re-check allows only the card the user approved.
     */
    private class OutwardRule : PolicyEngine {
        val contexts = mutableListOf<PolicyContext>()
        private val targets = mapOf("s1" to "测试甲", "s2" to "测试乙")

        override fun evaluate(
            call: ToolCall,
            intent: ActionIntent,
            ctx: PolicyContext,
        ): Verdict {
            contexts += ctx
            val target = targets[ctx.screen?.id?.value]
            val card = ConfirmCard(call.id, ConfirmReason.OUTWARD, "发送消息", target, pkg = intent.pkg)
            return when {
                intent.kind != IntentKind.OUTWARD -> Verdict.Allow
                intent.pkg in ctx.outwardConfirmedPackages -> Verdict.Allow
                ctx.approved == card -> Verdict.Allow
                else -> Verdict.Confirm(card)
            }
        }
    }

    @Test
    fun `a re-check whose target changed asks again, and only a sent message qualifies the App`() =
        runTest {
            val policy = OutwardRule()
            val look =
                FakeTool("look") { _, ctx ->
                    ctx.screen!!.snapshot()
                    ToolResult.ok("看到了")
                }
            // send reads its screen back, as a real send does: without that, c2 would be judged on
            // no screen (AgentLoopScreenBoundaryTest).
            val send =
                FakeTool("send", risk = RiskClass.OUTWARD) { _, ctx ->
                    ctx.screen!!.act(UiAction.Key(SystemKey.ENTER), on = null)
                    ToolResult.ok("发了")
                }
            val model =
                FakeChatModel()
                    .calls(call("c0", "look"))
                    .calls(call("c1", "send"))
                    .calls(call("c2", "send"))
                    .answers("发好了")
            val screen = ScriptedScreen(snapshotOf("s1"), snapshotOf("s2"))
            val harness = LoopHarness(this, model, tools = listOf(look, send), policy = policy, screen = screen)
            val loop = harness.start()
            loop.followup(text("给测试甲发消息"))
            val first = loop.approveNext()
            loop.approveNext(after = first)
            loop.awaitPhase(Phase.Done)

            val cards = harness.events().only<ApprovalAsked>().map { it.card.target }
            assertEquals(listOf("测试甲", "测试乙"), cards, "the changed target is confirmed again")
            assertEquals(listOf("c1", "c2"), send.calls.map { it.id.value })
            val recheck = policy.contexts[2]
            assertEquals(outwardCard("c1"), recheck.approved)
            assertEquals(emptySet<String>(), recheck.outwardConfirmedPackages, "an approval alone qualifies nothing")
            assertEquals(setOf(CHAT), policy.contexts.last().outwardConfirmedPackages, "after c1 was sent")
        }

    @Test
    fun `resume repairs from the log alone - without tool-started NOT_STARTED, with it OUTCOME_UNKNOWN`() =
        runTest {
            val harness = LoopHarness(this, FakeChatModel(), tools = listOf(FakeTool("ask"), FakeTool("send")))
            val store = create(harness)
            store.append(TurnStarted(1))
            store.append(UserMessage(UserMessageKind.USER, listOf(Part.Text("go"))))
            store.append(StepStarted(1, 1))
            val reply = store.append(reply(call("c1", "ask")))
            store.append(dispatched("c1", "ask"), sources = listOf(reply.seq))
            val verdict = store.append(verdict("c1", IntentKind.READ, Verdict.Allow))
            store.append(ToolStarted(ToolCallId("c1"), verdict.seq))
            store.append(QuestionAsked(PromptId("prompt-9"), ToolCallId("c1"), Question("谁？")))
            store.flush()
            store.close()

            harness.resume()
            advanceUntilIdle()
            assertEquals(
                listOf(ToolStatus.OUTCOME_UNKNOWN),
                harness.events().only<ToolResultRecorded>().map { it.result.status },
                "started: it may have run",
            )

            val other = LoopHarness(this, FakeChatModel(), tools = listOf(FakeTool("send", risk = RiskClass.OUTWARD)))
            val waiting = create(other)
            val card = outwardCard("c3")
            waiting.append(TurnStarted(1))
            waiting.append(StepStarted(1, 1))
            val sendReply = waiting.append(reply(call("c3", "send")))
            waiting.append(dispatched("c3", "send"), sources = listOf(sendReply.seq))
            waiting.append(verdict("c3", IntentKind.OUTWARD, Verdict.Confirm(card)))
            waiting.append(ApprovalAsked(PromptId("prompt-6"), card, expiresAt = 900_000))
            waiting.flush()
            waiting.close()

            other.resume()
            advanceUntilIdle()
            assertEquals(
                listOf(ToolStatus.NOT_STARTED),
                other.events().only<ToolResultRecorded>().map { it.result.status },
                "dispatched, waiting for its approval: it never ran",
            )
        }

    private fun outwardCard(id: String) = ConfirmCard(ToolCallId(id), ConfirmReason.OUTWARD, "发送消息", "测试甲", pkg = CHAT)

    private suspend fun create(harness: LoopHarness): SessionStore =
        SessionStore.create(
            harness.files,
            LoopHarness.SESSION,
            LoopHarness.TASK,
            LoopHarness.ATTENDED,
            harness.setup.clock,
        )

    private fun dispatched(
        id: String,
        name: String,
    ) = ToolCallRecorded(
        ToolCall(ToolCallId(id), LoopHarness.TASK, LoopHarness.SESSION, name, JsonObject(emptyMap()), "问"),
    )

    private fun verdict(
        id: String,
        kind: IntentKind,
        verdict: Verdict,
    ) = PolicyVerdictRecorded(ToolCallId(id), SecurityLevel.STANDARD, ActionIntent(kind), verdict)

    private fun reply(call: Part.ToolCall) =
        AssistantMessage(1, FakeChatModel.INFO.provider, FakeChatModel.INFO.model, listOf(call), StopReason.TOOL_USE)

    private companion object {
        const val CHAT = "com.example.chat"
    }
}
