package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.testing.TEST_WIRE
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class ModelContractsTest {
    private val schema = JsonObject(emptyMap())

    @Test
    fun `requests reject duplicate tools and bad caps`() {
        val tool = ToolDef("a", "d", schema)
        assertThrows<IllegalArgumentException> { ChatRequest(emptyList(), listOf(tool, tool), emptyList(), TEST_WIRE) }
        assertThrows<IllegalArgumentException> {
            ChatRequest(
                emptyList(),
                emptyList(),
                emptyList(),
                TEST_WIRE,
                maxOutputTokens = 0,
            )
        }
        assertThrows<IllegalArgumentException> { ToolDef("bad name", "d", schema) }
        assertThrows<IllegalArgumentException> { ToolDef("x".repeat(65), "d", schema) }
        val request = ChatRequest(emptyList(), listOf(tool), emptyList(), TEST_WIRE)
        assertEquals(CacheLayout.DEFAULT, request.cache)
        assertEquals(ContextEditPolicy.ClientSide, request.contextEdits)
    }

    @Test
    fun `cache layouts have at most four breakpoints`() {
        assertEquals(
            3,
            CacheLayout.DEFAULT.let {
                (if (it.tools) 1 else 0) + (if (it.system) 1 else 0) +
                    it.recentMessages
            },
        )
        CacheLayout(tools = true, system = true, recentMessages = 2)
        CacheLayout(tools = false, system = false, recentMessages = 4)
        assertThrows<IllegalArgumentException> { CacheLayout(tools = true, system = true, recentMessages = 3) }
        assertThrows<IllegalArgumentException> { CacheLayout(tools = false, system = false, recentMessages = -1) }
        assertEquals(0, CacheLayout.NONE.recentMessages)
    }

    @Test
    fun `server-side context edits validate thresholds`() {
        ContextEditPolicy.ServerSide(null, 0, null)
        ContextEditPolicy.ServerSide(1, 0, null)
        assertThrows<IllegalArgumentException> { ContextEditPolicy.ServerSide(0, 1, null) }
        assertThrows<IllegalArgumentException> { ContextEditPolicy.ServerSide(null, -1, null) }
        assertThrows<IllegalArgumentException> { ContextEditPolicy.ServerSide(null, 1, 0) }
    }

    @Test
    fun `the compaction trigger is at least the API's lowest`() {
        val lowest = ContextEditPolicy.ServerSide.MIN_COMPACT_ABOVE_TOKENS
        assertEquals(50_000, lowest)
        ContextEditPolicy.ServerSide(null, 3, lowest)
        val below = assertThrows<IllegalArgumentException> { ContextEditPolicy.ServerSide(null, 3, lowest - 1) }
        assertTrue(below.message!!.contains("49999"), below.message)
    }

    @Test
    fun `clear_at_least and instructions are positive, not blank, and only with their edit`() {
        ContextEditPolicy.ServerSide(100_000, 3, null, clearAtLeastTokens = 1)
        ContextEditPolicy.ServerSide(null, 3, 150_000, compactInstructions = "keep the goal")
        assertThrows<IllegalArgumentException> {
            ContextEditPolicy.ServerSide(100_000, 3, null, clearAtLeastTokens = 0)
        }
        assertThrows<IllegalArgumentException> {
            ContextEditPolicy.ServerSide(null, 3, 150_000, clearAtLeastTokens = 1)
        }
        assertThrows<IllegalArgumentException> {
            ContextEditPolicy.ServerSide(null, 3, 150_000, compactInstructions = " \n ")
        }
        assertThrows<IllegalArgumentException> {
            ContextEditPolicy.ServerSide(100_000, 3, null, compactInstructions = "keep the goal")
        }
    }

    @Test
    fun `usage counts are consistent`() {
        Usage(10, 5, cacheReadTokens = 6, cacheWriteTokens = 4, reasoningTokens = 5)
        assertThrows<IllegalArgumentException> { Usage(-1, 0) }
        assertThrows<IllegalArgumentException> { Usage(0, -1) }
        assertThrows<IllegalArgumentException> { Usage(5, 0, cacheReadTokens = -1) }
        assertThrows<IllegalArgumentException> { Usage(5, 0, cacheWriteTokens = -1) }
        assertThrows<IllegalArgumentException> { Usage(5, 0, cacheReadTokens = 4, cacheWriteTokens = 2) }
        assertThrows<IllegalArgumentException> { Usage(5, 3, reasoningTokens = 4) }
        assertThrows<IllegalArgumentException> { Usage(5, 3, reasoningTokens = -1) }
    }

    @Test
    fun `responses hold text, tool calls and reasoning and expose calls in order`() {
        val first = Part.ToolCall(ToolCallId("1"), "a", "{}")
        val second = Part.ToolCall(ToolCallId("2"), "b", "{}")
        val response = ChatResponse(listOf(Part.Text("x"), first, second), StopReason.TOOL_USE, Usage(1, 1))
        assertEquals(listOf(first, second), response.toolCalls)
        assertEquals(response, Json.decodeFromString<ChatResponse>(Json.encodeToString(response)))
        assertThrows<IllegalArgumentException> {
            ChatResponse(listOf(Part.ToolResult(ToolCallId("1"), emptyList(), false)), StopReason.OTHER)
        }
    }

    @Test
    fun `capabilities validate sizes and round-trip`() {
        val caps =
            ModelCapabilities(
                tools = true,
                vision = true,
                contextTokens = 200_000,
                maxOutputTokens = 64_000,
                reasoningLevels = listOf(ReasoningLevel.LOW, ReasoningLevel.MAX),
                promptCaching = PromptCaching.EXPLICIT,
                thinkingBinding = ThinkingBinding.PREFIX_BOUND,
                verified = setOf(VerifiedCapability.TOOL_CALL),
            )
        val info =
            ModelInfo(ProviderId("anthropic"), ModelId("claude-opus-5-5"), ModelProtocol.ANTHROPIC_MESSAGES, caps)
        assertEquals(info, Json.decodeFromString<ModelInfo>(Json.encodeToString(info)))
        assertThrows<IllegalArgumentException> { ModelCapabilities(tools = true, vision = false, contextTokens = 0) }
        assertThrows<IllegalArgumentException> {
            ModelCapabilities(tools = true, vision = false, contextTokens = 1, maxOutputTokens = 0)
        }
    }

    @Test
    fun `every model error has its own kind and the retry policy is explicit`() {
        val errors =
            listOf(
                ModelError.Auth("bad key"),
                ModelError.SignInExpired(),
                ModelError.PlanNotEligible("scope"),
                ModelError.QuotaExceeded("weekly"),
                ModelError.RateLimited(1500),
                ModelError.Overloaded(null),
                ModelError.ContextOverflow("too long"),
                ModelError.InvalidRequest("temperature", "not allowed"),
                ModelError.Refusal("cyber"),
                ModelError.PrefixBindingMismatch("thinking"),
                ModelError.Network("reset", IOException("reset")),
                ModelError.Server(503, "unavailable"),
                ModelError.BadResponse("no choices"),
            )
        assertEquals(ModelErrorKind.entries.toList(), errors.map { it.kind })
        val retryable = errors.filter { it.retryable }.map { it.kind }.toSet()
        assertEquals(
            setOf(
                ModelErrorKind.RATE_LIMITED,
                ModelErrorKind.OVERLOADED,
                ModelErrorKind.NETWORK,
                ModelErrorKind.SERVER,
                ModelErrorKind.BAD_RESPONSE,
            ),
            retryable,
        )
        assertEquals("invalid request (temperature): not allowed", errors[7].message)
        assertEquals("invalid request: x", ModelError.InvalidRequest(null, "x").message)
        assertEquals("model refused", ModelError.Refusal(null).message)
        assertEquals("rate limited (retry after ? ms)", ModelError.RateLimited(null).message)
        assertEquals(1500L, (errors[4] as ModelError.RateLimited).retryAfterMs)
        assertEquals(null, (errors[5] as ModelError.Overloaded).retryAfterMs)
        assertEquals(503, (errors[11] as ModelError.Server).status)
        assertEquals(listOf(1500L, null, null), listOf(errors[4], errors[5], errors[11]).map { it.retryAfterMs })
        assertEquals(2000L, ModelError.Server(503, "busy", retryAfterMs = 2000).retryAfterMs, "Retry-After on a 5xx")
        assertEquals(
            emptyList<ModelError>(),
            errors.filter { it.retryAfterMs != null && it !is ModelError.RateLimited },
            "only errors that carry a server delay report one",
        )
        assertEquals("temperature", (errors[7] as ModelError.InvalidRequest).param)
        assertEquals("not allowed", (errors[7] as ModelError.InvalidRequest).detail)
        assertEquals("cyber", (errors[8] as ModelError.Refusal).category)
        assertTrue(errors[10].cause is IOException)
        assertFalse(ModelError.BadResponse("x").cause != null)
        assertEquals(null, ModelError.Network("reset").cause)
    }

    @Test
    fun `only a bad response may discard what its attempt streamed`() {
        val errors =
            listOf(
                ModelError.Auth("bad key"),
                ModelError.SignInExpired(),
                ModelError.PlanNotEligible("scope"),
                ModelError.QuotaExceeded("weekly"),
                ModelError.RateLimited(1500),
                ModelError.Overloaded(null),
                ModelError.ContextOverflow("too long"),
                ModelError.InvalidRequest("temperature", "not allowed"),
                ModelError.Refusal("cyber"),
                ModelError.PrefixBindingMismatch("thinking"),
                ModelError.Network("reset"),
                ModelError.Server(503, "unavailable"),
                ModelError.BadResponse("no choices"),
            )
        assertEquals(emptyList<ModelError>(), errors.filter { it.discardsStreamed }, "kept by default")
        val secret = ModelError.BadResponse("the reply carries the request's secret", discardsStreamed = true)
        assertTrue(secret.discardsStreamed)
        assertEquals("bad response: the reply carries the request's secret", secret.message)
    }

    @Test
    fun `a server delay is never negative`() {
        val negative =
            mapOf(
                "RateLimited" to { ModelError.RateLimited(-1) },
                "Overloaded" to { ModelError.Overloaded(-1) },
                "Server" to { ModelError.Server(503, "busy", retryAfterMs = -1) },
            )
        negative.forEach { (name, build) -> assertThrows<IllegalArgumentException>(name) { build() } }
        assertEquals(0L, ModelError.RateLimited(0).retryAfterMs)
        assertEquals(0L, ModelError.Overloaded(0).retryAfterMs)
        assertEquals(0L, ModelError.Server(503, "busy", retryAfterMs = 0).retryAfterMs)
    }

    @Test
    fun `chat model streams deltas before returning`() =
        runTest {
            val model =
                object : ChatModel {
                    override val info =
                        ModelInfo(
                            ProviderId("p"),
                            ModelId("m"),
                            ModelProtocol.OPENAI_RESPONSES,
                            ModelCapabilities(true, false, 1),
                        )

                    override fun prepare(reasoning: ReasoningLevel?) = RequestSettings(null, TEST_WIRE)

                    override suspend fun complete(
                        request: ChatRequest,
                        onDelta: (Delta) -> Unit,
                    ): ChatResponse {
                        listOf(
                            Delta.Reasoning("r"),
                            Delta.Text("he"),
                            Delta.Text("y"),
                            Delta.ToolCallStarted(ToolCallId("c"), "t"),
                            Delta.ToolCallArgs(ToolCallId("c"), "{}"),
                        ).forEach(onDelta)
                        return ChatResponse(listOf(Part.Text("hey")), StopReason.END_TURN)
                    }
                }
            val seen = mutableListOf<Delta>()
            val response = model.complete(ChatRequest(emptyList(), emptyList(), emptyList(), TEST_WIRE)) { seen += it }
            assertEquals("hey", seen.filterIsInstance<Delta.Text>().joinToString("") { it.text })
            assertEquals(5, seen.size)
            assertEquals(StopReason.END_TURN, response.stopReason)
            assertEquals(
                StopReason.END_TURN,
                model.complete(ChatRequest(emptyList(), emptyList(), emptyList(), TEST_WIRE)).stopReason,
            )
            assertEquals(RequestSettings(null, TEST_WIRE), model.prepare(ReasoningLevel.HIGH))
        }
}
