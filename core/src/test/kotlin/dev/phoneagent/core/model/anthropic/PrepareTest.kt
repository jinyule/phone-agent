package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.WireSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `ChatModel.prepare` of the Anthropic adapter: the snapshot of the settings the model was made
 * with (the user's saved ones, not the defaults), with the level clamped and then mapped; settings
 * that do not fit the endpoint and the model are refused when the model is made, since `prepare`
 * never throws.
 */
class PrepareTest {
    private fun model(
        profile: ProviderProfile = Kit.profile(),
        model: ModelId = Kit.MODEL,
        settings: AnthropicSettings? = null,
    ) = if (settings == null) {
        Kit.adapters().model(profile, model, Kit.CAPABILITIES, Kit.blobs())
    } else {
        Kit.adapters().model(profile, model, Kit.CAPABILITIES, Kit.blobs(), settings)
    }

    @Test
    fun `prepare sends the saved settings, a fallback turned off included`() {
        val saved = AnthropicSettings(prefixMismatch = PrefixMismatch.ERROR, serverFallback = false, strictTools = true)
        val wire = model(settings = saved).prepare(ReasoningLevel.HIGH).wire
        assertEquals(saved.options(), wire.options)
        assertFalse(AnthropicWire.OPT_FALLBACKS in wire.options, "the user turned the fallback off")
        val defaults = model().prepare(ReasoningLevel.HIGH).wire
        assertEquals(AnthropicSettings.defaultsFor(Kit.profile(), Kit.MODEL).options(), defaults.options)
        assertTrue(AnthropicWire.OPT_FALLBACKS in defaults.options, "without saved settings, the recommended ones")
    }

    @Test
    fun `prepare clamps the level, then maps it, and asks for one tool call per reply`() {
        val model = model()
        val minimal = model.prepare(ReasoningLevel.MINIMAL)
        assertEquals(ReasoningLevel.LOW, minimal.reasoning, "Claude has no level below low")
        assertEquals(WireSetting(AnthropicWire.EFFORT_FIELD, "low"), minimal.wire.reasoningEffort)
        val none = model.prepare(null)
        assertEquals(null, none.reasoning)
        assertEquals(null, none.wire.reasoningEffort)
        assertEquals(Kit.wire(Kit.profile(), reasoning = ReasoningLevel.HIGH), model.prepare(ReasoningLevel.HIGH).wire)
        assertTrue(none.wire.disableParallelToolCalls)
    }

    @Test
    fun `settings the endpoint or the model cannot take are refused when the model is made`() {
        val official = Kit.profile()
        val gateway = Kit.profile("https://gateway.example.com")
        val opus = AnthropicSettings.defaultsFor(official, AnthropicPresets.MAIN_MODEL)

        fun refused(
            profile: ProviderProfile,
            model: ModelId,
            settings: AnthropicSettings,
        ): String? = assertThrows<ModelError.InvalidRequest> { model(profile, model, settings) }.param
        assertEquals("settings.serverFallback", refused(official, AnthropicPresets.LIGHT_MODEL, opus))
        assertEquals("settings.serverFallback", refused(gateway, Kit.MODEL, AnthropicSettings(serverFallback = true)))
        val mismatch = AnthropicSettings(prefixMismatch = PrefixMismatch.ERROR)
        assertEquals("settings.prefixMismatch", refused(gateway, Kit.MODEL, mismatch))
        val fitted = opus.effectiveFor(official, AnthropicPresets.LIGHT_MODEL)
        val haiku = model(official, AnthropicPresets.LIGHT_MODEL, fitted).prepare(null).wire
        assertEquals(fitted.options(), haiku.options, "settings made to fit the model are taken")
    }

    @Test
    fun `a profile edited after the model was made changes no snapshot`() {
        val live = mutableMapOf("x-client" to "phone-agent")
        val model = model(Kit.profile(headers = live))
        val before = model.prepare(ReasoningLevel.HIGH)
        live["x-client"] = "edited"
        live["x-extra"] = "added"
        assertEquals(mapOf("x-client" to "phone-agent"), before.wire.headers)
        assertEquals(before, model.prepare(ReasoningLevel.HIGH))
    }

    @Test
    fun `capabilities edited after the model was made change neither prepare nor the model's info`() {
        val levels = mutableListOf(ReasoningLevel.LOW, ReasoningLevel.HIGH)
        val verified = mutableSetOf(VerifiedCapability.TOOL_CALL)
        val capabilities = Kit.CAPABILITIES.copy(reasoningLevels = levels, verified = verified)
        val model = Kit.adapters().model(Kit.profile(), Kit.MODEL, capabilities, Kit.blobs())
        val before = model.prepare(ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.HIGH, before.reasoning)
        levels.remove(ReasoningLevel.HIGH)
        verified.clear()
        assertEquals(before, model.prepare(ReasoningLevel.HIGH), "the levels the model was made with")
        assertEquals(listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH), model.info.capabilities.reasoningLevels)
        assertEquals(setOf(VerifiedCapability.TOOL_CALL), model.info.capabilities.verified)
    }
}
