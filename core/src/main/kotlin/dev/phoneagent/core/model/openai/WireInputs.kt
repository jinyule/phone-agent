package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ProviderProfile
import okhttp3.Headers

/**
 * Everything besides the request that shapes what goes on the wire: the reasoning field and
 * whether it is echoed, the provider's extra (non-secret) headers, whether the credential is
 * ChatGPT plan sign-in, and the [WireOptions]. Both models read these only through [of], once, in
 * one place, so the source can become the request's own non-secret wire snapshot instead of the
 * live profile.
 *
 * @property reasoningField Chat Completions field carrying reasoning, or `null` when unknown.
 * @property echoReasoning send that reasoning back to the same provider and model.
 * @property headers extra request headers; never credentials.
 * @property plan the credential is [Credential.ChatGptPlan].
 * @property options the request shape options.
 */
internal data class WireInputs(
    val reasoningField: String?,
    val echoReasoning: Boolean,
    val headers: Headers,
    val plan: Boolean,
    val options: WireOptions,
) {
    companion object {
        /** The inputs [profile] implies; throws [IllegalArgumentException] for header values HTTP cannot carry. */
        fun of(
            profile: ProviderProfile,
            options: WireOptions,
        ): WireInputs =
            WireInputs(
                reasoningField = profile.reasoningField,
                echoReasoning = profile.echoReasoning,
                headers = profile.httpHeaders(),
                plan = profile.credential is Credential.ChatGptPlan,
                options = options,
            )
    }
}
