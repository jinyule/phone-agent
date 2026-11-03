package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The wire snapshot (model-layer §2 "线路快照"): `prepare` maps the profile, the saved
 * [WireOptions] and the clamped level into it, and `complete` takes everything besides the
 * neutral request from the request's own snapshot, so a request read back from a log sends what
 * it sent then, whatever the model was built with. A snapshot these rules cannot have given is
 * refused before anything is sent.
 */
class WireSnapshotTest {
    @StartStop
    private val server = Kit.server()

    private val chatReply = Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
    private val responsesReply =
        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"

    @Suppress("LongParameterList") // Named test defaults; every caller sets a few.
    private fun model(
        protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
        credential: Credential = Kit.KEY,
        options: WireOptions = WireOptions(),
        capabilities: ModelCapabilities = Kit.capabilities(),
        reasoningField: String? = null,
        echoReasoning: Boolean = false,
    ): ChatModel {
        val profile =
            Kit.profile(
                server.base(),
                protocol,
                credential,
                reasoningField = reasoningField,
                echoReasoning = echoReasoning,
                headers = mapOf("X-Team" to "phone"),
            )
        return Kit.adapters().model(profile, Kit.MODEL, capabilities, Kit.blobs(), options)
    }

    private fun request(
        wire: WireProfile,
        reasoning: ReasoningLevel? = null,
    ): ChatRequest = Kit.request(maxOutputTokens = 64).copy(wire = wire, reasoning = reasoning)

    /** The body [model] sends for [request]. */
    private suspend fun sent(
        model: ChatModel,
        request: ChatRequest,
        reply: String = chatReply,
    ): JsonObject {
        server.enqueueSse(reply)
        model.complete(request)
        return server.next().json()
    }

