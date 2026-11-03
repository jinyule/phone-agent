package dev.phoneagent.app.model.settings

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.SecretRef
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.openai.ProviderPresets
import dev.phoneagent.core.model.openai.WireOptions
import kotlinx.serialization.Serializable

/**
 * The non-secret model settings (`filesDir/model-settings.json`): the providers that passed "test
 * and save", each with the request options it passed with and the models it was checked with, and
 * which model serves each role. Keys are in the credential store, referenced by
 * [Credential.ApiKey.secretRef]. Field names are the file format: renaming one means raising
 * [version] and migrating.
 *
 * @property version format version, [CURRENT_VERSION].
 * @property providers the configured providers.
 * @property roles the model of each role; [ModelRole.MAIN] is required, the others follow it when
 *   absent.
 */
@Serializable
internal data class ModelSettings(
    val version: Int = CURRENT_VERSION,
    val providers: List<StoredProvider>,
    val roles: Map<ModelRole, RoleBinding>,
) {
    /** What is wrong with these settings, or `null` when they can be used. Checked on load and before save. */
    fun problem(): SettingsProblem? =
        when {
            version != CURRENT_VERSION -> {
                SettingsProblem(SettingsProblemKind.UNSUPPORTED_VERSION, "version")
            }

            providers.map { it.profile.id }.toSet().size != providers.size -> {
                SettingsProblem(SettingsProblemKind.DUPLICATE_PROVIDER, "providers")
            }

            else -> {
                providers.firstNotNullOfOrNull { it.problem() } ?: rolesProblem()
            }
        }

    /** The binding of [role], following [ModelRole.MAIN] when it has none of its own. */
    fun binding(role: ModelRole): RoleBinding = roles[role] ?: roles.getValue(ModelRole.MAIN)

    /** The provider and model [binding] names; both exist once [problem] is `null`. */
    fun target(binding: RoleBinding): Pair<StoredProvider, StoredModel>? {
        val provider = providers.firstOrNull { it.profile.id == binding.provider }
        val model = provider?.models?.firstOrNull { it.id == binding.model }
        return if (provider != null && model != null) provider to model else null
    }

    /** Every secret ref the providers use: what the credential store keeps. */
    fun refs(): Set<SecretRef> =
        providers.mapNotNullTo(HashSet()) { (it.profile.credential as? Credential.ApiKey)?.secretRef }

    private fun rolesProblem(): SettingsProblem? {
        if (ModelRole.MAIN !in roles) return SettingsProblem(SettingsProblemKind.NO_MAIN_MODEL, "roles")
        return roles.entries.firstNotNullOfOrNull { (role, binding) -> roleProblem(role, binding) }
    }

    private fun roleProblem(
        role: ModelRole,
        binding: RoleBinding,
    ): SettingsProblem? {
        val capabilities = target(binding)?.second?.capabilities
        val kind =
            when {
                capabilities == null -> SettingsProblemKind.UNKNOWN_MODEL
                role == ModelRole.MAIN && !capabilities.canDrive() -> SettingsProblemKind.MAIN_WITHOUT_TOOLS
                role == ModelRole.VISION && !capabilities.vision -> SettingsProblemKind.VISION_WITHOUT_IMAGES
                else -> null
            }
        return kind?.let { SettingsProblem(it, "roles.$role") }
    }

    companion object {
        /** The format this app reads and writes. */
        const val CURRENT_VERSION: Int = 1
    }
}

/** Whether a model may drive tasks: it calls tools, and "test and save" saw it do so. */
private fun ModelCapabilities.canDrive(): Boolean = tools && VerifiedCapability.TOOL_CALL in verified

/**
 * One provider that passed "test and save".
 *
 * @property profile where and how to call it; validated when it is read.
 * @property wire the request options the trial passed with
 *   ([dev.phoneagent.core.model.openai.ProbeResult.Passed.wire]).
 * @property models the models "test and save" checked, with their capabilities.
 */
@Serializable
internal data class StoredProvider(
    val profile: ProviderProfile,
    val wire: WireOptions,
    val models: List<StoredModel>,
) {
    fun problem(): SettingsProblem? {
        val preset = profile.preset
        val kind =
            when {
                profile.protocol !in SUPPORTED_PROTOCOLS -> SettingsProblemKind.UNSUPPORTED_PROTOCOL
                preset != null && ProviderPresets.byId(preset) == null -> SettingsProblemKind.UNKNOWN_PRESET
                !fits() -> SettingsProblemKind.OPTIONS_DO_NOT_FIT
                models.map { it.id }.toSet().size != models.size -> SettingsProblemKind.DUPLICATE_MODEL
                else -> null
            }
        return kind?.let { SettingsProblem(it, "providers.${profile.id}") }
    }

    private fun fits(): Boolean =
        try {
            wire.requireFits(profile)
            true
        } catch (ignoredDoesNotFit: IllegalArgumentException) {
            // Reported as a kind with the provider id; the message adds nothing the user can act on.
            false
        }
}

/** A model "test and save" checked, by its wire name. */
@Serializable
internal data class StoredModel(
    val id: ModelId,
    val capabilities: ModelCapabilities,
)

/** Which provider and model serve a role. */
@Serializable
internal data class RoleBinding(
    val provider: ProviderId,
    val model: ModelId,
)

/** Why settings cannot be used; [where] is the field path, never a value from the file. */
internal data class SettingsProblem(
    val kind: SettingsProblemKind,
    val where: String?,
)

/** The kinds of [SettingsProblem]; the UI shows one text per kind. */
internal enum class SettingsProblemKind {
    /** The file cannot be read from storage. */
    UNREADABLE_FILE,

    /** Not JSON, or not the shape of the settings. */
    MALFORMED,

    /** A value the format rejects (a profile, an id or capabilities that fail validation). */
    INVALID_VALUE,

    /** A version this app does not read. */
    UNSUPPORTED_VERSION,

    /** Two providers share an id. */
    DUPLICATE_PROVIDER,

    /** A provider lists a model twice. */
    DUPLICATE_MODEL,

    /** A protocol this app cannot call yet. */
    UNSUPPORTED_PROTOCOL,

    /** A preset id this app does not know. */
    UNKNOWN_PRESET,

    /** The saved request options do not fit the provider's route. */
    OPTIONS_DO_NOT_FIT,

    /** No model for [ModelRole.MAIN]. */
    NO_MAIN_MODEL,

    /** A role names a provider or model that is not configured. */
    UNKNOWN_MODEL,

    /** The main model does not call tools, or "test and save" did not see it do so. */
    MAIN_WITHOUT_TOOLS,

    /** The vision model does not accept images. */
    VISION_WITHOUT_IMAGES,
}

/** The protocols the app has adapters for; Anthropic Messages arrives with its adapter (WP04). */
internal val SUPPORTED_PROTOCOLS: Set<ModelProtocol> =
    setOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES)
