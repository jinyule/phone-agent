package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.model.clampTo
import dev.phoneagent.core.model.kind
import dev.phoneagent.core.model.wire
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import java.util.Locale

/**
 * Version of the OpenAI adapters' mapping rules: a neutral request plus its [WireProfile] to the
 * bytes sent ([WireProfile.mappingVersion]). Bump it whenever the same inputs would send other bytes.
 */
internal const val OPENAI_MAPPING_VERSION: Int = 1

/**
 * The [WireProfile.options] entry naming the leaked-call formats a Chat Completions reply recovers
 * ([ChatQuirks.recoverLeakedCalls]): their lowercase names, comma-separated, in declaration order.
 */
internal const val LEAKED_CALL_FORMATS: String = "leaked_call_formats"

/**
 * The wire snapshot rules of one model (model-layer §2 "线路快照"): what [prepare] gives and what
 * [accept] takes. Built once, when the model is created, from the profile (its headers copied by
 * [ProviderProfile.wire]), its [WireOptions] (the leaked-call formats it allows copied here) and
 * the model's own copy of its capabilities ([OpenAiAdapters.model]), so a later change of the
 * live settings reaches neither a snapshot nor what [accept] takes; nothing writes to what it
 * keeps, and every snapshot it gives shares it. [prepare]
 * clamps the level with [clampTo] and maps it in the same step; everything else in the snapshot
 * is fixed for the life of the model.
 *
 * - Chat Completions: the reasoning field and its echo, the profile's headers, images inline only
 *   with vision, the output cap in [ChatQuirks.maxTokensField], the level in the
 *   [ChatQuirks.reasoningEffort] style, `parallel_tool_calls:false` and `stream_options` as
 *   [ChatQuirks] says, and the recovered leaked-call formats in [LEAKED_CALL_FORMATS].
 * - Responses: `reasoning.effort` (its profiles name no reasoning field),
 *   `max_output_tokens` (none with ChatGPT plan sign-in, whose channel takes no cap),
 *   `parallel_tool_calls:false`, and `include` as [ResponsesQuirks] says.
 */
