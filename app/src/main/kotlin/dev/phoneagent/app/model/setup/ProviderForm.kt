package dev.phoneagent.app.model.setup

import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.RoleBinding
import dev.phoneagent.app.model.settings.SUPPORTED_PROTOCOLS
import dev.phoneagent.app.model.settings.StoredProvider
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.openai.ProviderPreset
import dev.phoneagent.core.model.openai.ProviderPresets
import java.net.URI
import java.net.URISyntaxException

/**
 * What the user (or the debug injection) filled in for one provider. [apiKey] lives only here and
 * in memory: [toString] leaves it out, and nothing persists the form.
 *
 * @property presetId the preset, or `null` for a custom provider.
 * @property baseUrl the address; a preset starts with its own.
 * @property protocol a custom provider's protocol; a preset's is its own.
 * @property auth whether a key is sent.
 * @property apiKey the key typed in; blank keeps the stored key of the same provider, if any.
 * @property headers extra non-secret headers.
 * @property mainModel the model that drives tasks (required).
 * @property lightModel the model for small judgments; blank follows the main model.
 * @property visionModel the model that reads screenshots; blank follows the main model.
 * @property visionImages whether [visionModel] (or, when blank, the main model) accepts images,
 *   when the provider's model list does not say.
 * @property contextTokens the context window when the provider's model list does not say; `null`
 *   for [DEFAULT_CONTEXT_TOKENS].
 */
