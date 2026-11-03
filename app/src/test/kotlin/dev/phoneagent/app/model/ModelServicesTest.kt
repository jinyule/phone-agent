package dev.phoneagent.app.model

import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.app.model.settings.SettingsProblemKind
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
