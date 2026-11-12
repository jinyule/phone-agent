package dev.phoneagent.app.model.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.RoleBinding
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.openai.OpenAiAdapters
import dev.phoneagent.core.model.openai.ProbeResult
import dev.phoneagent.core.model.openai.ProbeStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** "Test and save" against a loopback provider with core's real probe: what is written, and when. */
@RunWith(AndroidJUnit4::class)
class ModelSetupTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()
    private val cipher = SoftwareCipher()
    private val files by lazy { temp.newFolder("files") }
    private val noBackup by lazy { temp.newFolder("no_backup") }
    private val services by lazy { ModelServices(files, noBackup, cipher) }
    private val settingsFile get() = File(files, ModelSettingsFile.NAME)
    private val keyFiles get() = File(noBackup, "credentials").listFiles().orEmpty().toList()

    private val form get() =
        ProviderForm
            .start(null)
            .copy(baseUrl = provider.baseUrl, apiKey = KEY, mainModel = "main-model")

    @After
    fun stop() {
        provider.close()
    }

    private fun save(form: ProviderForm): SetupOutcome =
        runBlocking {
            services.reload()
            services.testAndSave(form)
        }

    @Test
    fun aPassingProviderIsSavedWithItsKeyInTheStoreOnly() {
        val outcome = save(form) as SetupOutcome.Saved
        val check = outcome.checks.single()
        assertEquals(ModelId("main-model"), check.model)
        assertEquals(setOf(ModelRole.MAIN), check.roles)
        assertTrue(check.result is ProbeResult.Passed)
        assertEquals(
            listOf("GET /v1/models", "POST /v1/chat/completions"),
            provider.requests.map { "${it.method} ${it.path}" },
        )
        assertTrue(provider.requests.all { it.authorization == "Bearer $KEY" })

        val stored = outcome.settings.providers.single()
        val ref = (stored.profile.credential as Credential.ApiKey).secretRef
        assertTrue(ref.value.startsWith("api-key/custom/"))
        val model = stored.models.single()
        assertEquals(32768, model.capabilities.contextTokens)
        assertEquals(setOf(VerifiedCapability.TOOL_CALL), model.capabilities.verified)
        assertEquals(
            mapOf(ModelRole.MAIN to RoleBinding(ProviderId("custom"), ModelId("main-model"))),
            outcome.settings.roles,
        )

        assertFalse(settingsFile.readText().contains(KEY))
        assertEquals(1, keyFiles.size)
        assertEquals(KEY, runBlocking { services.credentials.secret(Credential.ApiKey(ref), rejected = false) })
        val state = services.state.value as SettingsState.Ready
        assertEquals(outcome.settings, state.settings)
        assertEquals(mapOf(ProviderId("custom") to CredentialStatus.PRESENT), state.keys)
        assertEquals(state, runBlocking { services.reload() })
    }

    @Test
    fun aFailedListingWritesNothingAndSaysWhichStep() {
        provider.listingStatus = 401
        val outcome = save(form) as SetupOutcome.Failed
        val failed = outcome.checks.single().result as ProbeResult.Failed
        assertEquals(ProbeStep.LIST_MODELS, failed.step)
        assertEquals(ModelErrorKind.AUTH, failed.kind)
        assertTrue(failed.message.contains("Invalid API key provided"))
        assertEquals(1, provider.requests.size)
        assertNothingWritten()
    }

    @Test
    fun aFailedTrialWritesNothingAndSaysWhichStep() {
        provider.trialStatus = 404
        val outcome = save(form) as SetupOutcome.Failed
        assertEquals(ProbeStep.TOOL_CALL, (outcome.checks.single().result as ProbeResult.Failed).step)
        assertNothingWritten()
    }

    @Test
    fun everyNamedModelIsCheckedAndGetsItsRole() {
        val outcome =
            save(
                form.copy(lightModel = "small", visionModel = "seeing", visionImages = true),
            ) as SetupOutcome.Saved
        assertEquals(
            listOf("main-model", "small", "seeing"),
            provider.requests.filter { it.method == "POST" }.map { it.model },
        )
        assertEquals(
            listOf(
                setOf(ModelRole.MAIN),
                setOf(ModelRole.LIGHT),
                setOf(ModelRole.VISION),
            ),
            outcome.checks.map {
                it.roles
            },
        )
        val models =
            outcome.settings.providers
                .single()
                .models
                .associate { it.id.value to it.capabilities }
        assertEquals(
            mapOf("main-model" to false, "small" to false, "seeing" to true),
            models.mapValues { it.value.vision },
        )
        // Only main-model is listed: the others get the default context window.
        assertEquals(ProviderForm.DEFAULT_CONTEXT_TOKENS, models.getValue("small").contextTokens)
        assertEquals(RoleBinding(ProviderId("custom"), ModelId("seeing")), outcome.settings.roles[ModelRole.VISION])
    }

    @Test
    fun theFirstFailingModelStopsTheRunAndNothingIsWritten() {
        provider.trialStatus = 200
        val outcome =
            runBlocking {
                services.reload()
                val checked = mutableListOf<ModelId>()
                services
                    .testAndSave(form.copy(lightModel = "small")) {
                        checked += it
                        if (it.value == "small") provider.trialStatus = 400
                    }.also { assertEquals(listOf(ModelId("main-model"), ModelId("small")), checked) }
            } as SetupOutcome.Failed
        assertEquals(listOf(true, false), outcome.checks.map { it.result is ProbeResult.Passed })
        assertNothingWritten()
    }

    @Test
    fun anInvalidFormSendsNothing() {
        val outcome = save(form.copy(mainModel = "")) as SetupOutcome.Invalid
        assertEquals(listOf(FormProblem.MAIN_MODEL_MISSING), outcome.problems)
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
        assertNothingWritten()
    }

    @Test
    fun aKeystoreThatRefusesTheKeyLeavesTheOldSettings() {
        val first = save(form) as SetupOutcome.Saved
        cipher.refuseSeal = true
        val second = save(form.copy(apiKey = "sk-test-second-not-a-real-key")) as SetupOutcome.NotSaved
        assertEquals(SaveFailure.KEY, second.what)
        assertEquals(first.settings, (services.state.value as SettingsState.Ready).settings)
        assertEquals(first.settings, (runBlocking { services.reload() } as SettingsState.Ready).settings)
        assertEquals(1, keyFiles.size)
    }

    @Test
    fun aSettingsWriteThatFailsLeavesTheOldSettingsAndTheNewKeyIsCleanedOnLoad() {
        val first = save(form) as SetupOutcome.Saved
        val oldRef =
            (
                first.settings.providers
                    .single()
                    .profile.credential as Credential.ApiKey
            ).secretRef
        // The temporary file cannot be created: the write fails before anything moves.
        File(files, ModelSettingsFile.NAME + ".tmp").mkdirs()
        val second = save(form.copy(apiKey = "sk-test-second-not-a-real-key")) as SetupOutcome.NotSaved
        assertEquals(SaveFailure.SETTINGS, second.what)
        assertEquals(2, keyFiles.size)
        val reloaded = runBlocking { services.reload() } as SettingsState.Ready
        assertEquals(first.settings, reloaded.settings)
        assertEquals(1, keyFiles.size)
        assertEquals(CredentialStatus.PRESENT, services.credentials.status(oldRef))
    }

    @Test
    fun aNewKeyReplacesTheOldOneOnlyAfterTheSave() {
        val first = save(form) as SetupOutcome.Saved
        val oldRef =
            (
                first.settings.providers
                    .single()
                    .profile.credential as Credential.ApiKey
            ).secretRef
        val second = save(form.copy(apiKey = "sk-test-second-not-a-real-key")) as SetupOutcome.Saved
        val newRef =
            (
                second.settings.providers
                    .single()
                    .profile.credential as Credential.ApiKey
            ).secretRef
        assertTrue(newRef != oldRef)
        assertEquals(CredentialStatus.MISSING, services.credentials.status(oldRef))
        assertEquals(CredentialStatus.PRESENT, services.credentials.status(newRef))
        assertEquals(1, keyFiles.size)
        assertTrue(provider.requests.takeLast(2).all { it.authorization == "Bearer sk-test-second-not-a-real-key" })
    }

    @Test
    fun aBlankKeyKeepsTheStoredOneWhenItIsReadable() {
        val first = save(form) as SetupOutcome.Saved
        val again = save(form.copy(apiKey = "", lightModel = "small")) as SetupOutcome.Saved
        assertEquals(
            first.settings.providers
                .single()
                .profile.credential,
            again.settings.providers
                .single()
                .profile.credential,
        )
        assertTrue(provider.requests.drop(2).all { it.authorization == "Bearer $KEY" })
        cipher.forget()
        val lost = save(form.copy(apiKey = "")) as SetupOutcome.Invalid
        assertEquals(listOf(FormProblem.KEY_MISSING), lost.problems)
    }

    @Test
    fun noAuthenticationSendsNoKeyAndStoresNone() {
        val outcome = save(form.copy(auth = AuthChoice.NONE)) as SetupOutcome.Saved
        assertEquals(
            Credential.None,
            outcome.settings.providers
                .single()
                .profile.credential,
        )
        assertTrue(provider.requests.all { it.authorization == null })
        assertEquals(emptyList<File>(), keyFiles)
        assertEquals(emptyMap<ProviderId, CredentialStatus>(), (services.state.value as SettingsState.Ready).keys)
    }

    @Test
    fun addressesAndHeaderValuesTheHttpClientRefusesAreFormProblemsNotCrashes() {
        // Each cell is collected first (an exception counts as its class), so one run shows them all.
        val addresses =
            listOf("http://127.0.0.1:0/v1", "http://127.0.0.1:65536/v1", "http://127.0.0.1:99999/v1")
                .associateWith { outcomeOrThrown(form.copy(baseUrl = it)) }
        val values =
            listOf("caf\u00e9", "line\nbreak")
                .associate { "header $it" to outcomeOrThrown(form.copy(headers = mapOf("X-Note" to it))) }
        assertEquals(
            addresses.mapValues { SetupOutcome.Invalid(listOf(FormProblem.BASE_URL_INVALID)) } +
                values.mapValues { SetupOutcome.Invalid(listOf(FormProblem.HEADER_INVALID)) },
            addresses + values,
        )
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
        assertNothingWritten()
    }

    @Test
    fun anIllegalArgumentFromTheProbeIsAFormProblemNotACrash() {
        // Core now refuses what OkHttp refuses before any request, so no form reaches this; a resolver
        // stands in for a disagreement between the two, at the point the probe builds its request.
        val unsendable = CredentialResolver { _, _ -> throw IllegalArgumentException("refused like OkHttp refuses") }
        val refusing = ModelServices(files, noBackup, cipher, probeWith = { OpenAiAdapters(unsendable).probe() })
        val outcome =
            runBlocking {
                refusing.reload()
                refusing.testAndSave(form)
            }
        assertEquals(SetupOutcome.Invalid(listOf(FormProblem.NOT_SENDABLE)), outcome)
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
        assertFalse(settingsFile.exists())
        assertEquals(emptyList<File>(), keyFiles)
    }

    private fun outcomeOrThrown(form: ProviderForm): Any =
        runCatching { save(form) }.getOrElse { it.javaClass.simpleName }

    @Test
    fun savingWhatTheFormShowsAgainKeepsEveryCapability() {
        val first =
            save(
                form.copy(
                    contextTokens = 16_000,
                    lightModel = "small",
                    visionModel = "seeing",
                    visionImages = true,
                ),
            ) as SetupOutcome.Saved
        val edited = ProviderForm.of(first.settings)
        val second = save(edited) as SetupOutcome.Saved
        assertEquals(capabilities(first), capabilities(second))
        assertEquals(16_000, edited.contextTokens)
        // The form's value is the main model's; the others keep the listing's, or the default.
        assertEquals(
            mapOf(
                "main-model" to 16_000,
                "small" to ProviderForm.DEFAULT_CONTEXT_TOKENS,
                "seeing" to ProviderForm.DEFAULT_CONTEXT_TOKENS,
            ),
            capabilities(second).mapValues { it.value.contextTokens },
        )
    }

    @Test
    fun aModelTheListingNoLongerNamesKeepsItsStoredContextWindow() {
        provider.listing =
            """{"object":"list","data":[{"id":"main-model","context_length":32768},""" +
            """{"id":"small","context_length":8000}]}"""
        save(form.copy(lightModel = "small")) as SetupOutcome.Saved
        provider.listing = """{"object":"list","data":[{"id":"main-model","context_length":32768}]}"""
        val again = save(ProviderForm.of(services.settingsNow())) as SetupOutcome.Saved
        assertEquals(
            mapOf("main-model" to 32768, "small" to 8000),
            capabilities(again).mapValues { it.value.contextTokens },
        )
    }

    private fun ModelServices.settingsNow() = checkNotNull((state.value as SettingsState.Ready).settings)

    private fun capabilities(saved: SetupOutcome.Saved) =
        saved.settings.providers
            .single()
            .models
            .associate { it.id.value to it.capabilities }

    private fun assertNothingWritten() {
        assertFalse(settingsFile.exists())
        assertEquals(emptyList<File>(), keyFiles)
        assertEquals(SettingsState.Ready(null, emptyMap()), services.state.value)
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
