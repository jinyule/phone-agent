package dev.phoneagent.app.task

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.Display
import dev.phoneagent.app.R
import dev.phoneagent.app.adapters.screen.AndroidAppCatalog
import dev.phoneagent.app.adapters.screen.AndroidScreen
import dev.phoneagent.app.adapters.screen.AndroidScreenSystem
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.RoleModels
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.service.AgentAccessibility
import dev.phoneagent.core.agent.AgentLoop
import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.agent.TaskRuntime
import dev.phoneagent.core.assembly.ProductConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.OpenAiAdapters
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionLog
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.storage.FileStoragePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID

/** The locales the app's names are translated to (`res/values`, `res/values-zh-rCN`). */
private val NAME_LOCALES = listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)

/**
 * The product's configuration in this app (wp09-notes): the app's package, and every name it is
 * shown under in every language it has, for the policy to know its icon and its settings pages —
 * the launcher label, and the accessibility service's label on the system's accessibility page.
 *
 * It reads the names in each language and changes no locale (lint's `AppBundleLocaleChanges` is
 * about app bundles split by language; PhoneAgent is a sideloaded APK with every language in it).
 */
@SuppressLint("AppBundleLocaleChanges")
internal fun productConfig(context: Context): ProductConfig {
    val labels =
        NAME_LOCALES.flatMapTo(linkedSetOf()) { locale ->
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            val localized = context.createConfigurationContext(config)
            listOf(localized.getString(R.string.app_name), localized.getString(R.string.accessibility_service_label))
        }
    return ProductConfig(ownPackages = setOf(context.packageName), ownLabels = labels)
}

/**
 * Starts the app's tasks (phase1 §2.1), each in its own session directory
 * `filesDir/sessions/<session id>`:
 * - the main model from the saved settings, one per session, reading that session's blobs
 *   (model-layer §2), frozen for the task's life (a settings change applies to the next task);
 * - the product assembly over it ([assemble]) with this app's [productConfig];
 * - the accessibility ScreenPort on the main display (M1, no hand-off surfaces), built with the
 *   assembly's analyzer so its fingerprints and diffs follow what the model reads, and the app
 *   catalog;
 * - the task at [level], attended.
 *
 * Runtimes run on [work], under the scope they are given.
 */
internal class AppTaskLauncher(
    private val context: Context,
    private val services: ModelServices,
    private val level: SecurityLevel = SecurityLevel.STANDARD,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : TaskLauncher {
    private val adapters = OpenAiAdapters(services.credentials)
    private val storage = FileStoragePort(File(context.filesDir, SESSIONS_DIR).toPath())

    override suspend fun start(scope: CoroutineScope): StartedTask {
        val roles = roles()
        val sessionId = SessionId(UUID.randomUUID().toString())
        val files = SessionFiles(storage, StoragePath(sessionId.value))
        val model = roles.model(ModelRole.MAIN, sessionBlobs(files))
        val assembly = assemble(model, productConfig(context))
        val screen =
            AndroidScreen(
                surface = SurfaceRef(Display.DEFAULT_DISPLAY, SurfaceKind.MAIN),
                connection = { AgentAccessibility.host.value },
                system = AndroidScreenSystem(context),
                analyzer = assembly.analyzer,
            )
        val setup =
            assembly.setup(
                TaskId(UUID.randomUUID().toString()),
                files,
                screen,
                surfaces = null,
                apps = AndroidAppCatalog(context),
            )
        val session = AppTaskSession(setup, work)
        return StartedTask(session, session.start(sessionId, TaskStarted(level, unattended = false), scope))
    }

    /**
     * The models of the saved settings; [TaskUnavailableException] when there are none (or the
     * saved ones are broken) or the main one's key must be entered again.
     */
    private suspend fun roles(): RoleModels {
        val state = services.state.value.takeUnless { it == SettingsState.Loading } ?: services.reload()
        val ready = state as? SettingsState.Ready
        val settings = ready?.settings ?: throw TaskUnavailableException(TaskProblem.NO_MODEL)
        val roles = RoleModels(settings, adapters) // Ready holds usable settings only (checked on load).
        val profile = roles.target(ModelRole.MAIN).provider.profile
        if (profile.credential is Credential.ApiKey && ready.keys[profile.id] != CredentialStatus.PRESENT) {
            throw TaskUnavailableException(TaskProblem.KEY_MISSING)
        }
        return roles
    }

    private companion object {
        const val SESSIONS_DIR = "sessions"
    }
}

/** The images of the session in [files], for its main model; a missing one is an `IOException`. */
internal fun sessionBlobs(files: SessionFiles): BlobSource =
    BlobSource { ref -> files.blobs.get(ref) ?: throw FileNotFoundException("no blob $ref in ${files.dir}") }

/**
 * One task of the app on its [setup]: the first run, the next ones on the same log, the log.
 * Every run uses the same parts (model, screen port, catalog), so snapshot ids keep counting up
 * within the task.
 */
internal class AppTaskSession(
    private val setup: AgentSetup,
    private val work: CoroutineDispatcher,
) : TaskSession {
    /** The task's first run, with a new session [sessionId] at [settings]. */
    suspend fun start(
        sessionId: SessionId,
        settings: TaskStarted,
        scope: CoroutineScope,
    ): TaskRuntime = AgentLoop.start(setup, sessionId, settings, working(scope))

    override suspend fun resume(scope: CoroutineScope): TaskRuntime = AgentLoop.resume(setup, working(scope))

    override suspend fun log(): SessionLog = SessionStore.read(setup.files)

    override val cards: StepCards = StepCards.of(setup.registry)

    private fun working(scope: CoroutineScope): CoroutineScope = CoroutineScope(scope.coroutineContext + work)
}
