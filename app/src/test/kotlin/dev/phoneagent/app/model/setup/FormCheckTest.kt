package dev.phoneagent.app.model.setup

import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.SecretRef
import dev.phoneagent.core.model.openai.ProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormCheckTest {
    private val deepseek = ProviderForm.start(ProviderPresets.DEEPSEEK).copy(apiKey = KEY, mainModel = "deepseek-chat")
    private val custom =
        ProviderForm
            .start(
                null,
            ).copy(baseUrl = "http://192.168.1.5:11434/v1", auth = AuthChoice.NONE, mainModel = "qwen3:8b")

    private fun problems(
        form: ProviderForm,
        hasStoredKey: Boolean = false,
    ) = FormCheck.problems(form, hasStoredKey)

    @Test
    fun presetsThatNeedNoKeyStartWithoutAuthenticationAndOthersWithAKey() {
        for (preset in ProviderPresets.ALL) {
            val start = ProviderForm.start(preset)
            assertEquals(preset.id, if (preset.needsKey) AuthChoice.API_KEY else AuthChoice.NONE, start.auth)
            assertEquals(preset.baseUrl, start.baseUrl)
            assertEquals(preset.protocol, start.effectiveProtocol)
        }
        assertEquals(AuthChoice.NONE, ProviderForm.start(ProviderPresets.OLLAMA).auth)
        assertEquals(AuthChoice.NONE, ProviderForm.start(ProviderPresets.LM_STUDIO).auth)
        assertEquals(AuthChoice.API_KEY, ProviderForm.start(null).auth)
    }

    @Test
    fun validFormsHaveNoProblems() {
        assertEquals(emptyList<FormProblem>(), problems(deepseek))
        assertEquals(emptyList<FormProblem>(), problems(custom))
    }

    @Test
    fun aKeyBelowEightCharactersGetsTheShortKeyHint() {
        assertEquals(listOf(FormHint.ShortKey), FormCheck.hints(deepseek.copy(apiKey = "1234567")))
        assertEquals(listOf(FormHint.ShortKey), FormCheck.hints(deepseek.copy(apiKey = " x ")))
        assertEquals(emptyList<FormHint>(), FormCheck.hints(deepseek.copy(apiKey = "12345678")))
        assertEquals(emptyList<FormHint>(), FormCheck.hints(deepseek.copy(apiKey = "")))
        assertEquals(emptyList<FormHint>(), FormCheck.hints(deepseek.copy(apiKey = "x", auth = AuthChoice.NONE)))
    }

    @Test
    fun aCustomFormOnAnOfficialAddressGetsThePresetHint() {
        val official = custom.copy(baseUrl = "https://api.deepseek.com", auth = AuthChoice.API_KEY, apiKey = KEY)
        assertEquals(listOf(FormHint.UsePreset(ProviderPresets.DEEPSEEK)), FormCheck.hints(official))
        assertEquals(
            listOf(FormHint.UsePreset(ProviderPresets.KIMI)),
            FormCheck.hints(official.copy(baseUrl = "https://api.moonshot.ai/v1")),
        )
        assertEquals(emptyList<FormHint>(), FormCheck.hints(deepseek))
        assertEquals(emptyList<FormHint>(), FormCheck.hints(custom))
    }

    @Test
    fun aKeyIsNeededUnlessOneIsStored() {
        val blank = deepseek.copy(apiKey = "  ")
        assertEquals(listOf(FormProblem.KEY_MISSING), problems(blank))
        assertEquals(emptyList<FormProblem>(), problems(blank, hasStoredKey = true))
        assertEquals(listOf(FormProblem.KEY_NOT_HEADER_SAFE), problems(deepseek.copy(apiKey = "sk-test-é-not-real")))
        assertEquals(listOf(FormProblem.KEY_NOT_HEADER_SAFE), problems(deepseek.copy(apiKey = "sk-test\tnot-real")))
    }

    @Test
    fun modelsAreCheckedAsModelIds() {
        assertEquals(listOf(FormProblem.MAIN_MODEL_MISSING), problems(deepseek.copy(mainModel = " ")))
        assertEquals(listOf(FormProblem.MODEL_ID_INVALID), problems(deepseek.copy(lightModel = "x".repeat(257))))
        assertEquals(
            listOf("deepseek-chat", "small"),
            deepseek.copy(lightModel = " small ", visionModel = "deepseek-chat").models,
        )
    }

    @Test
    fun addressesAndHeadersAreCheckedLikeProfiles() {
        assertEquals(listOf(FormProblem.BASE_URL_INVALID), problems(custom.copy(baseUrl = "http://example.com/v1")))
        assertEquals(listOf(FormProblem.BASE_URL_INVALID), problems(custom.copy(baseUrl = "https://example.com/v1/")))
        assertEquals(listOf(FormProblem.BASE_URL_INVALID), problems(deepseek.copy(baseUrl = "")))
        assertEquals(listOf(FormProblem.HEADER_INVALID), problems(custom.copy(headers = mapOf("X-Api-Key" to "v"))))
        assertEquals(emptyList<FormProblem>(), problems(custom.copy(headers = mapOf("X-Org" to "team"))))
    }

    @Test
    fun addressesAndHeaderValuesTheHttpClientRefusesAreProblems() {
        // Every cell is collected first, so one run shows each that is let through.
        val addresses =
            listOf(
                "http://127.0.0.1:0/v1",
                "http://127.0.0.1:65536/v1",
                "http://127.0.0.1:99999/v1",
                "https://example.com:99999/v1",
            ).associateWith { problems(custom.copy(baseUrl = it)) }
        val values =
            listOf("caf\u00e9", "line\nbreak", "\u0000", "\u007f")
                .associate { "header $it" to problems(custom.copy(headers = mapOf("X-Note" to it))) }
        assertEquals(
            addresses.mapValues { listOf(FormProblem.BASE_URL_INVALID) } +
                values.mapValues { listOf(FormProblem.HEADER_INVALID) },
            addresses + values,
        )
        assertEquals(emptyList<FormProblem>(), problems(custom.copy(baseUrl = "http://127.0.0.1:65535/v1")))
        assertEquals(emptyList<FormProblem>(), problems(custom.copy(headers = mapOf("X-Note" to "a\tb ~"))))
    }

    @Test
    fun anotherPresetDropsTheTypedKey() {
        val kimi = deepseek.switchedTo(ProviderPresets.KIMI)
        assertEquals(ProviderPresets.KIMI.baseUrl, kimi.baseUrl)
        assertEquals("", kimi.apiKey)
        assertEquals("deepseek-chat", kimi.mainModel)
        // Custom keeps the address, and with it the key.
        assertEquals(KEY, deepseek.switchedTo(null).apiKey)
        assertEquals(KEY, deepseek.switchedTo(ProviderPresets.DEEPSEEK).apiKey)
    }

    @Test
    fun theUsePresetHintKeepsTheTypedKey() {
        val official = custom.copy(baseUrl = "https://api.deepseek.com", auth = AuthChoice.API_KEY, apiKey = KEY)
        val hint = FormCheck.hints(official).single() as FormHint.UsePreset
        val switched = official.switchedTo(hint.preset)
        assertEquals("deepseek", switched.presetId)
        assertEquals(KEY, switched.apiKey)
    }

    @Test
    fun otherProblems() {
        assertEquals(listOf(FormProblem.UNKNOWN_PRESET), problems(deepseek.copy(presetId = "nope")))
        assertEquals(
            listOf(FormProblem.PROTOCOL_UNSUPPORTED),
            problems(custom.copy(protocol = ModelProtocol.ANTHROPIC_MESSAGES)),
        )
        assertEquals(listOf(FormProblem.CONTEXT_INVALID), problems(custom.copy(contextTokens = 0)))
        assertEquals(listOf(FormProblem.VISION_NEEDS_IMAGES), problems(custom.copy(visionModel = "llava")))
        assertEquals(emptyList<FormProblem>(), problems(custom.copy(visionModel = "llava", visionImages = true)))
    }

    @Test
    fun profilesComeFromThePresetOrTheCustomFields() {
        val ref = SecretRef("api-key/deepseek/1")
        val preset = deepseek.copy(baseUrl = " https://api.deepseek.com ").profile(Credential.ApiKey(ref))
        assertEquals(ProviderPresets.DEEPSEEK.profile(preset.id, Credential.ApiKey(ref)), preset)
        val local =
            custom
                .copy(
                    protocol = ModelProtocol.OPENAI_RESPONSES,
                    headers = mapOf("X-Org" to "team"),
                ).profile(Credential.None)
        assertEquals("custom", local.id.value)
        assertEquals("192.168.1.5", local.displayName)
        assertEquals(ModelProtocol.OPENAI_RESPONSES, local.protocol)
        assertEquals(mapOf("X-Org" to "team"), local.headers)
        assertEquals(null, local.preset)
    }

    @Test
    fun theKeyIsNotInToString() {
        assertFalse(deepseek.toString().contains(KEY))
        assertTrue(deepseek.toString().contains("<${KEY.length} chars>"))
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