internal data class ProviderForm(
    val presetId: String?,
    val baseUrl: String,
    val protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
    val auth: AuthChoice,
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val mainModel: String = "",
    val lightModel: String = "",
    val visionModel: String = "",
    val visionImages: Boolean = false,
    val contextTokens: Int? = null,
) {
    /** The preset this form starts from, or `null` for a custom provider (or an unknown id). */
    val preset: ProviderPreset? get() = presetId?.let(ProviderPresets::byId)

    /** The provider id the settings store this form under: the preset's, or [CUSTOM_PROVIDER]. */
    val providerId: ProviderId get() = ProviderId(presetId ?: CUSTOM_PROVIDER)

    /** The protocol requests use. */
    val effectiveProtocol: ModelProtocol get() = preset?.protocol ?: protocol

    /** The key as it would be sent: trimmed, like the adapters trim it. */
    val typedKey: String get() = apiKey.trim()

    /** The distinct models the form names, main first: what "test and save" checks. */
    val models: List<String>
        get() = listOf(mainModel, lightModel, visionModel).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    override fun toString(): String =
        "ProviderForm(presetId=$presetId, baseUrl=$baseUrl, protocol=$protocol, auth=$auth, " +
            "apiKey=${if (apiKey.isEmpty()) "<empty>" else "<${apiKey.length} chars>"}, headers=${headers.keys}, " +
            "mainModel=$mainModel, lightModel=$lightModel, visionModel=$visionModel, " +
            "visionImages=$visionImages, contextTokens=$contextTokens)"

    /** The profile this form describes with [credential]; throws [IllegalArgumentException] like [ProviderProfile]. */
    fun profile(credential: Credential): ProviderProfile {
        val url = baseUrl.trim()
        val preset = preset
        val profile =
            preset?.profile(providerId, credential, baseUrl = url)
                ?: ProviderProfile(
                    id = providerId,
                    displayName = hostOf(url) ?: CUSTOM_PROVIDER,
                    protocol = protocol,
                    baseUrl = url,
                    credential = credential,
                )
        return if (headers.isEmpty()) profile else profile.copy(headers = headers)
    }

    companion object {
        /** The provider id of a form without a preset. */
        const val CUSTOM_PROVIDER: String = "custom"

        /** The context window assumed when neither the listing nor the form names one. */
        const val DEFAULT_CONTEXT_TOKENS: Int = 128_000

        /**
         * Below this many characters a key gets the short-key hint: the adapters reject every reply
         * that contains the key, matched exactly at any length (model-layer §2), so a placeholder
         * like `x` makes replies fail often.
         */
        const val SHORT_KEY_CHARS: Int = 8

        /**
         * The form that edits [settings]' main provider, with an empty key field (keeping the stored
         * key), or a new custom form when there are none.
         */
        fun of(settings: ModelSettings?): ProviderForm {
            val main = settings?.binding(ModelRole.MAIN)
            val provider = main?.let { settings.target(it) }?.first
            return if (settings == null || main == null ||
                provider == null
            ) {
                start(null)
            } else {
                editing(settings, main, provider)
            }
        }

        private fun editing(
            settings: ModelSettings,
            main: RoleBinding,
            provider: StoredProvider,
        ): ProviderForm {
            val profile = provider.profile

            fun role(role: ModelRole) =
                settings.roles[role]
                    ?.takeIf { it.provider == profile.id }
                    ?.model
                    ?.value
                    .orEmpty()
            val vision = role(ModelRole.VISION).ifEmpty { main.model.value }
            return ProviderForm(
                presetId = profile.preset?.takeIf { it == profile.id.value },
                baseUrl = profile.baseUrl,
                protocol = profile.protocol,
                auth = if (profile.credential == Credential.None) AuthChoice.NONE else AuthChoice.API_KEY,
                headers = profile.headers,
                mainModel = main.model.value,
                lightModel = role(ModelRole.LIGHT),
                visionModel = role(ModelRole.VISION),
                visionImages =
                    provider.models
                        .firstOrNull { it.id.value == vision }
                        ?.capabilities
                        ?.vision == true,
            )
        }

        /**
         * A new form for [preset] (`null`: custom). A preset that needs no key starts with no
         * authentication; every other form starts with a key.
         */
        fun start(preset: ProviderPreset?): ProviderForm =
            ProviderForm(
                presetId = preset?.id,
                baseUrl = preset?.baseUrl.orEmpty(),
                protocol = preset?.protocol ?: ModelProtocol.OPENAI_CHAT_COMPLETIONS,
                auth = if (preset?.needsKey == false) AuthChoice.NONE else AuthChoice.API_KEY,
            )
    }

    /** This form moved to [preset] (`null`: custom): its address and authentication, keeping what was typed. */
    fun switchedTo(preset: ProviderPreset?): ProviderForm =
        start(preset).copy(
            baseUrl = preset?.baseUrl ?: baseUrl,
            protocol = preset?.protocol ?: effectiveProtocol,
            apiKey = apiKey,
            headers = headers,
            mainModel = mainModel,
            lightModel = lightModel,
            visionModel = visionModel,
            visionImages = visionImages,
            contextTokens = contextTokens,
        )
}

/** How a provider authenticates, as the form offers it. */
internal enum class AuthChoice {
    /** No authentication ([Credential.None]): local servers. */
    NONE,

    /** An API key. */
    API_KEY,
}

/** Something that keeps a form from being tested. */
internal enum class FormProblem {
    /** No main model. */
    MAIN_MODEL_MISSING,

    /** A model name that cannot be a model id (too long, control characters). */
    MODEL_ID_INVALID,

    /** A key is chosen but none is typed, and none is stored for this provider. */
    KEY_MISSING,

    /** The key holds characters an HTTP header cannot carry. */
    KEY_NOT_HEADER_SAFE,

    /** The address is not `https://host[/path]`, or plain `http` to a host that is not local. */
    BASE_URL_INVALID,

    /** A header name looks like a credential, or a value is not valid HTTP. */
    HEADER_INVALID,

    /** A preset id this app does not know. */
    UNKNOWN_PRESET,

    /** A protocol this app cannot call yet. */
    PROTOCOL_UNSUPPORTED,

    /** A context window that is not positive. */
    CONTEXT_INVALID,

    /** A vision model is named but not marked as accepting images. */
    VISION_NEEDS_IMAGES,
}

