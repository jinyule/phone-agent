package dev.phoneagent.app.model

import dev.phoneagent.app.model.settings.ModelSettings
import dev.phoneagent.app.model.settings.StoredModel
import dev.phoneagent.app.model.settings.StoredProvider
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.OpenAiAdapters

/**
 * The [ChatModel] of each role from one snapshot of the model settings: [ModelRole.LIGHT] and
 * [ModelRole.VISION] follow [ModelRole.MAIN] unless the settings name their own model. This is the
 * settings-to-model factory only; choosing a model per attempt is the `request` interceptor
 * `ModelRouter` (phase1 §3.8), which the task assembly builds on top of it.
 *
 * Bound to [settings] as they were when it was created: a model it makes freezes that snapshot's
 * profile, capabilities and request options, and the adapter freezes its mapping version
 * (model-layer §2), so changing the settings later only affects instances created afterwards.
 * Throws [IllegalArgumentException] for settings that would not load ([ModelSettings.problem]).
 */
internal class RoleModels(
    settings: ModelSettings,
    private val adapters: OpenAiAdapters,
) {
    private val snapshot: ModelSettings =
        settings.copy(
            providers = settings.providers.map { it.copy(models = it.models.toList()) },
            roles = settings.roles.toMap(),
        )

    init {
        val problem = snapshot.problem()
        require(problem == null) { "model settings cannot be used: ${problem?.kind} at ${problem?.where}" }
    }

    /** The provider and model that serve [role]. */
    fun target(role: ModelRole): RoleTarget {
        val (provider, model) = checkNotNull(snapshot.target(snapshot.binding(role))) { "validated on creation" }
        return RoleTarget(provider, model)
    }

    /** A new model for [role] reading images from [blobs] (one per session, model-layer §2). */
    fun model(
        role: ModelRole,
        blobs: BlobSource,
    ): ChatModel {
        val target = target(role)
        return adapters.model(
            target.provider.profile,
            target.model.id,
            target.model.capabilities,
            blobs,
            target.provider.wire,
        )
    }
}

/** What serves one role: the provider with its request options, and the model. */
internal data class RoleTarget(
    val provider: StoredProvider,
    val model: StoredModel,
)
