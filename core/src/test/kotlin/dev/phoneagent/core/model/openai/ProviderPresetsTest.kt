package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueSse
import dev.phoneagent.core.model.openai.Kit.json
import dev.phoneagent.core.model.openai.Kit.next
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderPresetsTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun `the ten presets are distinct and make valid profiles`() {
        val all = ProviderPresets.ALL
        assertEquals(
            listOf(
                "openai",
                "deepseek",
                "qwen",
                "kimi",
                "glm",
                "doubao",
                "siliconflow",
                "openrouter",
                "ollama",
                "lmstudio",
            ),
            all.map { it.id },
        )
        for (preset in all) {
            val credential = if (preset.needsKey) Kit.KEY else Credential.None
            val profile = preset.profile(ProviderId(preset.id), credential)
            assertEquals(preset.id, profile.preset)
            assertEquals(preset.baseUrl, profile.baseUrl)
            assertEquals(preset.reasoningField, profile.reasoningField)
            assertTrue(preset.facts.isNotEmpty(), "${preset.id} states its quirks")
            assertSame(preset, ProviderPresets.byId(preset.id))
            assertTrue(preset.officialHosts.isNotEmpty() != preset.baseUrl.startsWith("http://127.0.0.1"), preset.id)
        }
        assertNull(ProviderPresets.byId("nope"))
        assertEquals(ModelProtocol.OPENAI_RESPONSES, ProviderPresets.OPENAI.protocol)
        assertFalse(ProviderPresets.OLLAMA.needsKey || ProviderPresets.LM_STUDIO.needsKey)
        val custom =
            ProviderPresets.OLLAMA.profile(
                ProviderId("lan"),
                Credential.None,
                "http://192.168.1.20:11434/v1",
                "Desk",
            )
        assertEquals("Desk", custom.displayName)
    }

    @Test
    fun `official endpoints are recognized by host`() {
        assertSame(ProviderPresets.DEEPSEEK, ProviderPresets.matchingOfficial(" https://API.deepseek.com/v1 "))
        assertSame(
            ProviderPresets.QWEN,
            ProviderPresets.matchingOfficial("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        )
        assertSame(ProviderPresets.OPENROUTER, ProviderPresets.matchingOfficial("https://openrouter.ai/api/v1"))
        assertNull(ProviderPresets.matchingOfficial("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.matchingOfficial("not a url"))
    }

    @Test
    fun `quirks are validated`() {
        assertThrows<IllegalArgumentException> { ChatQuirks(maxTokensField = "max_output_tokens") }
        assertEquals(ChatQuirks(), ChatQuirks.DEFAULT)
        assertEquals(setOf("max_tokens", "max_completion_tokens"), ChatQuirks.MAX_TOKENS_FIELDS)
    }

    @Test
    fun `a preset's quirks shape the request`() =
        runTest {
            val reply = Kit.sse("""{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""")

            suspend fun sent(
                preset: String?,
                levels: List<ReasoningLevel>? = null,
            ): JsonObject {
                server.enqueueSse(reply)
                val profile = Kit.profile(server.base(), preset = preset)
                val model = Kit.adapters().model(profile, Kit.MODEL, Kit.capabilities(levels = levels), Kit.blobs())
                model.complete(Kit.request(reasoning = ReasoningLevel.MEDIUM, maxOutputTokens = 64))
                return server.next().json()
            }
            val custom = sent(null)
            assertEquals(JsonPrimitive(false), custom["parallel_tool_calls"])
            assertEquals(JsonPrimitive("medium"), custom["reasoning_effort"])
            assertEquals(JsonPrimitive(64), custom["max_tokens"])
            val kimi = sent("kimi")
            assertFalse("parallel_tool_calls" in kimi || "reasoning_effort" in kimi)
            val openRouter = sent("openrouter")
            assertEquals(JsonPrimitive("medium"), (openRouter["reasoning"] as JsonObject)["effort"])
            val unknown = sent("retired-preset")
            assertEquals(custom, unknown)
            val clamped = sent(null, levels = listOf(ReasoningLevel.LOW))
            assertEquals(JsonPrimitive("low"), clamped["reasoning_effort"])
        }
}
