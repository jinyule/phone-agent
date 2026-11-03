package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * [AnthropicWire.snapshot] is the pure function "profile + model + capabilities + level → complete
 * [WireProfile]": the same inputs always give the same snapshot, and the adapter sends every
 * snapshot it produces. `complete()` itself never makes one; it reads `request.wire` only.
 */
class WireSnapshotTest {
    private val profiles =
        listOf(
            Kit.profile(),
            Kit.profile("https://gateway.example.com"),
            Kit.profile("http://127.0.0.1:8080", credential = Credential.None, headers = mapOf("X-Route" to "a")),
        )
    private val models = listOf(AnthropicPresets.MAIN_MODEL, AnthropicPresets.LIGHT_MODEL, ModelId("gpt-6-luna"))
    private val levels = listOf<ReasoningLevel?>(null) + ReasoningLevel.entries
    private val capabilities = listOf(Kit.CAPABILITIES, Kit.CAPABILITIES.copy(vision = false))

    private data class Inputs(
        val profile: ProviderProfile,
        val model: ModelId,
        val capabilities: ModelCapabilities,
        val level: ReasoningLevel?,
    ) {
        fun snapshot(): WireProfile = AnthropicWire.snapshot(profile, model, capabilities, level)
    }

    private val all =
        profiles.flatMap { profile ->
            models.flatMap { model ->
                capabilities.flatMap { caps -> levels.map { Inputs(profile, model, caps, it) } }
            }
        }

    @Test
    fun `the same inputs give the same snapshot, down to its serialized form`() {
        for (inputs in all) {
            val first = inputs.snapshot()
            val second = inputs.snapshot()
            assertEquals(first, second, "$inputs")
            assertEquals(
                Json.encodeToString(WireProfile.serializer(), first),
                Json.encodeToString(WireProfile.serializer(), second),
                "$inputs",
            )
        }
    }

    @Test
    fun `only wire inputs count, not the profile's name or id`() {
        val profile = Kit.profile()
        val renamed = profile.copy(id = ProviderId("other"), displayName = "另一个")
        val model = AnthropicPresets.MAIN_MODEL
        assertEquals(
            AnthropicWire.snapshot(profile, model, Kit.CAPABILITIES, ReasoningLevel.HIGH),
            AnthropicWire.snapshot(renamed, model, Kit.CAPABILITIES, ReasoningLevel.HIGH),
        )
        assertNotEquals(
            AnthropicWire.snapshot(profile, model, Kit.CAPABILITIES, ReasoningLevel.HIGH),
            AnthropicWire.snapshot(profile, model, Kit.CAPABILITIES, ReasoningLevel.LOW),
            "the level is an input",
        )
    }

    @Test
    fun `every produced snapshot is honored, with parallel calls off by mapping rule 1`() {
        val noParallel =
            buildJsonObject {
                put("type", "auto")
                put("disable_parallel_tool_use", true)
            }
        for (inputs in all) {
            val wire = inputs.snapshot()
            assertEquals(AnthropicWire.MAPPING_VERSION, wire.mappingVersion)
            assertEquals(inputs.profile.credential.kind, wire.credential)
            assertEquals(if (inputs.capabilities.vision) ImageMode.INLINE else ImageMode.PLACEHOLDER, wire.images)
            // Mapping version 1 always sends disable_parallel_tool_use; the Chat Completions field stays off.
            assertFalse(wire.parallelToolCalls || wire.streamOptions || wire.includeEncryptedReasoning, "$inputs")
            val request = Kit.request(reasoning = inputs.level, wire = wire)
            val rules = WireRules.of(request, inputs.profile.credential.kind)
            val body = MessagesWire(inputs.profile.id, inputs.model).body(request, rules, ImageData.NONE)
            assertEquals(noParallel, body["tool_choice"], "$inputs")
            assertFalse("parallel_tool_calls" in body, "$inputs")
            assertEquals(inputs.level?.let { JsonPrimitive(AnthropicWire.effortOf(it)) }, effortOf(body), "$inputs")
        }
    }

