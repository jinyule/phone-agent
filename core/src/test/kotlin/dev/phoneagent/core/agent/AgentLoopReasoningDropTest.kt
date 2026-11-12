@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The default `requestError` chain recovers from a stale thinking signature once (model-layer §5). */
class AgentLoopReasoningDropTest {
    private val mismatch = ModelError.PrefixBindingMismatch("signature no longer valid")

    private fun block(
        kind: ReasoningKind,
        type: String,
    ) = Part.Reasoning(
        FakeChatModel.INFO.provider,
        FakeChatModel.INFO.model,
        kind,
        buildJsonObject { put("type", type) },
    )

    private val thinking = block(ReasoningKind.ANTHROPIC_THINKING, "thinking")
    private val compaction = block(ReasoningKind.ANTHROPIC_COMPACTION, "compaction")
    private val firstReply = listOf(compaction, thinking, Part.Text("看一下"), call("c1", "look"))

    private suspend fun TestScope.run(model: FakeChatModel): LoopHarness {
        val harness = LoopHarness(this, model, tools = listOf(FakeTool("look")), interceptors = Interceptors())
        harness.start().followup(text("go"))
        advanceUntilIdle()
        return harness
    }

    private fun FakeChatModel.replyParts(request: Int): List<Part> =
        requests[request].messages.single { it.role == Role.ASSISTANT }.parts

    @Test
    fun `a mismatch is retried at once without the thinking, the compaction block kept`() =
        runTest {
            val model =
                FakeChatModel()
                    .replies(ChatResponse(firstReply, StopReason.TOOL_USE))
                    .fails(mismatch)
                    .answers("好了")
            val harness = run(model)

            assertEquals(firstReply, model.replyParts(1))
            assertEquals(listOf(compaction, Part.Text("看一下"), call("c1", "look")), model.replyParts(2))
            val events = harness.events()
            val reply = harness.records().first { it.event is AssistantMessage }
            assertEquals(listOf(ReasoningDropped(reply.seq)), events.only<ReasoningDropped>())
            assertEquals(
                listOf(RequestRetry(2, 0, ModelErrorKind.PREFIX_BINDING_MISMATCH)),
                events.only<RequestRetry>(),
            )
            assertEquals(TurnEnded(1, TurnEndReason.COMPLETED), events.last())
        }

    @Test
    fun `a second mismatch after the drop fails the turn`() =
        runTest {
            val model =
                FakeChatModel()
                    .replies(ChatResponse(firstReply, StopReason.TOOL_USE))
                    .fails(mismatch)
                    .fails(mismatch)
            val harness = run(model)

            assertEquals(3, model.requests.size)
            val events = harness.events()
            assertEquals(1, events.only<ReasoningDropped>().size)
            assertEquals(1, events.only<RequestRetry>().size)
            assertEquals(TurnEnded(1, TurnEndReason.FAILED, mismatch.message), events.last())
        }

    @Test
    fun `a mismatch with no reasoning to drop fails the turn without a retry`() =
        runTest {
            val model =
                FakeChatModel()
                    .replies(ChatResponse(listOf(compaction, call("c1", "look")), StopReason.TOOL_USE))
                    .fails(mismatch)
            val harness = run(model)

            assertEquals(2, model.requests.size)
            val events = harness.events()
            assertEquals(emptyList<ReasoningDropped>(), events.only<ReasoningDropped>())
            assertEquals(emptyList<RequestRetry>(), events.only<RequestRetry>())
            assertEquals(TurnEnded(1, TurnEndReason.FAILED, mismatch.message), events.last())
        }
}
