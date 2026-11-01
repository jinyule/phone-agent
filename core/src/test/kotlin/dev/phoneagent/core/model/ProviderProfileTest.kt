package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ProviderId
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderProfileTest {
    private val key = Credential.ApiKey(SecretRef("provider/deepseek"))

    private val base =
        ProviderProfile(
            ProviderId("deepseek"),
            "DeepSeek",
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://api.deepseek.com/v1",
            key,
            preset = "deepseek",
        )

    @Test
    fun `valid profiles round-trip with every credential kind`() {
        val profiles =
            listOf(
                base.copy(
                    reasoningField = "reasoning_content",
                    echoReasoning = true,
                    headers =
                        mapOf("X-Title" to "PhoneAgent"),
                ),
                base.copy(
                    protocol = ModelProtocol.OPENAI_RESPONSES,
                    baseUrl = "https://api.openai.com/v1",
                    credential = Credential.ChatGptPlan(SecretRef("chatgpt")),
                ),
                base.copy(
                    protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                    baseUrl = "http://127.0.0.1:11434",
                    credential = Credential.None,
                ),
            )
        profiles.forEach { assertEquals(it, Json.decodeFromString<ProviderProfile>(Json.encodeToString(it))) }
        assertEquals("""{"type":"api_key","secretRef":"provider/deepseek"}""", Json.encodeToString<Credential>(key))
        assertEquals("chatgpt", SecretRef("chatgpt").toString())
    }

    @Test
    fun `configuration errors fail loudly`() {
        val bad =
            listOf<() -> Any>(
                { base.copy(displayName = " ") },
                { base.copy(baseUrl = "ftp://x") },
                { base.copy(baseUrl = "https://x/v1/") },
                { base.copy(baseUrl = "https://x?y") },
                { base.copy(headers = mapOf("Authorization" to "Bearer sk-test-not-a-real-key")) },
                { base.copy(headers = mapOf("x-api-key" to "sk-test-not-a-real-key")) },
                { base.copy(headers = mapOf("bad header" to "x")) },
                { base.copy(credential = Credential.ChatGptPlan(SecretRef("s"))) },
                { base.copy(protocol = ModelProtocol.ANTHROPIC_MESSAGES, reasoningField = "r") },
                { base.copy(echoReasoning = true) },
                { SecretRef(" ") },
            )
        bad.forEachIndexed { index, make -> assertThrows<IllegalArgumentException>("case $index") { make() } }
    }
}