    @Test
    fun `settings the model or the endpoint cannot take are refused when the snapshot is made`() {
        val official = Kit.profile()
        val gateway = Kit.profile("https://gateway.example.com")
        val opusSettings = AnthropicSettings.defaultsFor(official, AnthropicPresets.MAIN_MODEL)

        fun refused(
            profile: ProviderProfile,
            model: ModelId,
            settings: AnthropicSettings,
        ): String? =
            assertThrows<ModelError.InvalidRequest> {
                AnthropicWire.snapshot(profile, model, Kit.CAPABILITIES, ReasoningLevel.LOW, settings)
            }.param
        // Claude Haiku 5.5 has no server-side fallback (a `fallbacks` field is a 400 there).
        assertEquals("settings.serverFallback", refused(official, AnthropicPresets.LIGHT_MODEL, opusSettings))
        assertEquals("settings.serverFallback", refused(official, ModelId("claude-unknown-9"), opusSettings))
        // A gateway gets no Claude-API-only beta, also when the profile asks for one.
        assertEquals("settings.serverFallback", refused(gateway, Kit.MODEL, AnthropicSettings(serverFallback = true)))
        val mismatch = AnthropicSettings(prefixMismatch = PrefixMismatch.ERROR)
        assertEquals("settings.prefixMismatch", refused(gateway, Kit.MODEL, mismatch))
        assertEquals(
            "settings.serverFallback",
            refused(gateway, Kit.MODEL, mismatch.copy(serverFallback = true)),
        )
    }

    @Test
    fun `the Claude API host on another port or path gets no Claude API beta in its snapshot`() {
        for (baseUrl in listOf("https://api.anthropic.com:8443", "https://api.anthropic.com/proxy")) {
            val other = Kit.profile(baseUrl)
            val defaults = AnthropicSettings.defaultsFor(other, AnthropicPresets.MAIN_MODEL)
            assertEquals(AnthropicSettings(), defaults, "$baseUrl: no fallback, no prefix mismatch behavior")
            val error =
                assertThrows<ModelError.InvalidRequest> {
                    AnthropicWire.snapshot(
                        other,
                        Kit.MODEL,
                        Kit.CAPABILITIES,
                        null,
                        AnthropicSettings(serverFallback = true),
                    )
                }
            assertEquals("settings.serverFallback", error.param, baseUrl)
        }
    }

    @Test
    fun `saved settings made to fit each model are what the snapshot records`() {
        val official = Kit.profile()
        val saved = AnthropicSettings(prefixMismatch = PrefixMismatch.ERROR, serverFallback = true, strictTools = true)
        assertEquals(saved, saved.effectiveFor(official, AnthropicPresets.MAIN_MODEL), "Opus takes everything")
        val haiku = saved.effectiveFor(official, AnthropicPresets.LIGHT_MODEL)
        assertEquals(saved.copy(serverFallback = false), haiku, "Haiku has no fallback")
        val gateway = saved.effectiveFor(Kit.profile("https://gateway.example.com"), AnthropicPresets.MAIN_MODEL)
        assertEquals(saved.copy(serverFallback = false, prefixMismatch = null), gateway, "no Claude API betas")
        val wire = AnthropicWire.snapshot(official, AnthropicPresets.LIGHT_MODEL, Kit.CAPABILITIES, null, haiku)
        assertEquals(haiku.options(), wire.options)
        assertFalse(AnthropicWire.OPT_FALLBACKS in wire.options, "the snapshot records what is sent")
        assertEquals(
            AnthropicSettings.defaultsFor(official, AnthropicPresets.LIGHT_MODEL),
            AnthropicSettings(prefixMismatch = PrefixMismatch.DROP_BLOCK),
        )
    }

    private fun effortOf(body: JsonObject) = body.obj("output_config")?.get("effort")
}
