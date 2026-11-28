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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The model settings of the app process: the credential store, the settings file, "test and
 * save", and the current [state] that the UI and the debug injection share.
 *
 * Settings are in `filesDir`, keys in `noBackupFilesDir` (see [CredentialStore] for what happens
 * when settings arrive without their keys). Keys nobody references are deleted only after the
 * settings loaded successfully: while the file is broken, nothing is saved or deleted until an
 * explicit [reset].
 *
 * [reload], [reset] and [testAndSave] hold one [lock] from start to end, the state they publish
 * included: a load can never delete a key that a save running beside it has just written, and
 * the UI and the debug injection cannot interleave saves. A save publishes its settings as part
 * of its writes ([ModelSetup]), so cancelling it never leaves [state] behind what is on disk.
 */
internal class ModelServices(
    filesDir: File,
    noBackupDir: File,
    cipher: SecretCipher,
    probeWith: (CredentialResolver) -> ProviderProbe = { OpenAiAdapters(it).probe() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val lock: Mutex = Mutex(),
) {
    /** The keys. */
    val credentials: CredentialStore = CredentialStore(File(noBackupDir, CREDENTIALS_DIR), cipher, io)
    private val file = ModelSettingsFile(File(filesDir, ModelSettingsFile.NAME))
    private val setup = ModelSetup(credentials, file, probeWith, io)
    private val current = MutableStateFlow<SettingsState>(SettingsState.Loading)

    /** The settings as last loaded or saved. */
    val state: StateFlow<SettingsState> = current.asStateFlow()

    /** Reads the settings again, and on success deletes the keys they no longer use. */
    suspend fun reload(): SettingsState = lock.withLock { load() }

    private suspend fun load(): SettingsState =
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

    /**
     * "Test and save" of [form] against the current settings (loaded first if they never were);
     * publishes them when saved. While the settings are broken it does nothing and says
     * [SetupOutcome.SettingsBroken].
     */
    suspend fun testAndSave(
        form: ProviderForm,
        onModel: (ModelId) -> Unit = {},
    ): SetupOutcome =
        lock.withLock {
            val now = current.value.takeUnless { it == SettingsState.Loading } ?: load()
            if (now is SettingsState.Broken) return@withLock SetupOutcome.SettingsBroken
            setup.testAndSave(form, (now as? SettingsState.Ready)?.settings, onModel) { saved ->
                current.value = ready(saved)
            }
        }

    /** Deletes the settings and every key, then loads (finding none): the only way out of broken settings. */
    suspend fun reset(): SettingsState =
        lock.withLock {
            withContext(io) { file.reset() }
            load()
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