    @Test
    fun `Chat prepare maps the profile, the options and the clamped level`() {
        val options =
            WireOptions(
                chat =
                    ChatQuirks(
                        parallelToolCalls = false,
                        streamUsage = false,
                        maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
                        reasoningEffort = ReasoningEffortStyle.OPENROUTER,
                        leakedCallFormats = setOf(LeakedCallFormat.TOOL_CALL_TAG, LeakedCallFormat.DSML),
                        recoverLeakedCalls = setOf(LeakedCallFormat.TOOL_CALL_TAG, LeakedCallFormat.DSML),
                    ),
            )
        val levels = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM)
        val model =
            model(
                options = options,
                capabilities = Kit.capabilities(vision = true, levels = levels),
                reasoningField = "reasoning_content",
                echoReasoning = true,
            )
        val settings = model.prepare(ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.MEDIUM, settings.reasoning)
        assertEquals(
            WireProfile(
                mappingVersion = OPENAI_MAPPING_VERSION,
                credential = CredentialKind.API_KEY,
                headers = mapOf("X-Team" to "phone"),
                images = ImageMode.INLINE,
                reasoningField = "reasoning_content",
                echoReasoning = true,
                reasoningEffort = WireSetting("reasoning.effort", "medium"),
                maxTokensField = "max_completion_tokens",
                disableParallelToolCalls = false,
                streamOptions = false,
                recoverLeakedToolCalls = true,
                options = mapOf(LEAKED_CALL_FORMATS to "dsml,tool_call_tag"),
            ),
            settings.wire,
        )
        assertNull(model.prepare(null).wire.reasoningEffort)
    }

    @Test
    fun `Chat prepare sends defaults, a top-level effort or none`() {
        val wire = model().prepare(ReasoningLevel.LOW).wire
        assertEquals(ImageMode.PLACEHOLDER, wire.images)
        assertEquals(WireSetting("reasoning_effort", "low"), wire.reasoningEffort)
        assertEquals("max_tokens", wire.maxTokensField)
        assertTrue(wire.disableParallelToolCalls && wire.streamOptions)
        assertFalse(wire.recoverLeakedToolCalls)
        assertEquals(emptyMap<String, String>(), wire.options)
        val none = WireOptions(chat = ChatQuirks(reasoningEffort = ReasoningEffortStyle.NONE))
        assertNull(model(options = none).prepare(ReasoningLevel.LOW).wire.reasoningEffort)
        val unselectable = model(capabilities = Kit.capabilities(levels = emptyList())).prepare(ReasoningLevel.LOW)
        assertNull(unselectable.reasoning)
        assertNull(unselectable.wire.reasoningEffort)
    }

    @Test
    fun `Responses prepare maps its own fields, and the plan channel takes no output cap`() {
        val wire =
            model(ModelProtocol.OPENAI_RESPONSES)
                .prepare(ReasoningLevel.HIGH)
                .wire
        assertEquals(WireSetting("reasoning.effort", "high"), wire.reasoningEffort)
        assertEquals("max_output_tokens", wire.maxTokensField)
        assertTrue(wire.disableParallelToolCalls && wire.includeEncryptedReasoning)
        assertNull(wire.reasoningField, "Responses has no reasoning field")
        assertFalse(wire.echoReasoning || wire.streamOptions || wire.recoverLeakedToolCalls)
        val plan = model(ModelProtocol.OPENAI_RESPONSES, Kit.PLAN).prepare(null).wire
        assertEquals(CredentialKind.CHATGPT_PLAN, plan.credential)
        assertNull(plan.maxTokensField)
        val noInclude = WireOptions(responses = ResponsesQuirks(includeEncryptedReasoning = false))
        assertFalse(
            model(ModelProtocol.OPENAI_RESPONSES, options = noInclude).prepare(null).wire.includeEncryptedReasoning,
        )
    }

    @Test
    fun `a Chat request sends what its snapshot says, not what the model was built with`() =
        runTest {
            val model = model(capabilities = Kit.capabilities(levels = listOf(ReasoningLevel.LOW)))
            val logged =
                model.prepare(null).wire.copy(
                    headers = mapOf("X-Logged" to "then"),
                    reasoningEffort = WireSetting("reasoning.effort", "high"),
                    maxTokensField = "max_completion_tokens",
                    disableParallelToolCalls = false,
                    streamOptions = false,
                )
            val body = sent(model, request(logged, ReasoningLevel.LOW))
            assertEquals(JsonObject(mapOf("effort" to JsonPrimitive("high"))), body["reasoning"])
            assertEquals(JsonPrimitive(64), body["max_completion_tokens"])
            assertFalse("max_tokens" in body || "parallel_tool_calls" in body || "stream_options" in body)
        }

    @Test
    fun `a Responses request sends what its snapshot says`() =
        runTest {
            val model = model(ModelProtocol.OPENAI_RESPONSES)
            val logged =
                model.prepare(ReasoningLevel.LOW).wire.copy(
                    reasoningEffort = null,
                    maxTokensField = null,
                    disableParallelToolCalls = false,
                    includeEncryptedReasoning = false,
                )
            val body = sent(model, request(logged, ReasoningLevel.LOW), responsesReply)
            assertFalse("reasoning" in body || "max_output_tokens" in body)
            assertFalse("parallel_tool_calls" in body || "include" in body)
        }

    @Test
    fun `snapshots these rules cannot have given are refused before anything is sent`() =
        runTest {
            val chat = model()
            val own = chat.prepare(null).wire
            val bad = "phone" + Char(1)
            val refused =
                listOf(
                    "wire.mappingVersion" to own.copy(mappingVersion = OPENAI_MAPPING_VERSION + 1),
                    "wire.credential" to own.copy(credential = CredentialKind.NONE),
                    "wire.headers" to own.copy(headers = mapOf("X-Team" to bad)),
                    "wire.reasoningEffort" to own.copy(reasoningEffort = WireSetting("model", "other")),
                    "wire.reasoningEffort" to own.copy(reasoningEffort = WireSetting("reasoning_effort", "extreme")),
                    "wire.maxTokensField" to own.copy(maxTokensField = "max_output_tokens"),
                    "wire.options" to own.copy(options = mapOf("temperature" to "2")),
                    "wire.includeEncryptedReasoning" to own.copy(includeEncryptedReasoning = true),
                    "wire.options" to
                        own.copy(recoverLeakedToolCalls = true, options = mapOf(LEAKED_CALL_FORMATS to "xml")),
                    "wire.recoverLeakedToolCalls" to own.copy(recoverLeakedToolCalls = true),
                    "wire.options" to
                        own.copy(recoverLeakedToolCalls = true, options = mapOf(LEAKED_CALL_FORMATS to "dsml")),
                )
            for ((param, wire) in refused) {
                val error = assertThrows<ModelError.InvalidRequest>(param) { chat.complete(request(wire)) }
                assertEquals(param, error.param)
                assertFalse(error.message.orEmpty().contains(bad), "the refused header value is not quoted")
            }
            val responses = model(ModelProtocol.OPENAI_RESPONSES)
            val ownResponses = responses.prepare(null).wire
            val refusedResponses =
                listOf(
                    "wire.reasoningEffort" to
                        ownResponses.copy(reasoningEffort = WireSetting("reasoning_effort", "low")),
                    "wire.maxTokensField" to ownResponses.copy(maxTokensField = "max_tokens"),
                    "wire.options" to ownResponses.copy(options = mapOf(LEAKED_CALL_FORMATS to "dsml")),
                    "wire.reasoningField" to ownResponses.copy(reasoningField = "reasoning_content"),
                    "wire.streamOptions" to ownResponses.copy(streamOptions = true),
                    "wire.recoverLeakedToolCalls" to ownResponses.copy(recoverLeakedToolCalls = true),
                )
            for ((param, wire) in refusedResponses) {
                val error = assertThrows<ModelError.InvalidRequest>(param) { responses.complete(request(wire)) }
                assertEquals(param, error.param)
            }
            assertEquals(0, server.requestCount, "nothing was sent")
        }

    @Test
    fun `the capabilities a model was created with are its own copy`() {
        for (protocol in listOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)) {
            val levels = mutableListOf(ReasoningLevel.LOW, ReasoningLevel.HIGH)
            val verified = mutableSetOf(VerifiedCapability.TOOL_CALL)
            val given = Kit.capabilities(levels = levels).copy(verified = verified)
            val model = model(protocol, capabilities = given)
            val prepared = model.prepare(ReasoningLevel.HIGH)
            val info =
                model.info.copy(
                    capabilities = given.copy(reasoningLevels = levels.toList(), verified = verified.toSet()),
                )
            levels.remove(ReasoningLevel.HIGH)
            verified.clear()
            assertEquals(prepared, model.prepare(ReasoningLevel.HIGH), "$protocol: prepare after the caller's change")
            assertEquals(info, model.info, "$protocol: info after the caller's change")
        }
    }

    @Test
    fun `a request still carrying the unprepared snapshot is refused`() =
        runTest {
            assertThrows<ModelError.InvalidRequest> { model().complete(Kit.request()) }
            assertEquals(0, server.requestCount)
        }
}
