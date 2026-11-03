package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ProviderProfile
import kotlinx.serialization.Serializable

/**
 * What the adapters send for one provider profile besides the request itself (model-layer §3.1,
 * §3.2). The options are fixed inputs: a model reads them when it is created and never changes
 * them, whatever a reply says. "Test and save" finds what the provider accepts
 * ([ProviderProbe.testAndSave], [ProbeResult.Passed.wire]); the app stores the result with the
 * profile and passes it to [OpenAiAdapters.model]. A provider that later refuses one of them fails
 * with [dev.phoneagent.core.model.ModelError.InvalidRequest] naming the parameter, and testing the
 * profile again fixes the options.
 *
 * The serial field names here, in [ChatQuirks] and in [ResponsesQuirks] are the format the app
 * stores them in (`model-settings.json`): renaming one means raising that file's version and
 * migrating it.
 *
 * @property chat Chat Completions request shape.
 * @property responses Responses request shape.
 */
@Serializable
public data class WireOptions(
    val chat: ChatQuirks = ChatQuirks.DEFAULT,
    val responses: ResponsesQuirks = ResponsesQuirks(),
) {
    /**
     * The leaked-call formats [profile]'s route may recover ([ChatQuirks.recoverLeakedCalls]), and
     * so the only ones a settings switch may offer: on a preset route (the preset found as
     * [defaultsFor] finds it, official hosts included) its [ProviderPreset.leakedCallFormats]; on
     * a custom route what [ChatQuirks.leakedCallFormats] declares.
     */
    public fun allowedLeakedCalls(profile: ProviderProfile): Set<LeakedCallFormat> =
        presetOf(profile)?.leakedCallFormats ?: chat.leakedCallFormats

    /**
     * Throws [IllegalArgumentException] unless these options fit [profile]'s route: a preset route
     * declares no formats of its own, and every recovered format is one [allowedLeakedCalls] allows.
     * Models and "test and save" check it before any request; the app also checks saved options
     * with it when it loads them.
     */
    public fun requireFits(profile: ProviderProfile) {
        val preset = presetOf(profile)
        require(preset == null || chat.leakedCallFormats.isEmpty()) {
            "Provider ${profile.id}: on a preset route (${preset?.id}) the preset declares the leaked-call " +
                "formats, the options may not"
        }
        val refused = chat.recoverLeakedCalls - allowedLeakedCalls(profile)
        require(refused.isEmpty()) {
            "Provider ${profile.id}: recovering leaked calls in $refused is not allowed on this route " +
                "(allowed: ${allowedLeakedCalls(profile)})"
        }
    }

    /** Starting points. */
    public companion object {
        /**
         * The options of [profile]'s preset; a custom profile that points at a preset's official
         * host gets that preset's (model-layer §4.1 step 5); anything else the defaults.
         */
        public fun defaultsFor(profile: ProviderProfile): WireOptions =
            WireOptions(chat = presetOf(profile)?.chat ?: ChatQuirks.DEFAULT)

        /** The preset [profile]'s route is: its own, else the one whose official host it points at. */
        private fun presetOf(profile: ProviderProfile): ProviderPreset? =
            profile.preset?.let(ProviderPresets::byId) ?: ProviderPresets.matchingOfficial(profile.baseUrl)
    }
}

/**
 * Responses request shape of one provider.
 *
 * @property includeEncryptedReasoning ask for replayable reasoning
 *   (`include: ["reasoning.encrypted_content"]`); models without reasoning refuse it.
 */
@Serializable
public data class ResponsesQuirks(
    val includeEncryptedReasoning: Boolean = true,
)
