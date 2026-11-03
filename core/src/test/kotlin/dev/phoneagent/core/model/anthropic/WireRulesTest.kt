package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The adapter reads its settings only from `request.wire` and refuses snapshots it cannot honor. */
class WireRulesTest {
    private val wire = Kit.wire()

    private fun rules(
        snapshot: WireProfile,
        reasoning: ReasoningLevel? = ReasoningLevel.HIGH,
        credential: CredentialKind = CredentialKind.API_KEY,
    ): WireRules = WireRules.of(Kit.request(reasoning = reasoning, wire = snapshot), credential)

    private fun refused(
        snapshot: WireProfile,
        reasoning: ReasoningLevel? = ReasoningLevel.HIGH,
        credential: CredentialKind = CredentialKind.API_KEY,
    ): ModelError.InvalidRequest = assertThrows { rules(snapshot, reasoning, credential) }

    @Test
    fun `the official snapshot turns on every recommended setting`() {
        val rules = rules(wire)
        assertEquals(AnthropicWire.API_VERSION, rules.apiVersion)
        assertFalse(rules.bearer)
        assertTrue(rules.adaptiveThinking && rules.eager && rules.fallbacks && rules.inlineImages)
        assertEquals("summarized", rules.display)
        assertEquals("drop_block", rules.prefixMismatch)
        assertEquals("high", rules.effort)
        assertFalse(rules.strict)
    }

    @Test
    fun `a bare snapshot sends only what Messages requires`() {
        val bare =
            WireProfile(1, CredentialKind.API_KEY, maxTokensField = "max_tokens", disableParallelToolCalls = true)
        val rules = rules(bare, reasoning = null)
        assertEquals(AnthropicWire.API_VERSION, rules.apiVersion)
        assertFalse(rules.adaptiveThinking || rules.eager || rules.fallbacks || rules.strict || rules.bearer)
        assertNull(rules.display)
        assertNull(rules.prefixMismatch)
        assertNull(rules.effort)
        val falseFlags =
            bare.copy(
                options =
                    mapOf(
                        AnthropicWire.OPT_EAGER to "false",
                        AnthropicWire.OPT_STRICT to "false",
                    ),
            )
        assertFalse(rules(falseFlags, reasoning = null).eager)
    }

    @Test
    fun `another mapping version or credential kind is refused before sending`() {
        assertEquals("wire.mappingVersion", refused(wire.copy(mappingVersion = 2)).param)
        assertEquals("wire.credential", refused(wire, credential = CredentialKind.NONE).param)
        val keyless = rules(wire.copy(credential = CredentialKind.NONE), credential = CredentialKind.NONE)
        assertEquals("high", keyless.effort, "a matching kind is accepted")
    }

    @Test
    fun `fields of the other protocols must keep their defaults`() {
        val cases =
            listOf(
                wire.copy(reasoningField = "reasoning_content"),
                wire.copy(reasoningField = "reasoning_content", echoReasoning = true),
                wire.copy(streamOptions = true),
                wire.copy(includeEncryptedReasoning = true),
                wire.copy(recoverLeakedToolCalls = true),
            )
        cases.forEach { assertEquals("wire", refused(it).param, it.toString()) }
        assertTrue(refused(wire.copy(streamOptions = true)).detail.contains("streamOptions"))
    }

    @Test
    fun `a snapshot that allows parallel tool calls is refused`() {
        val parallel = refused(wire.copy(disableParallelToolCalls = false))
        assertEquals("wire.disableParallelToolCalls", parallel.param)
        assertTrue(parallel.detail.contains("one tool call per reply"), parallel.detail)
    }

    @Test
    fun `max_tokens is the only cap field`() {
        assertEquals("wire.maxTokensField", refused(wire.copy(maxTokensField = null)).param)
        assertEquals("wire.maxTokensField", refused(wire.copy(maxTokensField = "max_completion_tokens")).param)
    }

    @Test
    fun `the effort must be the mapping of the request's level`() {
        assertEquals("wire.reasoningEffort", refused(wire, reasoning = ReasoningLevel.LOW).param)
        assertEquals("wire.reasoningEffort", refused(wire, reasoning = null).param)
        assertEquals("wire.reasoningEffort", refused(wire.copy(reasoningEffort = null)).param)
        val wrongField = wire.copy(reasoningEffort = WireSetting("reasoning.effort", "high"))
        assertEquals("wire.reasoningEffort", refused(wrongField).param)
        assertEquals("low", rules(Kit.wire(reasoning = ReasoningLevel.MINIMAL), ReasoningLevel.MINIMAL).effort)
        assertEquals("medium", rules(Kit.wire(reasoning = ReasoningLevel.MEDIUM), ReasoningLevel.MEDIUM).effort)
    }