/** Advice shown with a form; none of it keeps the form from being tested. */
internal sealed interface FormHint {
    /** The key is shorter than [ProviderForm.SHORT_KEY_CHARS]: suggest no authentication for local services. */
    data object ShortKey : FormHint

    /** A custom address is [preset]'s official one (model-layer §4.1 step 5): suggest the preset. */
    data class UsePreset(
        val preset: ProviderPreset,
    ) : FormHint
}

/** Checks of a [ProviderForm] that need no network. */
internal object FormCheck {
    /**
     * What keeps [form] from being tested; empty when it can be. [hasStoredKey] is whether the
     * provider already has a readable stored key that a blank key field keeps.
     */
    fun problems(
        form: ProviderForm,
        hasStoredKey: Boolean,
    ): List<FormProblem> =
        listOfNotNull(
            FormProblem.UNKNOWN_PRESET.takeIf { form.presetId != null && form.preset == null },
            FormProblem.PROTOCOL_UNSUPPORTED.takeIf { form.effectiveProtocol !in SUPPORTED_PROTOCOLS },
            FormProblem.MAIN_MODEL_MISSING.takeIf { form.mainModel.isBlank() },
            FormProblem.MODEL_ID_INVALID.takeIf { form.models.any { name -> !isModelId(name) } },
            keyProblem(form, hasStoredKey),
            FormProblem.CONTEXT_INVALID.takeIf { (form.contextTokens ?: 1) <= 0 },
            FormProblem.VISION_NEEDS_IMAGES.takeIf { form.visionModel.isNotBlank() && !form.visionImages },
        ) + profileProblems(form)

    /** Advice for [form]: a short key, an official address in a custom form. */
    fun hints(form: ProviderForm): List<FormHint> =
        listOfNotNull(
            FormHint.ShortKey.takeIf {
                form.auth == AuthChoice.API_KEY && form.typedKey.isNotEmpty() &&
                    form.typedKey.length < ProviderForm.SHORT_KEY_CHARS
            },
            officialPreset(form)?.let { FormHint.UsePreset(it) },
        )

    private fun officialPreset(form: ProviderForm): ProviderPreset? =
        if (form.presetId == null) ProviderPresets.matchingOfficial(form.baseUrl) else null

    private fun keyProblem(
        form: ProviderForm,
        hasStoredKey: Boolean,
    ): FormProblem? =
        when {
            form.auth != AuthChoice.API_KEY -> null
            form.typedKey.isEmpty() -> FormProblem.KEY_MISSING.takeUnless { hasStoredKey }
            form.typedKey.any { it < ' ' || it > '~' } -> FormProblem.KEY_NOT_HEADER_SAFE
            else -> null
        }

    /** The address and the headers, checked by building the profile (it never echoes either). */
    private fun profileProblems(form: ProviderForm): List<FormProblem> {
        val bare = form.copy(headers = emptyMap())
        return when {
            form.presetId != null && form.preset == null -> emptyList()
            !builds(bare) -> listOf(FormProblem.BASE_URL_INVALID)
            !builds(form) -> listOf(FormProblem.HEADER_INVALID)
            else -> emptyList()
        }
    }

    private fun builds(form: ProviderForm): Boolean =
        try {
            form.profile(Credential.None)
            true
        } catch (ignoredInvalid: IllegalArgumentException) {
            // The profile's own check; its message is not shown.
            false
        }

    private fun isModelId(name: String): Boolean =
        try {
            ModelId(name)
            true
        } catch (ignoredInvalid: IllegalArgumentException) {
            // Shown as MODEL_ID_INVALID.
            false
        }
}

/** The host of [url] as a label, or `null` when it has none. */
private fun hostOf(url: String): String? =
    try {
        URI(url).host
    } catch (ignoredNotUri: URISyntaxException) {
        // The profile's own check reports the address.
        null
    }
