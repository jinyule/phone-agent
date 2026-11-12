package dev.phoneagent.app.model

import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.app.model.settings.SettingsProblem
import dev.phoneagent.app.model.settings.SettingsProblemKind
import dev.phoneagent.app.model.setup.AuthChoice
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Loading the settings with their keys: what is shown, and what is (not) deleted. */
class ModelServicesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = SoftwareCipher()
    private val files by lazy { temp.newFolder("files") }
    private val noBackup by lazy { temp.newFolder("no_backup") }
    private val services by lazy { ModelServices(files, noBackup, cipher) }
    private val keyDir get() = File(noBackup, "credentials")

    private fun write(settings: ModelSettings) =
        File(files, ModelSettingsFile.NAME).writeText(Json.encodeToString(serializer<ModelSettings>(), settings))

    private fun reload() = runBlocking { services.reload() }

    @Test
    fun noFileIsNoSettingsYet() {
        assertEquals(SettingsState.Loading, services.state.value)
        assertEquals(SettingsState.Ready(null, emptyMap()), reload())
        assertEquals(SettingsState.Ready(null, emptyMap()), services.state.value)
    }

    @Test
    fun settingsWhoseKeyIsMissingAskForTheKeyInsteadOfFailingThePage() {
        // What a restore of filesDir without noBackupFilesDir (or a lost key file) looks like.
        write(SettingsFixtures.settings())
        val state = reload() as SettingsState.Ready
        assertEquals(SettingsFixtures.settings(), state.settings)
        assertEquals(mapOf(SettingsFixtures.DEEPSEEK to CredentialStatus.MISSING), state.keys)
    }

    @Test
    fun settingsWhoseKeyCannotBeDecryptedAskForTheKeyToo() {
        write(SettingsFixtures.settings())
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        assertEquals(
            mapOf(SettingsFixtures.DEEPSEEK to CredentialStatus.PRESENT),
            (reload() as SettingsState.Ready).keys,
        )
        // A restore onto another device, or a cleared Keystore.
        cipher.forget()
        assertEquals(
            mapOf(SettingsFixtures.DEEPSEEK to CredentialStatus.UNREADABLE),
            (reload() as SettingsState.Ready).keys,
        )
    }

    @Test
    fun aBrokenFileDeletesNoKeyUntilTheUserResets() {
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        services.credentials.put(SecretRef("api-key/other/1"), "sk-test-other-not-a-real-key")
        File(files, ModelSettingsFile.NAME).writeText("{ not json")
        val broken = reload() as SettingsState.Broken
        assertEquals(SettingsProblemKind.MALFORMED, broken.problem.kind)
        assertEquals(2, keyDir.listFiles()!!.size)
        assertEquals(2, (reload() as SettingsState.Broken).let { keyDir.listFiles()!!.size })

        assertEquals(SettingsState.Ready(null, emptyMap()), runBlocking { services.reset() })
        assertEquals(emptyList<File>(), keyDir.listFiles()!!.toList())
        assertTrue(!File(files, ModelSettingsFile.NAME).exists())
    }

    @Test
    fun aBrokenFileIsNotOverwrittenBySavingUntilTheUserResets() {
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        val file = File(files, ModelSettingsFile.NAME)
        file.writeText("{ not json")
        reload() as SettingsState.Broken
        FakeProvider().use { provider ->
            val custom = ProviderForm.start(null)
            val form = custom.copy(baseUrl = provider.baseUrl, auth = AuthChoice.NONE, mainModel = "main-model")
            val outcome = runBlocking { services.testAndSave(form) }
            // Collected first, so a run that saves shows everything it changed.
            assertEquals(
                listOf<Any>(SetupOutcome.SettingsBroken, "{ not json", CredentialStatus.PRESENT, 0),
                listOf(
                    outcome,
                    file.readText(),
                    services.credentials.status(SettingsFixtures.REF),
                    provider.requests.size,
                ),
            )
            runBlocking { services.reset() }
            assertTrue(runBlocking { services.testAndSave(form) } is SetupOutcome.Saved)
        }
    }

    @Test
    fun aSaveBeforeAnyLoadFindsTheBrokenFileFirst() {
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        val file = File(files, ModelSettingsFile.NAME)
        file.writeText("{ not json")
        FakeProvider().use { provider ->
            val custom = ProviderForm.start(null)
            val form = custom.copy(baseUrl = provider.baseUrl, auth = AuthChoice.NONE, mainModel = "main-model")
            val outcome = runBlocking { services.testAndSave(form) }
            assertEquals(
                listOf<Any>(SetupOutcome.SettingsBroken, "{ not json", CredentialStatus.PRESENT, 0),
                listOf(
                    outcome,
                    file.readText(),
                    services.credentials.status(SettingsFixtures.REF),
                    provider.requests.size,
                ),
            )
            assertTrue(services.state.value is SettingsState.Broken)
        }
    }

    /**
     * A key and the settings that use it, then [make] spoils the settings file; then a save, after a
     * reload or as the first thing; then a reset. What the services show and keep at each step.
     */
    private fun saveOverSpoiledFile(
        make: (File) -> Unit,
        reloadFirst: Boolean,
        provider: FakeProvider,
    ): List<Any> {
        val root = temp.newFolder()
        val services =
            ModelServices(File(root, "files").also { it.mkdir() }, File(root, "no_backup").also { it.mkdir() }, cipher)
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        make(File(root, "files/${ModelSettingsFile.NAME}"))
        val custom = ProviderForm.start(null)
        val form = custom.copy(baseUrl = provider.baseUrl, auth = AuthChoice.NONE, mainModel = "main-model")
        val sentBefore = provider.requests.size
        val outcome =
            runBlocking {
                if (reloadFirst) services.reload()
                services.testAndSave(form)
            }
        val spoiled =
            listOf(
                services.state.value,
                outcome,
                services.credentials.status(SettingsFixtures.REF),
                provider.requests.size - sentBefore,
            )
        val reset = runBlocking { services.reset() }
        return spoiled + listOf(reset, services.credentials.status(SettingsFixtures.REF))
    }

    @Test
    fun aSettingsFileThatCannotBeReadKeepsItsKeysAndRefusesSavesUntilTheUserResets() {
        val stored = Json.encodeToString(serializer<ModelSettings>(), SettingsFixtures.settings())
        val shapes =
            mapOf<String, (File) -> Unit>(
                "file without read permission" to { SettingsFixtures.unreadable(it, stored) },
                "directory in its place" to { SettingsFixtures.directoryAt(it) },
            )
        // Collected first, so each cell shows everything a run that ignores the spoiled file changes.
        val seen =
            FakeProvider().use { provider ->
                shapes.entries
                    .flatMap { (shape, make) ->
                        listOf(true, false).map { reloadFirst ->
                            val cell = "$shape, ${if (reloadFirst) "reload" else "save"} first"
                            cell to saveOverSpoiledFile(make, reloadFirst, provider)
                        }
                    }.toMap()
            }
        val broken = SettingsState.Broken(SettingsProblem(SettingsProblemKind.UNREADABLE_FILE, null))
        assertEquals(4, seen.size)
        assertEquals(
            seen.keys.associateWith {
                listOf(
                    broken,
                    SetupOutcome.SettingsBroken,
                    CredentialStatus.PRESENT,
                    0,
                    SettingsState.Ready(null, emptyMap()),
                    CredentialStatus.MISSING,
                )
            },
            seen,
        )
    }

    @Test
    fun aMissingFileIsNoSettingsAndTheKeysNobodyUsesGo() {
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        assertEquals(
            listOf(SettingsState.Ready(null, emptyMap()), CredentialStatus.MISSING),
            listOf(reload(), services.credentials.status(SettingsFixtures.REF)),
        )
    }

    @Test
    fun aSuccessfulLoadDeletesKeysNoProviderUses() {
        write(SettingsFixtures.settings())
        services.credentials.put(SettingsFixtures.REF, "sk-test-not-a-real-key")
        val orphan = SecretRef("api-key/deepseek/ffffffff")
        services.credentials.put(orphan, "sk-test-orphan-not-a-real-key")
        reload()
        assertEquals(CredentialStatus.PRESENT, services.credentials.status(SettingsFixtures.REF))
        assertEquals(CredentialStatus.MISSING, services.credentials.status(orphan))
    }
}
