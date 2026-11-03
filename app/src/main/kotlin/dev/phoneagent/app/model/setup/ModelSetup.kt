package dev.phoneagent.app.model.setup

import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.credential.CredentialStore
import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.settings.RoleBinding
import dev.phoneagent.app.model.settings.StoredModel
import dev.phoneagent.app.model.settings.StoredProvider
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.SecretRef
import dev.phoneagent.core.model.openai.ProbeResult
import dev.phoneagent.core.model.openai.ProviderProbe
import dev.phoneagent.core.model.openai.WireOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID

/**
 * "Test and save" of one provider form (model-layer §4.1). Every model the form names is checked
 * with core's [ProviderProbe] (list the models, then a real trial tool call), main model first,
 * each starting from the request options the previous one passed with. Only when all pass is
 * anything written, in this order: the new key, then the settings (atomically), then the keys
 * no saved provider uses any more. A crash in between leaves only keys nobody references, which
 * the next successful load deletes ([CredentialStore.retainOnly]). A failure writes nothing.
 *
 * The probe sees the typed key through a resolver of its own, which knows only this run's ref:
 * nothing reaches the credential store before the trial passed. Runs are serialized, so the UI and
 * the debug injection cannot interleave saves.
 */
internal class ModelSetup(
    private val credentials: CredentialStore,
    private val file: ModelSettingsFile,
    private val probeWith: (CredentialResolver) -> ProviderProbe,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newGeneration: () -> String = { UUID.randomUUID().toString().take(GENERATION_CHARS) },
) {
    private val running = Mutex()

    /**
     * Tests [form] and saves it as the only provider, replacing [current] (the loaded settings, or
     * `null`). A blank key keeps the stored key of the same provider when it is readable.
     * [onModel] is told which model is being checked.
     */
    suspend fun testAndSave(
        form: ProviderForm,
        current: ModelSettings?,
        onModel: (ModelId) -> Unit = {},
    ): SetupOutcome =
        running.withLock {
            val kept = withContext(io) { keptRef(form, current) }
            val problems = FormCheck.problems(form, hasStoredKey = kept != null)
            if (problems.isNotEmpty()) return@withLock SetupOutcome.Invalid(problems)
            val typed = form.typedKey.takeIf { form.auth == AuthChoice.API_KEY && it.isNotEmpty() }
            val ref = typed?.let { SecretRef("api-key/${form.providerId}/${newGeneration()}") } ?: kept
            val credential = ref?.let { Credential.ApiKey(it) } ?: Credential.None
            val profile = form.profile(credential)
            val probe = probeWith(TrialCredentials(ref, typed, credentials))
            when (val run = Trials(form, profile, probe, onModel).run()) {
                is TrialRun.Failed -> SetupOutcome.Failed(run.checks)
                is TrialRun.Passed -> save(form, profile, run, typed?.let { checkNotNull(ref) to it })
            }
        }

    /** The ref of the stored key [form] keeps when its key field is blank, or `null`. */
    private fun keptRef(
        form: ProviderForm,
        current: ModelSettings?,
    ): SecretRef? {
        val provider = current?.providers?.firstOrNull { it.profile.id == form.providerId }
        val ref = (provider?.profile?.credential as? Credential.ApiKey)?.secretRef
        val keeps = form.auth == AuthChoice.API_KEY && form.typedKey.isEmpty()
        return ref?.takeIf { keeps && credentials.status(it) == CredentialStatus.PRESENT }
    }

    private suspend fun save(
        form: ProviderForm,
        profile: ProviderProfile,
        run: TrialRun.Passed,
        newKey: Pair<SecretRef, String>?,
    ): SetupOutcome {
        val settings = settingsOf(form, profile, run)
        return withContext(io) {
            when {
                newKey != null && !attempt { credentials.put(newKey.first, newKey.second) } -> {
                    SetupOutcome.NotSaved(run.checks, SaveFailure.KEY)
                }

                !attempt { file.save(settings) } -> {
                    SetupOutcome.NotSaved(run.checks, SaveFailure.SETTINGS)
                }

                else -> {
                    credentials.retainOnly(settings.refs())
                    SetupOutcome.Saved(settings, run.checks)
                }
            }
        }
    }

    private fun settingsOf(
        form: ProviderForm,
        profile: ProviderProfile,
        run: TrialRun.Passed,
    ): ModelSettings {
        val models =
            run.checks.map {
                StoredModel(
                    it.model,
                    checkNotNull(it.capabilities) { "passed checks have them" },
                )
            }
        val roles =
            buildMap {
                put(ModelRole.MAIN, RoleBinding(profile.id, ModelId(form.mainModel.trim())))
                form.lightModel.trim().takeIf { it.isNotEmpty() }?.let {
                    put(ModelRole.LIGHT, RoleBinding(profile.id, ModelId(it)))
                }
                form.visionModel.trim().takeIf { it.isNotEmpty() }?.let {
                    put(ModelRole.VISION, RoleBinding(profile.id, ModelId(it)))
                }
            }
        return ModelSettings(providers = listOf(StoredProvider(profile, run.wire, models)), roles = roles)
    }

    /** Whether [write] finished; storage and Keystore failures are reported as an outcome. */
    @Suppress("TooGenericExceptionCaught") // Each is named below; any of them means "not saved".
    private inline fun attempt(write: () -> Unit): Boolean =
        try {
            write()
            true
        } catch (ignoredStorage: IOException) {
            // The file system refused the write.
            false
        } catch (ignoredKeystore: GeneralSecurityException) {
            // The Keystore refused to create or use the key.
            false
        } catch (ignoredKeystoreInternal: ProviderException) {
            // Keystore-internal failures arrive unchecked.
            false
        }
}

