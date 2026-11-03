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

/** Valid settings to start tests from. */
internal object SettingsFixtures {
    val REF = SecretRef("api-key/deepseek/0a1b2c3d")
    val DEEPSEEK: ProviderId = ProviderId("deepseek")
    val MAIN = ModelId("deepseek-chat")
    val SMALL = ModelId("deepseek-small")
    val SEEING = ModelId("deepseek-vl")

    val verified =
        ModelCapabilities(
            tools = true,
            vision = false,
            contextTokens = 64_000,
            verified = setOf(VerifiedCapability.TOOL_CALL),
        )

    val profile: ProviderProfile = ProviderPresets.DEEPSEEK.profile(DEEPSEEK, Credential.ApiKey(REF))

    fun provider(
        profile: ProviderProfile = this.profile,
        models: List<StoredModel> =
            listOf(
                StoredModel(MAIN, verified),
                StoredModel(SMALL, verified),
                StoredModel(SEEING, verified.copy(vision = true)),
            ),
        wire: WireOptions = WireOptions.defaultsFor(profile),
    ) = StoredProvider(profile, wire, models)

    fun settings(
        providers: List<StoredProvider> = listOf(provider()),
        roles: Map<ModelRole, RoleBinding> = mapOf(ModelRole.MAIN to RoleBinding(DEEPSEEK, MAIN)),
    ) = ModelSettings(providers = providers, roles = roles)

    /** A custom Chat Completions provider without a key. */
    val local: ProviderProfile =
        ProviderProfile(
            id = ProviderId("custom"),
            displayName = "192.168.1.5",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://192.168.1.5:11434/v1",
            credential = Credential.None,
        )
}
