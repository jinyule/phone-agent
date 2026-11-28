package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ProviderId
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `secrets cannot hide in the url or in headers`() {
        val secret = "sk-test-not-a-real-key"
        listOf(
            "https://user:$secret@api.example.test/v1",
            "https://$secret@api.example.test",
            // Not a valid authority: java.net.URI would quote it in its own error message.
            "https://user:$secret^x@api.example.test/v1",
        ).forEach { url ->
            val inUrl = assertThrows<IllegalArgumentException>(url) { base.copy(baseUrl = url) }
            assertFalse(secret in inUrl.message.orEmpty(), "the error must not echo the url: ${inUrl.message}")
        }
        val accepted =
            listOf(
                "X-Goog-Api-Key",
                "x-portkey-api-key",
                "X-Auth-Token",
                "X-Client-Secret",
                "Set-Cookie",
                "X-Access-Token",
            ).mapNotNull { name ->
                val error = runCatching { base.copy(headers = mapOf(name to secret)) }.exceptionOrNull()
                when {
                    error !is IllegalArgumentException -> "$name accepted"
                    secret in error.message.orEmpty() -> "$name: value echoed in '${error.message}'"
                    else -> null
                }
            }
        assertEquals(emptyList<String>(), accepted, "credential-like header names")
    }

    @Test
    fun `toString masks header values`() {
        val profile = base.copy(headers = mapOf("X-Title" to "PhoneAgent", "OpenAI-Organization" to "org-private"))
        val text = profile.toString()
        assertFalse("org-private" in text || "PhoneAgent" in text, text)
        assertTrue("X-Title" in text && "OpenAI-Organization" in text && "deepseek" in text, text)
    }

    @Test
    fun `plain http is only for local servers`() {
        listOf(
            "http://127.0.0.1:11434",
            "http://localhost:11434/v1",
            "http://10.0.2.2:8080",
            "http://192.168.1.20:11434/v1",
            "http://172.16.0.5",
            "http://169.254.1.1",
            "http://[::1]:11434",
            "http://[fd12:3456::1]:8080",
            "http://desktop.local:11434",
        ).forEach { url -> assertEquals(url, base.copy(baseUrl = url).baseUrl) }
        listOf(
            "http://api.openai.com/v1",
            "http://8.8.8.8",
            "http://172.32.0.1",
            "http://[2001:db8::1]",
            "http://localhost.example.com",
        ).filter { url -> runCatching { base.copy(baseUrl = url) }.isSuccess }
            .let { assertEquals(emptyList<String>(), it, "remote plain-http urls that were accepted") }
    }
}
