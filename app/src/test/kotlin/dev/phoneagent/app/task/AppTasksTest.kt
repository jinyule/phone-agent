package dev.phoneagent.app.task

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.RoleBinding
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.PolicyRulesRecorded
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.storage.FileStoragePort
import dev.phoneagent.core.tool.StepState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/** How the app starts its tasks: the product's names, the model, the session directory and its log. */
@RunWith(AndroidJUnit4::class)
class AppTasksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val services by lazy { ModelServices(context.filesDir, context.noBackupFilesDir, SoftwareCipher()) }
    private val sessions get() = File(context.filesDir, "sessions")

    private fun save(settings: ModelSettings) =
        File(
            context.filesDir,
            ModelSettingsFile.NAME,
        ).writeText(Json.encodeToString(serializer<ModelSettings>(), settings))

    /** A main model on a local server that needs no key. */
    private val keyless: ModelSettings =
        SettingsFixtures.settings(
            providers = listOf(SettingsFixtures.provider(profile = SettingsFixtures.local)),
            roles = mapOf(ModelRole.MAIN to RoleBinding(SettingsFixtures.local.id, SettingsFixtures.MAIN)),
        )

    private fun problemOfStarting(): TaskProblem? =
        runBlocking {
            val job = SupervisorJob()
            try {
                AppTaskLauncher(context, services).start(CoroutineScope(job))
                null
            } catch (unavailable: TaskUnavailableException) {
                unavailable.reason
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun theProductKnowsThisAppByItsPackageAndEveryNameItIsShownUnder() {
        val config = productConfig(context)
        assertEquals(setOf("dev.phoneagent"), config.ownPackages)
        assertEquals(setOf("PhoneAgent", "PhoneAgent screen control", "PhoneAgent 屏幕控制"), config.ownLabels)
    }

    @Test
    fun withoutAModelNoTaskStarts() {
        assertEquals(TaskProblem.NO_MODEL, problemOfStarting())
        File(context.filesDir, ModelSettingsFile.NAME).writeText("{ not json")
        runBlocking { services.reload() }
        assertEquals(TaskProblem.NO_MODEL, problemOfStarting())
    }

    @Test
    fun aMainModelWhoseKeyIsGoneAsksForTheKey() {
        save(SettingsFixtures.settings())
        assertEquals(TaskProblem.KEY_MISSING, problemOfStarting())
        assertTrue(!sessions.exists() || sessions.list().isNullOrEmpty())
    }

    @Test
    fun aTaskStartsInASessionOfItsOwnThatRecordsItsLevelAndRules() =
        runBlocking {
            save(keyless)
            val job = SupervisorJob()
            val launcher = AppTaskLauncher(context, services)
            val first = launcher.start(CoroutineScope(job))
            val second = launcher.start(CoroutineScope(job))

            val dirs =
                sessions
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .sorted()
            assertEquals(2, dirs.size)
            val log = first.session.log()
            assertEquals(TaskStarted(SecurityLevel.STANDARD, unattended = false), log.records[0].event)
            assertEquals(PolicyTuning.RULES_VERSION, (log.records[1].event as PolicyRulesRecorded).rulesVersion)
            assertNotEquals(
                log.header.sessionId,
                second.session
                    .log()
                    .header.sessionId,
            )
            assertEquals(log.header.sessionId.value, dirs.single { it == log.header.sessionId.value })

            val tap = call("call-1", "phone_tap", "screen" to "s1", "ref" to "e2")
            assertEquals(
                StepState.RUNNING,
                first.session.cards
                    .card(tap, null)
                    ?.state,
            )
            job.cancelAndJoin()
        }

    @Test
    fun aSessionsModelReadsTheImagesOfThatSessionOnly() =
        runBlocking<Unit> {
            val storage = FileStoragePort(File(context.cacheDir, "blobs-test").toPath())
            val mine = SessionFiles(storage, StoragePath("session-a"))
            val other = SessionFiles(storage, StoragePath("session-b"))
            val bytes = byteArrayOf(1, 2, 3)
            val ref = mine.blobs.put(bytes)

            assertArrayEquals(bytes, sessionBlobs(mine).bytes(ref))
            // An IOException: the adapter turns it into InvalidRequest.
            assertThrows(FileNotFoundException::class.java) { runBlocking { sessionBlobs(other).bytes(ref) } }
        }
}
