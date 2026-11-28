@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.TurnEnded
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * An error that discards what its attempt streamed (`ModelError.discardsStreamed`, phase1 §3.7):
 * no interrupted `assistant/message`, only the `assistant/attempt` with the error's fixed message,
 * so the streamed text reaches neither the log nor a later request.
 */
class AgentLoopDiscardedStreamTest {
    private val secretReply =
        ModelError.BadResponse("the reply carries the request's secret", discardsStreamed = true)

    private val attempt =
        AssistantAttempt(1, ModelErrorKind.BAD_RESPONSE, "bad response: the reply carries the request's secret")

    private fun ChatRequest.texts(): List<String> =
        messages.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }

    @Test
    fun `a failed turn keeps no prefix, in the log or in the next turn's request`() =
        runTest {
            val model = FakeChatModel().fails(secretReply, delivered = "已流出的半句").answers("好了")
            val harness = LoopHarness(this, model)
            val loop = harness.start()
            loop.followup(text("go"))
            advanceUntilIdle()

            val events = harness.events()
            assertEquals(emptyList<AssistantMessage>(), events.only<AssistantMessage>())
            assertEquals(listOf(attempt), events.only<AssistantAttempt>())
            assertEquals(TurnEndReason.FAILED, events.only<TurnEnded>().single().reason)

            loop.followup(text("再试"))
            loop.awaitPhase(Phase.Done)
            assertEquals(listOf("go", "再试"), model.requests[1].texts())
        }

    @Test
    fun `a retried attempt keeps no prefix, and the retry is the same request`() =
        runTest {
            val model = FakeChatModel().fails(secretReply, delivered = "已流出").answers("好了")
            val retrying = Interceptors(requestError = listOf(RetryInterceptor(random = Random(0))))
            val harness = LoopHarness(this, model, interceptors = retrying)
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val events = harness.events()
            val reply = events.only<AssistantMessage>().single()
            assertEquals(listOf(Part.Text("好了")), reply.parts, "only the retry's reply, nothing interrupted")
            assertEquals(listOf(attempt), events.only<AssistantAttempt>())
            assertEquals(model.requests[0].messages, model.requests[1].messages)
        }
}
