package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ProviderProfile

/**
 * What the adapters send for one provider profile besides the request itself (model-layer §3.1,
 * §3.2). The options are fixed inputs: a model reads them when it is created and never changes
 * them, whatever a reply says. "Test and save" finds what the provider accepts
 * ([ProviderProbe.testAndSave], [ProbeResult.Passed.wire]); the app stores the result with the
 * profile and passes it to [OpenAiAdapters.model]. A provider that later refuses one of them fails
 * with [dev.phoneagent.core.model.ModelError.InvalidRequest] naming the parameter, and testing the
 * profile again fixes the options.
 *
 * @property chat Chat Completions request shape.
 * @property responses Responses request shape.
 */
public data class WireOptions(
    val chat: ChatQuirks = ChatQuirks.DEFAULT,
    val responses: ResponsesQuirks = ResponsesQuirks(),
) {
    /** Starting points. */
    public companion object {
        /**
         * The options of [profile]'s preset; a custom profile that points at a preset's official
         * host gets that preset's (model-layer §4.1 step 5); anything else the defaults.
         */
        public fun defaultsFor(profile: ProviderProfile): WireOptions {
            val preset = profile.preset?.let(ProviderPresets::byId) ?: ProviderPresets.matchingOfficial(profile.baseUrl)
            return WireOptions(chat = preset?.chat ?: ChatQuirks.DEFAULT)
        }
    }
}

/**
 * Responses request shape of one provider.
 *
 * @property includeEncryptedReasoning ask for replayable reasoning
 *   (`include: ["reasoning.encrypted_content"]`); models without reasoning refuse it.
 */
public data class ResponsesQuirks(
    val includeEncryptedReasoning: Boolean = true,
)