/** The checks of one form, model by model; stops at the first failure. */
private class Trials(
    private val form: ProviderForm,
    private val profile: ProviderProfile,
    private val probe: ProviderProbe,
    private val onModel: (ModelId) -> Unit,
) {
    suspend fun run(): TrialRun {
        var wire = WireOptions.defaultsFor(profile)
        val checks = mutableListOf<ModelCheck>()
        for (name in form.models) {
            val model = ModelId(name)
            onModel(model)
            when (val result = probe.testAndSave(profile, model, initial(name), wire = wire)) {
                is ProbeResult.Failed -> {
                    checks += ModelCheck(model, rolesOf(name), result, null)
                    return TrialRun.Failed(checks)
                }

                is ProbeResult.Passed -> {
                    wire = result.wire
                    checks += ModelCheck(model, rolesOf(name), result, listed(name, result))
                }
            }
        }
        return TrialRun.Passed(checks, wire)
    }

    /** What the trial starts from: tools (it calls one), and the form's values. */
    private fun initial(name: String) =
        ModelCapabilities(
            tools = true,
            vision = name == visionTarget() && form.visionImages,
            contextTokens = form.contextTokens ?: ProviderForm.DEFAULT_CONTEXT_TOKENS,
        )

    /** The verified capabilities, with what the listing says about the model where the form did not. */
    private fun listed(
        name: String,
        passed: ProbeResult.Passed,
    ): ModelCapabilities {
        val entry = passed.models?.firstOrNull { it.id.value == name }
        val vision =
            if (name == visionTarget()) form.visionImages || entry?.vision == true else entry?.vision ?: false
        return passed.capabilities.copy(
            vision = vision,
            contextTokens = form.contextTokens ?: entry?.contextTokens ?: ProviderForm.DEFAULT_CONTEXT_TOKENS,
        )
    }

    private fun visionTarget(): String = form.visionModel.trim().ifEmpty { form.mainModel.trim() }

    private fun rolesOf(name: String): Set<ModelRole> =
        buildSet {
            if (form.mainModel.trim() == name) add(ModelRole.MAIN)
            if (form.lightModel.trim() == name) add(ModelRole.LIGHT)
            if (form.visionModel.trim() == name) add(ModelRole.VISION)
        }
}

private sealed interface TrialRun {
    val checks: List<ModelCheck>

    class Failed(
        override val checks: List<ModelCheck>,
    ) : TrialRun

    class Passed(
        override val checks: List<ModelCheck>,
        val wire: WireOptions,
    ) : TrialRun
}

/**
 * The key of a "test and save" run: [typed] under [ref] (never stored before the run passed), or
 * the stored key under [ref] when nothing was typed. Any other ref is not this run's.
 */
private class TrialCredentials(
    private val ref: SecretRef?,
    private val typed: String?,
    private val stored: CredentialResolver,
) : CredentialResolver {
    override suspend fun secret(
        credential: Credential,
        rejected: Boolean,
    ): String? =
        when {
            credential == Credential.None -> null

            credential !is Credential.ApiKey || credential.secretRef != ref -> throw ModelError.Auth(
                "not this check's key",
            )

            typed != null -> typed

            else -> stored.secret(credential, rejected)
        }
}

/**
 * One model's "test and save".
 *
 * @property model the model.
 * @property roles the roles the form gives it.
 * @property result what core's probe found.
 * @property capabilities what is saved for it when it passed, else `null`.
 */
internal data class ModelCheck(
    val model: ModelId,
    val roles: Set<ModelRole>,
    val result: ProbeResult,
    val capabilities: ModelCapabilities?,
)

/** What [ModelSetup.testAndSave] did. */
internal sealed interface SetupOutcome {
    /** The form cannot be tested; nothing was sent or written. */
    data class Invalid(
        val problems: List<FormProblem>,
    ) : SetupOutcome

    /** A model failed its check (the last of [checks]); nothing was written. */
    data class Failed(
        val checks: List<ModelCheck>,
    ) : SetupOutcome

    /** Every model passed but writing failed at [what]; the previous settings stay in effect. */
    data class NotSaved(
        val checks: List<ModelCheck>,
        val what: SaveFailure,
    ) : SetupOutcome

    /** Every model passed and [settings] are saved. */
    data class Saved(
        val settings: ModelSettings,
        val checks: List<ModelCheck>,
    ) : SetupOutcome
}

/** Which write of a passed "test and save" failed. */
internal enum class SaveFailure {
    /** Storing the key (Keystore or file). */
    KEY,

    /** Writing the settings file. */
    SETTINGS,
}

private const val GENERATION_CHARS = 8