    @Test
    fun `unknown options and bad values are refused`() {
        val cases =
            mapOf(
                "anthropic.betas" to "x",
                AnthropicWire.OPT_VERSION to "latest",
                AnthropicWire.OPT_AUTH to "basic",
                AnthropicWire.OPT_THINKING to "enabled",
                AnthropicWire.OPT_DISPLAY to "full",
                AnthropicWire.OPT_PREFIX_MISMATCH to "ignore",
                AnthropicWire.OPT_FALLBACKS to "claude-opus-4-8",
                AnthropicWire.OPT_EAGER to "yes",
            )
        cases.forEach { (name, value) ->
            val error = refused(wire.copy(options = wire.options + (name to value)))
            assertEquals("wire.options", error.param, name)
            assertTrue(error.detail.contains(name), error.detail)
        }
    }

    @Test
    fun `display and prefix mismatch need adaptive thinking`() {
        val withoutThinking = wire.copy(options = wire.options - AnthropicWire.OPT_THINKING)
        assertEquals("wire.options", refused(withoutThinking).param)
        val displayOnly = withoutThinking.copy(options = withoutThinking.options - AnthropicWire.OPT_PREFIX_MISMATCH)
        assertEquals("wire.options", refused(displayOnly).param)
    }

    @Test
    fun `the adapter's own headers cannot be overridden and bad values are not echoed`() {
        listOf("anthropic-version", "Anthropic-Beta", "Content-Type", "accept").forEach { name ->
            assertEquals("wire.headers", refused(wire.copy(headers = mapOf(name to "x"))).param, name)
        }
        val error = refused(wire.copy(headers = mapOf("X-Trace" to "secret-ish\nvalue")))
        assertEquals("wire.headers", error.param)
        assertFalse(error.message!!.contains("secret-ish"), error.message)
        assertEquals("1", rules(wire.copy(headers = mapOf("X-Trace" to "1"))).headers["x-trace"])
    }

    @Test
    fun `placeholder images come from the snapshot, not the capabilities`() {
        assertFalse(rules(wire.copy(images = ImageMode.PLACEHOLDER)).inlineImages)
    }

    @Test
    fun `snapshots of the official endpoint and of gateways differ only in Claude API betas`() {
        val official = Kit.wire()
        assertEquals(
            mapOf(
                "anthropic.version" to "2026-10-21",
                "anthropic.auth" to "x-api-key",
                "anthropic.thinking" to "adaptive",
                "anthropic.thinking_display" to "summarized",
                "anthropic.prefix_mismatch" to "drop_block",
                "anthropic.fallbacks" to "default",
                "anthropic.eager_input_streaming" to "true",
            ),
            official.options,
        )
        assertEquals(WireSetting("output_config.effort", "high"), official.reasoningEffort)
        assertEquals("max_tokens", official.maxTokensField)
        val gateway = Kit.profile("http://127.0.0.1:8080")
        val custom = AnthropicWire.snapshot(gateway, Kit.MODEL, Kit.CAPABILITIES, null)
        assertEquals(official.options - "anthropic.prefix_mismatch" - "anthropic.fallbacks", custom.options)
        assertNull(custom.reasoningEffort)
        val haiku = AnthropicSettings.defaultsFor(Kit.profile(), AnthropicPresets.LIGHT_MODEL)
        assertFalse(haiku.serverFallback, "Claude Haiku 5.5 has no server-side fallback")
        assertEquals(PrefixMismatch.DROP_BLOCK, haiku.prefixMismatch)
    }

    @Test
    fun `settings and snapshots refuse what cannot be sent`() {
        assertThrows<IllegalArgumentException> { AnthropicSettings(apiVersion = "v1") }
        assertThrows<IllegalArgumentException> { AnthropicSettings(adaptiveThinking = false) }
        assertThrows<IllegalArgumentException> {
            AnthropicSettings(adaptiveThinking = false, thinkingDisplay = null, prefixMismatch = PrefixMismatch.ERROR)
        }
        val chat =
            ProviderProfile(
                Kit.PROVIDER,
                "Chat",
                ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://api.example.com/v1",
                Kit.KEY,
            )
        assertThrows<IllegalArgumentException> { AnthropicWire.snapshot(chat, ModelId("m"), Kit.CAPABILITIES, null) }
    }
}
