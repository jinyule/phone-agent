package dev.phoneagent.app.model

import android.content.Context
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.CredentialStore
import dev.phoneagent.app.model.credential.KeystoreCipher
import dev.phoneagent.app.model.credential.SecretCipher
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsLoad
import dev.phoneagent.app.model.settings.SettingsProblem
import dev.phoneagent.app.model.setup.ModelSetup
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.openai.OpenAiAdapters
import dev.phoneagent.core.model.openai.ProviderProbe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The model settings of the app process: the credential store, the settings file, "test and
 * save", and the current [state] that the UI and the debug injection share.
 *
 * Settings are in `filesDir`, keys in `noBackupFilesDir` (see [CredentialStore] for what happens
 * when settings arrive without their keys). Keys nobody references are deleted only after the
 * settings loaded successfully: while the file is broken, nothing is deleted until the user resets.
 */
internal class ModelServices(
    filesDir: File,
    noBackupDir: File,
    cipher: SecretCipher,
    probeWith: (CredentialResolver) -> ProviderProbe = { OpenAiAdapters(it).probe() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The keys. */
    val credentials: CredentialStore = CredentialStore(File(noBackupDir, CREDENTIALS_DIR), cipher, io)
    private val file = ModelSettingsFile(File(filesDir, ModelSettingsFile.NAME))
    private val setup = ModelSetup(credentials, file, probeWith, io)
    private val current = MutableStateFlow<SettingsState>(SettingsState.Loading)

    /** The settings as last loaded or saved. */
    val state: StateFlow<SettingsState> = current.asStateFlow()

    /** Reads the settings again, and on success deletes the keys they no longer use. */
    suspend fun reload(): SettingsState =
        withContext(io) {
            val next =
                when (val load = file.load()) {
                    SettingsLoad.Empty -> ready(null)
                    is SettingsLoad.Loaded -> ready(load.settings)
                    is SettingsLoad.Broken -> SettingsState.Broken(load.problem)
                }
            current.value = next
            next
        }

    /** "Test and save" of [form] against the current settings; publishes them when saved. */
    suspend fun testAndSave(
        form: ProviderForm,
        onModel: (ModelId) -> Unit = {},
    ): SetupOutcome {
        val outcome = setup.testAndSave(form, (state.value as? SettingsState.Ready)?.settings, onModel)
        if (outcome is SetupOutcome.Saved) current.value = withContext(io) { ready(outcome.settings) }
        return outcome
    }

    /** Deletes the settings and every key: what the broken-settings page offers. */
    suspend fun reset(): SettingsState {
        withContext(io) { file.reset() }
        return reload()
    }

    private fun ready(settings: ModelSettings?): SettingsState.Ready {
        credentials.retainOnly(settings?.refs().orEmpty())
        val keys =
            settings
                ?.providers
                ?.mapNotNull { provider ->
                    (provider.profile.credential as? Credential.ApiKey)?.let {
                        provider.profile.id to
                            credentials.status(it.secretRef)
                    }
                }?.toMap()
                .orEmpty()
        return SettingsState.Ready(settings, keys)
    }

    companion object {
        private const val CREDENTIALS_DIR = "credentials"

        @Volatile
        private var instance: Pair<Context, ModelServices>? = null

        /** The process-wide services (one per application object, so tests get fresh ones). */
        fun of(context: Context): ModelServices {
            val app = context.applicationContext
            return instance?.takeIf { it.first === app }?.second
                ?: synchronized(this) {
                    instance?.takeIf { it.first === app }?.second
                        ?: ModelServices(app.filesDir, app.noBackupFilesDir, KeystoreCipher()).also {
                            instance = app to it
                        }
                }
        }
    }
}

/** The model settings as the UI shows them. */
internal sealed interface SettingsState {
    /** Not read yet. */
    data object Loading : SettingsState

    /**
     * Usable settings, or none yet ([settings] `null`). [keys] is the status of each provider's
     * stored key; anything but [CredentialStatus.PRESENT] means "enter the key again".
     */
    data class Ready(
        val settings: ModelSettings?,
        val keys: Map<ProviderId, CredentialStatus>,
    ) : SettingsState

    /** Settings that cannot be used: a full-page error with a reset, and nothing deleted. */
    data class Broken(
        val problem: SettingsProblem,
    ) : SettingsState
}
