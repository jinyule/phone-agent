package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.session.DecodedLine
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionRecord
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** B1: everything that shapes the wire request besides the neutral content is frozen into the log. */
class WireProfileTest {
    private val codec = SessionCodec()

    private val profile =
        ProviderProfile(
            ProviderId("deepseek"),
            "DeepSeek",
            ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://api.deepseek.com/v1",
            Credential.ApiKey(SecretRef("provider/deepseek")),
            reasoningField = "reasoning_content",
        )

    private fun header(wire: WireProfile) =
        RequestHeader(
            provider = profile.id,
            model = ModelId("deepseek-chat"),
            protocol = profile.protocol,
            wire = wire,
            system = listOf(PromptBlock("identity", "You operate a phone.")),
            tools = emptyList(),
            cache = CacheLayout.NONE,
            contextEdits = ContextEditPolicy.ClientSide,
            maxOutputTokens = 4096,
            reasoning = ReasoningLevel.HIGH,
        )

    private fun line(wire: WireProfile) = codec.encode(SessionRecord(1, 0, header(wire)))

    private val base = profile.wire(mappingVersion = 1)

    @Test
    fun `the profile part of the snapshot is the non-secret wire settings of the profile`() {
        assertEquals(
            WireProfile(
                mappingVersion = 1,
                credential = CredentialKind.API_KEY,
                reasoningField = "reasoning_content",
            ),
            base,
        )
        assertEquals(CredentialKind.CHATGPT_PLAN, Credential.ChatGptPlan(SecretRef("s")).kind)
        assertEquals(CredentialKind.NONE, Credential.None.kind)
        val text = line(base)
        assertFalse("provider/deepseek" in text, "the secret reference is not part of the wire: $text")
        assertFalse("api.deepseek.com" in text, "the endpoint does not shape the request content: $text")
    }

    @Test
    fun `profiles that differ only in wire settings write different headers`() {
        val variants =
            listOf(
                profile,
                profile.copy(echoReasoning = true),
                profile.copy(reasoningField = null),
                profile.copy(headers = mapOf("X-Title" to "PhoneAgent")),
                profile.copy(headers = mapOf("X-Title" to "Other")),
                profile.copy(credential = Credential.None),
            )
        val lines = variants.map { line(it.wire(mappingVersion = 1)) }
        assertEquals(
            lines.size,
            lines.toSet().size,
            "every wire difference shows in the log:\n${lines.joinToString("\n")}",
        )
        assertEquals(line(base), line(profile.copy(baseUrl = "https://other.example/v1", displayName = "X").wire(1)))
    }

    @Test
    fun `every snapshot field changes the header line and survives the round trip`() {
        val variants =
            mapOf(
                "mappingVersion" to base.copy(mappingVersion = 2),
                "credential" to base.copy(credential = CredentialKind.NONE),
                "headers" to base.copy(headers = mapOf("X-Title" to "PhoneAgent")),
                "images" to base.copy(images = ImageMode.PLACEHOLDER),
                "reasoningField" to base.copy(reasoningField = "reasoning"),
                "echoReasoning" to base.copy(echoReasoning = true),
                "reasoningEffort" to base.copy(reasoningEffort = WireSetting("reasoning_effort", "high")),
                "maxTokensField" to base.copy(maxTokensField = "max_completion_tokens"),
                "parallelToolCalls" to base.copy(parallelToolCalls = true),
                "streamOptions" to base.copy(streamOptions = true),
                "includeEncryptedReasoning" to base.copy(includeEncryptedReasoning = true),
                "recoverLeakedToolCalls" to base.copy(recoverLeakedToolCalls = true),
                "options" to base.copy(options = mapOf("tool_choice" to "auto")),
            )
        assertEquals(
            serializer<WireProfile>().descriptor.elementNames.toSet(),
            variants.keys,
            "each field has a variant",
        )
        val baseLine = line(base)
        variants.forEach { (field, wire) ->
            val changed = line(wire)
            assertTrue(changed != baseLine, "$field does not reach the log")
            val decoded = codec.decode(changed, version = 1, lineNumber = 2)
            assertEquals(header(wire), (decoded as DecodedLine.Event).record.event, field)
        }
    }

    @Test
    fun `the request carries the snapshot the header recorded`() {
        val wire = base.copy(reasoningEffort = WireSetting("reasoning_effort", "high"), maxTokensField = "max_tokens")
        val request = header(wire).toRequest(emptyList())
        assertEquals(wire, request.wire)
        assertEquals(request, Json.decodeFromString<ChatRequest>(Json.encodeToString(request)))
    }

    @Test
    fun `snapshots are validated where they are built and read`() {
        val bad =
            listOf<() -> Any>(
                { base.copy(mappingVersion = 0) },
                { base.copy(reasoningField = null, echoReasoning = true) },
                { base.copy(reasoningField = " ") },
                { base.copy(maxTokensField = "") },
                { base.copy(reasoningEffort = WireSetting("reasoning_effort", " ")) },
                { WireSetting(" ", "high") },
                { base.copy(headers = mapOf("Authorization" to "Bearer sk-test-not-a-real-key")) },
                { base.copy(headers = mapOf("X-Goog-Api-Key" to "sk-test-not-a-real-key")) },
                { base.copy(headers = mapOf("bad header" to "x")) },
                { base.copy(options = mapOf("Bad Key" to "x")) },
            )
        val accepted = bad.withIndex().filter { (_, make) -> runCatching { make() }.isSuccess }.map { it.index }
        assertEquals(emptyList<Int>(), accepted, "cases that were accepted")
        val secret = "sk-test-not-a-real-key"
        val error =
            assertThrows<IllegalArgumentException> { base.copy(headers = mapOf("X-Api-Key" to secret)) }
        assertFalse(secret in error.message.orEmpty())
        assertFalse("org-1" in base.copy(headers = mapOf("OpenAI-Organization" to "org-1")).toString())
    }

    @Test
    fun `reasoning levels are clamped to what the model supports`() {
        val supported = listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.HIGH, ReasoningLevel.MAX.clampTo(supported), "down to the highest supported")
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.MINIMAL.clampTo(supported), "up to the lowest supported")
        assertEquals(ReasoningLevel.MEDIUM, ReasoningLevel.MEDIUM.clampTo(supported))
        assertEquals(ReasoningLevel.LOW, ReasoningLevel.MEDIUM.clampTo(listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH)))
        assertEquals(ReasoningLevel.XHIGH, ReasoningLevel.XHIGH.clampTo(null), "unknown support: unchanged")
        assertEquals(null, ReasoningLevel.HIGH.clampTo(emptyList()), "nothing selectable: none sent")
    }
}
