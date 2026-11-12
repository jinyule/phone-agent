package dev.phoneagent.app.model

import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * A load and a save of the settings never interleave. The settings file is replaced by a FIFO, so
 * a load that started reading holds the lock until the test writes the old settings into it; the
 * order of events is fixed by the FIFO and the lock, not by timing.
 */
class ModelServicesLockTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()

    /** Threads for the calls that block on the FIFO. */
    private val blocking = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val lock = ObservedMutex()
    private val files by lazy { temp.newFolder("files") }
    private val services by lazy { ModelServices(files, temp.newFolder("no_backup"), SoftwareCipher(), lock = lock) }
    private val settingsFile get() = File(files, ModelSettingsFile.NAME)

    @After
    fun stop() {
        provider.close()
        blocking.close()
    }

    @Test
    fun aSaveWaitsForALoadThatIsReadingAndKeepsItsNewKey() {
        val form = ProviderForm.start(null).copy(baseUrl = provider.baseUrl, apiKey = OLD_KEY, mainModel = "main-model")
        runBlocking {
            withTimeout(WAIT_MILLIS) {
                services.reload()
                services.testAndSave(form) as SetupOutcome.Saved
                val old = settingsFile.readBytes()
                settingsFile.delete()
                mkfifo(settingsFile)

                val load = async(blocking) { services.reload() }
                // Opening the write end returns once the load has opened the read end, inside the lock.
                val writer = withContext(blocking) { FileOutputStream(settingsFile) }
                val save = async(blocking) { services.testAndSave(form.copy(apiKey = NEW_KEY)) }
                // The load ends whatever happens here: the old settings go in, then the end of the file.
                val first =
                    writer.use {
                        select {
                            save.onAwait { "the save finished" }
                            lock.waiting.onAwait { "the save waited" }
                        }.also { _ -> it.write(old) }
                    }
                load.await()
                val saved = save.await() as SetupOutcome.Saved
                val ref =
                    (
                        saved.settings.providers
                            .single()
                            .profile.credential as Credential.ApiKey
                    ).secretRef
                // Collected first, so a run on code without the lock shows every consequence.
                assertEquals(
                    listOf(
                        "the save waited",
                        CredentialStatus.PRESENT,
                        SettingsState.Ready(saved.settings, mapOf(ProviderId("custom") to CredentialStatus.PRESENT)),
                    ),
                    listOf(first, services.credentials.status(ref), services.reload()),
                )
            }
        }
    }

    private fun mkfifo(file: File) {
        val made = ProcessBuilder("mkfifo", file.path).inheritIO().start().waitFor()
        assertEquals("mkfifo exit status", 0, made)
    }

    /** A [Mutex] that says when a [lock] call finds it held by someone else. */
    private class ObservedMutex(
        private val inner: Mutex = Mutex(),
    ) : Mutex by inner {
        val waiting = CompletableDeferred<Unit>()

        override suspend fun lock(owner: Any?) {
            if (inner.isLocked) waiting.complete(Unit)
            inner.lock(owner)
        }
    }

    private companion object {
        const val OLD_KEY = "sk-test-old-not-a-real-key"
        const val NEW_KEY = "sk-test-new-not-a-real-key"
        const val WAIT_MILLIS = 20_000L
    }
}
