@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.testing.FakeChatModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The wire snapshot, the effective level and the context edits of every main request come from
 * `ChatModel.prepare`.
 */
class AgentLoopPrepareTest {
    private val wire =
        WireProfile(
            mappingVersion = 7,
            credential = CredentialKind.API_KEY,
            headers =
                mapOf("x-team" to "a"),
        )

    private val compaction = ContextEditPolicy.ServerSide(null, 3, 150_000, compactInstructions = "只写摘要")

    private fun model(
        name: String,
        wire: WireProfile,
        levels: List<ReasoningLevel>?,
        contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
    ) = FakeChatModel(
        FakeChatModel.INFO.copy(
            model = ModelId(name),
            capabilities =
                ModelCapabilities(
                    tools = true,
                    vision = true,
                    contextTokens = 1_000,
                    reasoningLevels = levels,
                ),
        ),
        wire,
        contextEdits,
    )

    @Test
    fun `the header records the wire, the clamped level and the edits prepare gives, and the request carries them`() =
        runTest {
            val levels = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM)
            val model = model("m1", wire, levels, compaction).answers("好了")
            val high =
                Interceptor<RequestPlan, RequestPlan> {
                    plan,
                    chain,
                    ->
                    chain.proceed(plan.copy(reasoning = ReasoningLevel.HIGH))
                }
            val harness =
                LoopHarness(
                    this,
                    model,
                    interceptors = Interceptors(requestError = emptyList(), request = listOf(high)),
                )
            val loop = harness.start()
            loop.followup(text("go"))
            loop.awaitPhase(Phase.Done)

            val header = harness.events().only<RequestHeader>().single()
            assertEquals(wire, header.wire)
            assertEquals(ReasoningLevel.MEDIUM, header.reasoning, "HIGH clamped to the model's highest level below it")
            assertEquals(compaction, header.contextEdits)
            assertEquals(
                listOf<ReasoningLevel?>(ReasoningLevel.HIGH),
                model.prepared,
                "the level the request chain gave",
            )
            val sent = model.requests.single()
            assertEquals(wire, sent.wire)
            assertEquals(ReasoningLevel.MEDIUM, sent.reasoning)
            assertEquals(compaction, sent.contextEdits)
        }

    @Test
    fun `a request chain that switches the model prepares the new one, edits included`() =
        runTest {
            val first = model("m1", wire, null).answers("一")
            val otherWire = WireProfile(mappingVersion = 2, credential = CredentialKind.NONE)
            val second = model("m2", otherWire, listOf(ReasoningLevel.LOW), compaction).answers("二")
            var requests = 0
            val switch =
                Interceptor<RequestPlan, RequestPlan> { plan, chain ->
                    requests += 1
                    val next = if (requests == 1) plan else plan.copy(model = second, reasoning = ReasoningLevel.HIGH)
                    chain.proceed(next)
                }
            val harness =
                LoopHarness(
                    this,
                    first,
                    interceptors = Interceptors(requestError = emptyList(), request = listOf(switch)),
                )
            val loop = harness.start()
            loop.followup(text("一"))
            advanceUntilIdle()
            loop.followup(text("二"))
            advanceUntilIdle()

            val headers = harness.events().only<RequestHeader>()
            assertEquals(listOf(wire, otherWire), headers.map { it.wire })
            assertEquals(listOf(null, ReasoningLevel.LOW), headers.map { it.reasoning })
            assertEquals(listOf(ModelId("m1"), ModelId("m2")), headers.map { it.model })
            assertEquals(listOf(ContextEditPolicy.ClientSide, compaction), headers.map { it.contextEdits })
            assertEquals(listOf<ReasoningLevel?>(ReasoningLevel.HIGH), second.prepared)
            assertEquals(otherWire, second.requests.single().wire)
            assertEquals(compaction, second.requests.single().contextEdits)
        }
}
