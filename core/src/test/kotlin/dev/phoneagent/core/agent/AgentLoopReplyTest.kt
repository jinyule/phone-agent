@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.ToolCallRecorded
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.tool.RiskClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** What a reply must be before the loop records it and acts on it (phase1 §3.7, §7.2 step 4). */
class AgentLoopReplyTest {
    private val retrying = Interceptors(requestError = listOf(RetryInterceptor(random = Random(0))))

    @Test
    fun `a reply that repeats a call id is a bad response, retried, and runs nothing`() =
        runTest {
            val tap = FakeTool("tap", RiskClass.WRITE)
            val model =
                FakeChatModel()
                    .calls(call("c1", "tap"), call("c1", "tap"), text = "先点")
                    .answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(tap), interceptors = retrying)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(Phase.Done, loop.phase.value)
            assertEquals(emptyList<Any>(), tap.calls)
            assertEquals(emptyList<ToolCallRecorded>(), events.only<ToolCallRecorded>())
            val attempt = events.only<AssistantAttempt>().single()
            assertEquals(ModelErrorKind.BAD_RESPONSE, attempt.error)
            assertEquals("bad response: tool call id c1 appears more than once in the reply", attempt.message)
            assertEquals(listOf(ModelErrorKind.BAD_RESPONSE), events.only<RequestRetry>().map { it.error })
            val replies = events.only<AssistantMessage>()
            assertEquals(listOf(true, false), replies.map { it.interrupted }, "what was streamed stays as a prefix")
            assertEquals(listOf(Part.Text("先点")), replies.first().parts)
            assertEquals(2, replies.last().attempt)
        }

    @Test
    fun `a call id already in the log is a bad response and the earlier call is not run again`() =
        runTest {
            val tap = FakeTool("tap", RiskClass.WRITE)
            val model =
                FakeChatModel()
                    .calls(call("c1", "tap"))
                    .calls(call("c2", "tap"), call("c1", "tap"))
                    .answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(tap), interceptors = retrying)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(Phase.Done, loop.phase.value)
            assertEquals(listOf("c1"), tap.calls.map { it.id.value })
            assertEquals(listOf("c1"), events.only<ToolCallRecorded>().map { it.call.id.value })
            assertEquals(
                "bad response: tool call id c1 was already used in this session",
                events.only<AssistantAttempt>().single().message,
            )
        }

    @Test
    fun `without a retry a repeated call id fails the turn and runs nothing`() =
        runTest {
            val tap = FakeTool("tap", RiskClass.WRITE)
            val model = FakeChatModel().calls(call("c1", "tap"), call("c1", "tap"))
            val harness = LoopHarness(this, model, tools = listOf(tap))
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            assertEquals(emptyList<Any>(), tap.calls)
            assertEquals(
                TurnEnded(1, TurnEndReason.FAILED, "bad response: tool call id c1 appears more than once in the reply"),
                harness.events().last(),
            )
            assertEquals(Phase.Idle, loop.phase.value)
        }

    private val thinking =
        Part.Reasoning(
            ProviderId("fake"),
            ModelId("fake-model"),
            ReasoningKind.ANTHROPIC_THINKING,
            JsonNull,
        )

    @Test
    fun `an empty reply is a bad response, retried, and does not end the turn`() =
        runTest {
            val model = FakeChatModel().replies(ChatResponse(emptyList(), StopReason.END_TURN)).answers("好了")
            val harness = LoopHarness(this, model, interceptors = retrying)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(Phase.Done, loop.phase.value)
            assertEquals(
                AssistantAttempt(
                    1,
                    ModelErrorKind.BAD_RESPONSE,
                    "bad response: the reply has no text and no tool call",
                ),
                events.only<AssistantAttempt>().single(),
            )
            assertEquals(listOf(listOf(Part.Text("好了"))), events.only<AssistantMessage>().map { it.parts })
        }

    @Test
    fun `a reply of reasoning or blank text only is no final answer`() =
        runTest {
            val model =
                FakeChatModel()
                    .replies(ChatResponse(listOf(thinking), StopReason.END_TURN))
                    .replies(ChatResponse(listOf(thinking, Part.Text(" \n")), StopReason.END_TURN))
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()
            loop.followup(text("again"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(Phase.Idle, loop.phase.value)
            assertEquals(listOf(TurnEndReason.FAILED, TurnEndReason.FAILED), events.only<TurnEnded>().map { it.reason })
            assertEquals(listOf(Part.Text(" \n")), events.only<AssistantMessage>().single().parts, "only the prefix")
            assertEquals(2, events.only<AssistantAttempt>().count { it.error == ModelErrorKind.BAD_RESPONSE })
        }

    @Test
    fun `a reply cut at the output cap with reasoning only ends the turn at the cap, not as a bad response`() =
        runTest {
            val model = FakeChatModel().replies(ChatResponse(listOf(thinking), StopReason.MAX_TOKENS))
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(TurnEnded(1, TurnEndReason.MAX_TOKENS), events.last())
            assertEquals(emptyList<AssistantAttempt>(), events.only<AssistantAttempt>())
            assertEquals(Phase.Idle, loop.phase.value)
        }
}
