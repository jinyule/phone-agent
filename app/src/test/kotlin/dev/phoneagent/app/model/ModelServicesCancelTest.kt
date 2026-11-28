package dev.phoneagent.app.model

import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.SettingsLoad
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

/**
 * A save cancelled once it has started writing finishes: the key, the settings, the cleanup and
 * the published state all move together, and the cancellation ends the call afterwards. A save
 * cancelled while it is still testing writes nothing. The cancellation lands at a fixed point (the
 * seal of the new key, or a held trial), not at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelServicesCancelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()
    private val cipher = SoftwareCipher()

    /** The services' I/O threads. */
    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()

    /** Threads for callers that are not on [io], like the UI. */
    private val elsewhere = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @After
    fun stop() {
        provider.close()
        io.close()
        elsewhere.close()
    }

    private fun form(key: String) =
        ProviderForm.start(null).copy(baseUrl = provider.baseUrl, apiKey = key, mainModel = "main-model")

    private fun refOf(settings: ModelSettings?): SecretRef? {
        val credential =
            settings
                ?.providers
                ?.single()
                ?.profile
                ?.credential
        return (credential as? Credential.ApiKey)?.secretRef
    }

    /** The settings saved in [files]. */
    private fun onDisk(files: File): ModelSettings {
        val load = ModelSettingsFile(File(files, ModelSettingsFile.NAME)).load()
        return (load as SettingsLoad.Loaded).settings
    }

    /**
     * Saves [OLD_KEY], then a save of [NEW_KEY] called on [caller] that cancels itself as its key
     * is sealed. How it ended; whether disk, keys and state agree; and a save with the key field
     * left blank afterwards.
     */
    private fun cancelledWhileSealing(caller: CoroutineDispatcher): List<Any?> {
        val files = temp.newFolder()
        val services = ModelServices(files, temp.newFolder(), cipher, io = io)
        return runBlocking {
            withTimeout(WAIT_MILLIS) {
                val oldRef = refOf((services.testAndSave(form(OLD_KEY)) as SetupOutcome.Saved).settings)
                val save = async(caller, start = CoroutineStart.LAZY) { services.testAndSave(form(NEW_KEY)) }
                cipher.onSeal = { save.cancel() }
                save.join()
                val ended = save.getCompletionExceptionOrNull()
                cipher.onSeal = {}
                val disk = onDisk(files)
                val newRef = checkNotNull(refOf(disk))
                val seen =
                    listOf(
                        ended is CancellationException,
                        newRef != oldRef,
                        services.credentials.status(newRef),
                        services.credentials.status(checkNotNull(oldRef)),
                        services.state.value == SettingsState.Ready(disk, mapOf(CUSTOM to CredentialStatus.PRESENT)),
                    )
                seen + services.testAndSave(form("")).javaClass.simpleName
            }
        }
    }

    @Test
    fun aSaveCancelledWhileItWritesFinishesAndPublishesWhatItWrote() {
        // Collected first, so each cell shows every consequence.
        val seen =
            mapOf(
                "caller on another dispatcher" to cancelledWhileSealing(elsewhere),
                "caller on the I/O dispatcher" to cancelledWhileSealing(io),
            )
        assertEquals(
            seen.keys.associateWith {
                listOf(true, true, CredentialStatus.PRESENT, CredentialStatus.MISSING, true, "Saved")
            },
            seen,
        )
    }

    @Test
    fun aSaveCancelledWhileItTestsWritesNothing() {
        val files = temp.newFolder()
        val keys = temp.newFolder()
        val services = ModelServices(files, keys, cipher, io = io)
        val settingsFile = File(files, ModelSettingsFile.NAME)
        val seen =
            runBlocking {
                withTimeout(WAIT_MILLIS) {
                    val old = (services.testAndSave(form(OLD_KEY)) as SetupOutcome.Saved).settings
                    val before = settingsFile.readText()
                    val hold = provider.holdTrials()
                    val save = async(elsewhere) { services.testAndSave(form(NEW_KEY)) }
                    check(withContext(elsewhere) { hold.arrived() }) { "the trial never came" }
                    save.cancel()
                    hold.release()
                    save.join()
                    val ended = save.getCompletionExceptionOrNull()
                    listOf(
                        ended is CancellationException,
                        settingsFile.readText() == before,
                        File(keys, "credentials").listFiles().orEmpty().size,
                        services.credentials.status(checkNotNull(refOf(old))),
                        services.state.value == SettingsState.Ready(old, mapOf(CUSTOM to CredentialStatus.PRESENT)),
                    )
                }
            }
        assertEquals(listOf(true, true, 1, CredentialStatus.PRESENT, true), seen)
    }

    private companion object {
        val CUSTOM = ProviderId("custom")
        const val OLD_KEY = "sk-test-old-not-a-real-key"
        const val NEW_KEY = "sk-test-new-not-a-real-key"
        const val WAIT_MILLIS = 20_000L
    }
}