internal class WireRules private constructor(
    private val protocol: ModelProtocol,
    private val base: WireProfile,
    private val levels: List<ReasoningLevel>?,
    private val effortStyle: ReasoningEffortStyle,
    private val allowedLeakedCalls: Set<LeakedCallFormat>,
) {
    /** What [ChatModel.prepare][dev.phoneagent.core.model.ChatModel.prepare] gives for [reasoning]. */
    fun prepare(reasoning: ReasoningLevel?): RequestSettings {
        val level = reasoning?.clampTo(levels)
        val effort = level?.let { effortField()?.let { field -> WireSetting(field, it.wireValue) } }
        return RequestSettings(
            level,
            base.copy(reasoningEffort = effort),
        )
    }

    private fun effortField(): String? =
        when {
            protocol == ModelProtocol.OPENAI_RESPONSES -> NESTED_EFFORT
            effortStyle == ReasoningEffortStyle.REASONING_EFFORT -> TOP_LEVEL_EFFORT
            effortStyle == ReasoningEffortStyle.OPENROUTER -> NESTED_EFFORT
            else -> null
        }

    /**
     * [request]'s snapshot as this model sends it. Throws [ModelError.InvalidRequest], before
     * anything is sent, for a snapshot these rules cannot have given: another mapping version or
     * credential kind (the contract of [ChatModel.complete][dev.phoneagent.core.model.ChatModel.complete]),
     * and, for a snapshot read back from a log, a header value HTTP cannot carry, a field or value
     * these rules do not send, an option they do not know, or leaked-call formats the route does
     * not allow.
     */
    fun accept(request: ChatRequest): SentWire {
        val wire = request.wire
        if (wire.mappingVersion != OPENAI_MAPPING_VERSION) {
            invalid(
                "wire.mappingVersion",
                "this adapter maps version $OPENAI_MAPPING_VERSION, not ${wire.mappingVersion}",
            )
        }
        if (wire.credential != base.credential) {
            invalid("wire.credential", "the profile sends ${base.credential}, not ${wire.credential}")
        }
        val headers =
            try {
                wire.headers.toHeaders()
            } catch (ignoredInvalid: IllegalArgumentException) {
                // OkHttp's message quotes the value it refused; the field is named instead.
                invalid("wire.headers", "a header value is not valid HTTP")
            }
        checkFields(wire)
        return SentWire(wire, headers, leakedCalls(wire))
    }

    private fun checkFields(wire: WireProfile) {
        val chat = protocol == ModelProtocol.OPENAI_CHAT_COMPLETIONS
        wire.reasoningEffort?.let { effort ->
            val fields = if (chat) setOf(TOP_LEVEL_EFFORT, NESTED_EFFORT) else setOf(NESTED_EFFORT)
            if (effort.field !in fields) invalid("wire.reasoningEffort", "this adapter does not send ${effort.field}")
            if (effort.value !in EFFORT_VALUES) invalid("wire.reasoningEffort", "unknown level ${effort.value}")
        }
        wire.maxTokensField?.let { field ->
            val fields = if (chat) MaxTokensField.entries.map { it.wireName } else listOf(RESPONSES_CAP)
            if (field !in fields) invalid("wire.maxTokensField", "this adapter does not send $field")
        }
        val unknown = wire.options.keys - if (chat) setOf(LEAKED_CALL_FORMATS) else emptySet()
        if (unknown.isNotEmpty()) invalid("wire.options", "unknown options $unknown")
        val foreign =
            if (chat) {
                listOf("includeEncryptedReasoning" to wire.includeEncryptedReasoning)
            } else {
                listOf(
                    "reasoningField" to (wire.reasoningField != null),
                    "streamOptions" to wire.streamOptions,
                    "recoverLeakedToolCalls" to wire.recoverLeakedToolCalls,
                )
            }
        foreign.firstOrNull { it.second }?.let { (field, _) ->
            invalid("wire.$field", "this protocol has no such setting")
        }
    }

    private fun leakedCalls(wire: WireProfile): Set<LeakedCallFormat> {
        val names = wire.options[LEAKED_CALL_FORMATS]?.split(',').orEmpty()
        val formats =
            names.mapTo(LinkedHashSet()) { name ->
                LeakedCallFormat.entries.firstOrNull { it.optionName == name }
                    ?: invalid("wire.options", "unknown leaked-call format $name")
            }
        if (formats.isEmpty() == wire.recoverLeakedToolCalls) {
            invalid("wire.recoverLeakedToolCalls", "it must be set exactly when $LEAKED_CALL_FORMATS names formats")
        }
        val refused = formats - allowedLeakedCalls
        if (refused.isNotEmpty()) invalid("wire.options", "this route does not allow recovering $refused")
        return formats
    }

    companion object {
        /**
         * The rules of a [profile] model with [capabilities] and [options]; throws
         * [IllegalArgumentException] for [options] that do not fit the route
         * ([WireOptions.requireFits]).
         */
        fun of(
            profile: ProviderProfile,
            capabilities: ModelCapabilities,
            options: WireOptions,
        ): WireRules {
            profile.httpHeaders()
            options.requireFits(profile)
            val vision = if (capabilities.vision) ImageMode.INLINE else ImageMode.PLACEHOLDER
            val plan = profile.credential.kind == CredentialKind.CHATGPT_PLAN
            val own = profile.wire(OPENAI_MAPPING_VERSION).copy(images = vision)
            val chat = options.chat
            val formats = chat.recoverLeakedCalls.sortedBy { it.ordinal }
            val base =
                if (profile.protocol == ModelProtocol.OPENAI_RESPONSES) {
                    own.copy(
                        maxTokensField = if (plan) null else RESPONSES_CAP,
                        disableParallelToolCalls = true,
                        includeEncryptedReasoning = options.responses.includeEncryptedReasoning,
                    )
                } else {
                    own.copy(
                        maxTokensField = chat.maxTokensField.wireName,
                        disableParallelToolCalls = chat.parallelToolCalls,
                        streamOptions = chat.streamUsage,
                        recoverLeakedToolCalls = formats.isNotEmpty(),
                        options =
                            if (formats.isEmpty()) {
                                emptyMap()
                            } else {
                                mapOf(LEAKED_CALL_FORMATS to formats.joinToString(",") { it.optionName })
                            },
                    )
                }
            return WireRules(
                profile.protocol,
                base,
                capabilities.reasoningLevels,
                chat.reasoningEffort,
                options.allowedLeakedCalls(profile).toSet(),
            )
        }
    }
}

/**
 * A request's snapshot, accepted by [WireRules.accept]: the only source of everything besides the
 * neutral request that shapes the bytes.
 *
 * @property wire the snapshot itself.
 * @property headers its extra headers as HTTP headers.
 * @property leakedCalls the leaked-call formats a Chat Completions reply recovers.
 */
internal class SentWire(
    val wire: WireProfile,
    val headers: Headers,
    val leakedCalls: Set<LeakedCallFormat>,
) {
    /** The credential is ChatGPT plan sign-in. */
    val plan: Boolean get() = wire.credential == CredentialKind.CHATGPT_PLAN

    /** Images are sent as images. */
    val vision: Boolean get() = wire.images == ImageMode.INLINE
}

/** The wire value of this level: its lowercase name. */
internal val ReasoningLevel.wireValue: String get() = name.lowercase(Locale.ROOT)

/** The name of this format in [LEAKED_CALL_FORMATS]. */
internal val LeakedCallFormat.optionName: String get() = name.lowercase(Locale.ROOT)

private fun invalid(
    param: String,
    detail: String,
): Nothing = throw ModelError.InvalidRequest(param, detail)

private const val TOP_LEVEL_EFFORT = "reasoning_effort"
private const val NESTED_EFFORT = "reasoning.effort"
private const val RESPONSES_CAP = "max_output_tokens"
private val EFFORT_VALUES = ReasoningLevel.entries.map { it.wireValue }.toSet()
