package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import dev.phoneagent.core.model.anthropic.Kit.enqueueSse
import dev.phoneagent.core.model.anthropic.Kit.json
import dev.phoneagent.core.model.anthropic.Kit.next
import dev.phoneagent.core.testing.Golden
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [AnthropicSettings.serverContext] through the three gates (model-layer §3.3, §5): the defaults
 * turn compaction on for the Claude API and the models known to take it, settings that do not fit
 * are refused when the model is made, and a request whose edits the endpoint does not take is
 * refused before sending. An overflow without compaction says why nothing trimmed the history.
 */
class ServerContextTest {
    private val server = Kit.loopbackServer()
    private val official = Kit.profile()
    private val gateway = Kit.profile("https://gateway.example.com")
    private val compaction =
        ContextEditPolicy.ServerSide(
            clearToolUsesAboveTokens = null,
            keepToolUses = 3,
            compactAboveTokens = AnthropicSettings.COMPACT_ABOVE_TOKENS,
            compactInstructions = AnthropicSettings.COMPACTION_INSTRUCTIONS,
        )
    private val clearing = ContextEditPolicy.ServerSide(100_000, 3, null, clearAtLeastTokens = 20_000)

    @AfterEach
    fun closeServer() = server.close()

    @Test
    fun `the Claude API gets compaction at 150K with the instructions by default, and no clearing`() {
        val known = listOf(AnthropicPresets.MAIN_MODEL, AnthropicPresets.SONNET_MODEL, AnthropicPresets.LIGHT_MODEL)
        for (model in known) {
            assertEquals(compaction, AnthropicSettings.defaultsFor(official, model).serverContext, "$model")
        }
        assertEquals(150_000, AnthropicSettings.COMPACT_ABOVE_TOKENS)
        assertNull(AnthropicSettings.defaultsFor(gateway, AnthropicPresets.MAIN_MODEL).serverContext, "not Claude")
        assertNull(AnthropicSettings.defaultsFor(official, ModelId("claude-opus-4-1")).serverContext, "not known")
    }

    @Test
    fun `server context must turn something on, and clearing needs clear_at_least`() {
        val nothing =
            assertThrows<IllegalArgumentException> {
                AnthropicSettings(serverContext = ContextEditPolicy.ServerSide(null, 3, null))
            }
        assertTrue("null turns both off" in nothing.message.orEmpty(), nothing.message)
        val everyStep =
            assertThrows<IllegalArgumentException> {
                AnthropicSettings(serverContext = ContextEditPolicy.ServerSide(100_000, 3, null))
            }
        assertTrue("clearAtLeastTokens" in everyStep.message.orEmpty(), everyStep.message)
        assertThrows<IllegalArgumentException> {
            AnthropicSettings(serverContext = ContextEditPolicy.ServerSide(100_000, 3, 150_000))
        }
        AnthropicSettings(serverContext = clearing)
        AnthropicSettings(serverContext = ContextEditPolicy.ServerSide(null, 3, 150_000))
    }

    @Test
    fun `settings made to fit keep server context only on the Claude API with a known model`() {
        val saved = AnthropicSettings(serverContext = clearing)
        assertEquals(saved, saved.effectiveFor(official, AnthropicPresets.LIGHT_MODEL))
        assertEquals(AnthropicSettings(), saved.effectiveFor(gateway, AnthropicPresets.MAIN_MODEL))
        assertEquals(AnthropicSettings(), saved.effectiveFor(official, ModelId("claude-opus-4-1")))
        assertEquals(AnthropicSettings(), AnthropicSettings().effectiveFor(official, AnthropicPresets.MAIN_MODEL))
    }

    @Test
    fun `server context the endpoint or the model cannot take is refused when the model is made`() {
        fun refused(
            profile: ProviderProfile,
            model: ModelId,
        ): ModelError.InvalidRequest =
            assertThrows {
                Kit.adapters().model(
                    profile,
                    model,
                    Kit.CAPABILITIES,
                    Kit.blobs(),
                    AnthropicSettings(serverContext = compaction),
                )
            }
        val onGateway = refused(gateway, AnthropicPresets.MAIN_MODEL)
        assertEquals("settings.serverContext", onGateway.param)
        assertTrue(gateway.baseUrl in onGateway.detail, onGateway.detail)
        assertEquals("settings.serverContext", refused(official, ModelId("claude-opus-4-1")).param)
        val fallbackFirst =
            assertThrows<ModelError.InvalidRequest> {
                AnthropicWire.snapshot(
                    gateway,
                    Kit.MODEL,
                    Kit.CAPABILITIES,
                    null,
                    AnthropicSettings(serverFallback = true, serverContext = compaction),
                )
            }
        assertEquals("settings.serverFallback", fallbackFirst.param, "the first setting that does not fit is named")
    }

