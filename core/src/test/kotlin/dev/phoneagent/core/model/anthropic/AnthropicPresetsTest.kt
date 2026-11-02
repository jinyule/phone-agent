package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.PromptCaching
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.ThinkingBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The built-in Anthropic preset: profile, known models, per-role levels and the official endpoint. */
class AnthropicPresetsTest {
    @Test
    fun `the preset profile is the Claude API over Messages`() {
        val profile = AnthropicPresets.profile(Kit.PROVIDER, Kit.KEY)
        assertEquals(ModelProtocol.ANTHROPIC_MESSAGES, profile.protocol)
        assertEquals("https://api.anthropic.com", profile.baseUrl)
        assertEquals(AnthropicPresets.ID, profile.preset)
        assertEquals("Anthropic", profile.displayName)
        assertEquals(Kit.KEY, profile.credential)
        assertEquals("Claude", AnthropicPresets.profile(Kit.PROVIDER, Kit.KEY, displayName = "Claude").displayName)
    }

    @Test
    fun `the three 5_5 models are known, anything else must be probed`() {
        val known = listOf(AnthropicPresets.MAIN_MODEL, AnthropicPresets.SONNET_MODEL, AnthropicPresets.LIGHT_MODEL)
        for (model in known) {
            val capabilities = AnthropicPresets.capabilities(model)!!
            assertTrue(capabilities.tools && capabilities.vision, "$model")
            assertEquals(PromptCaching.EXPLICIT, capabilities.promptCaching)
            assertEquals(ThinkingBinding.PREFIX_BOUND, capabilities.thinkingBinding)
            val levels = capabilities.reasoningLevels!!
            assertEquals(ReasoningLevel.LOW, levels.first())
            assertEquals(ReasoningLevel.MAX, levels.last())
            assertFalse(ReasoningLevel.MINIMAL in levels, "effort has no minimal")
        }
        assertNull(AnthropicPresets.capabilities(ModelId("gpt-6-luna")))
        assertNull(AnthropicPresets.capabilities(ModelId("claude-opus-4-1")))
    }

    @Test
    fun `levels are explicit per role`() {
        assertEquals(ReasoningLevel.HIGH, AnthropicPresets.defaultReasoning(ModelRole.MAIN))
        assertEquals(ReasoningLevel.HIGH, AnthropicPresets.defaultReasoning(ModelRole.VISION))
        assertEquals(ReasoningLevel.LOW, AnthropicPresets.defaultReasoning(ModelRole.LIGHT))
    }

    @Test
    fun `only https api_anthropic_com is official, and Haiku has no server fallback`() {
        assertTrue(AnthropicPresets.isOfficial("https://api.anthropic.com"))
        assertTrue(AnthropicPresets.isOfficial("https://api.anthropic.com/"))
        assertFalse(AnthropicPresets.isOfficial("http://api.anthropic.com"))
        assertFalse(AnthropicPresets.isOfficial("https://api.anthropic.com.example.org"))
        assertFalse(AnthropicPresets.isOfficial("http://127.0.0.1:8080"))
        assertFalse(AnthropicPresets.isOfficial("not a url"))
        assertTrue(AnthropicPresets.hasServerFallback(AnthropicPresets.MAIN_MODEL))
        assertTrue(AnthropicPresets.hasServerFallback(AnthropicPresets.SONNET_MODEL))
        assertFalse(AnthropicPresets.hasServerFallback(AnthropicPresets.LIGHT_MODEL))
    }
}