    @Test
    fun `a threshold the conversation cannot reach before the window is full is refused`() {
        fun snapshot(
            edits: ContextEditPolicy.ServerSide,
            contextTokens: Int,
        ) = AnthropicWire.snapshot(
            official,
            Kit.MODEL,
            Kit.CAPABILITIES.copy(contextTokens = contextTokens),
            null,
            AnthropicSettings(serverContext = edits),
        )
        val atWindow = assertThrows<ModelError.InvalidRequest> { snapshot(compaction, 150_000) }
        assertEquals("settings.serverContext", atWindow.param)
        assertTrue("150000" in atWindow.detail, atWindow.detail)
        snapshot(compaction, 150_001)
        val both = ContextEditPolicy.ServerSide(200_000, 3, 150_000, clearAtLeastTokens = 20_000)
        val clearAbove = assertThrows<ModelError.InvalidRequest> { snapshot(both, 180_000) }
        assertTrue("200000" in clearAbove.detail, "the higher of the two is checked: ${clearAbove.detail}")
        snapshot(both, 200_001)
        snapshot(clearing, 100_001)
    }

    @Test
    fun `prepare gives the server context as the request's edits, and the snapshot options leave it out`() {
        val defaults = Kit.adapters().model(official, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
        val prepared = defaults.prepare(ReasoningLevel.HIGH)
        assertEquals(compaction, prepared.contextEdits)
        assertEquals(AnthropicSettings.defaultsFor(official, Kit.MODEL).options(), prepared.wire.options)
        val none = Kit.adapters().model(official, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs(), AnthropicSettings())
        assertEquals(ContextEditPolicy.ClientSide, none.prepare(null).contextEdits)
        val onGateway = Kit.adapters().model(gateway, Kit.MODEL, Kit.CAPABILITIES, Kit.blobs())
        assertEquals(ContextEditPolicy.ClientSide, onGateway.prepare(null).contextEdits)
    }

    @Test
    fun `the Claude API gets the edits and their betas as prepared`() =
        runTest {
            server.enqueueSse(Kit.TEXT_REPLY)
            val model = Kit.official(server)
            val prepared = model.prepare(ReasoningLevel.HIGH)
            model.complete(Kit.request(wire = prepared.wire, contextEdits = prepared.contextEdits))
            val sent = server.next()
            assertEquals(
                "server-side-fallback-2026-07-01,thinking-binding-controls-2026-08-01,compact-2026-01-12",
                sent.headers["anthropic-beta"],
            )
            val edits = sent.json().getValue("context_management").toString()
            assertTrue("compact_20260112" in edits && "clear_tool_uses" !in edits, edits)
        }

    @Test
    fun `server-side edits are refused on another endpoint before anything is sent`() =
        runTest {
            for (edits in listOf(compaction, clearing)) {
                server.enqueueSse(Kit.TEXT_REPLY) // would be consumed if the request were sent
                val error =
                    assertThrows<ModelError.InvalidRequest> {
                        Kit.model(server).complete(Kit.request(contextEdits = edits))
                    }
                assertEquals("contextEdits", error.param, "$edits")
                assertTrue("prepare the request for this model" in error.detail, error.detail)
                assertFalse(error.retryable)
            }
            assertEquals(0, server.requestCount, "nothing was sent")
        }

    @Test
    fun `an overflow without compaction says why nothing trimmed the history`() =
        runTest {
            val tooLong = """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long"}}"""

            suspend fun overflow(
                model: ChatModel,
                edits: ContextEditPolicy,
            ): ModelError.ContextOverflow {
                server.enqueueError(400, tooLong)
                val wire = model.prepare(ReasoningLevel.HIGH).wire
                return assertThrows { model.complete(Kit.request(wire = wire, contextEdits = edits)) }
            }
            val compacting = Kit.official(server)
            assertEquals("prompt is too long", overflow(compacting, compaction).detail, "compaction was on")
            val clearingOnly = Kit.official(server, AnthropicSettings(serverContext = clearing))
            val turnedOff = overflow(clearingOnly, clearing).detail
            assertTrue(turnedOff.startsWith("prompt is too long; "), turnedOff)
            assertTrue("compaction is off in this model's settings" in turnedOff, turnedOff)
            val elsewhere = overflow(Kit.model(server), ContextEditPolicy.ClientSide).detail
            assertTrue("this endpoint is not the Claude API" in elsewhere, elsewhere)
            assertTrue("only appended to" in elsewhere, elsewhere)
        }

    @Test
    fun `an overflow reported inside the stream says so too`() =
        runTest {
            val error = """{"type":"error","error":{"type":"request_too_large","message":"too large"}}"""
            server.enqueueSse(Kit.sse("error" to error))
            val model = Kit.official(server, AnthropicSettings())
            val overflow =
                assertThrows<ModelError.ContextOverflow> {
                    model.complete(Kit.request(wire = model.prepare(ReasoningLevel.HIGH).wire))
                }
            assertTrue(overflow.detail.startsWith("too large; server-side compaction is off"), overflow.detail)
        }

    @Test
    fun `the compaction instructions`() {
        Golden.assertMatches("compaction-instructions.expected.md", AnthropicSettings.COMPACTION_INSTRUCTIONS)
    }
}
