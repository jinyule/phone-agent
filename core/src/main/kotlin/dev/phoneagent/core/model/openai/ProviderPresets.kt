package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ProviderProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * A built-in provider template (model-layer §3.1): where it lives, which protocol, where reasoning
 * comes back, and its known quirks. [facts] say how sure each statement is.
 *
 * @property id stable preset id, stored in `ProviderProfile.preset`.
 * @property displayName brand name; the app localizes by [id].
 * @property protocol wire protocol.
 * @property baseUrl default base URL (local servers: the address on the machine that runs them).
 * @property needsKey whether an API key is required.
 * @property reasoningField Chat Completions field carrying reasoning, or `null`.
 * @property echoReasoning send that reasoning back in later requests.
 * @property chat Chat Completions request quirks.
 * @property officialHosts hosts that identify this provider in a custom profile.
 * @property facts known behavior, each with its source.
 */
public data class ProviderPreset(
    val id: String,
    val displayName: String,
    val protocol: ModelProtocol,
    val baseUrl: String,
    val needsKey: Boolean,
    val reasoningField: String?,
    val echoReasoning: Boolean,
    val chat: ChatQuirks,
    val officialHosts: Set<String>,
    val facts: List<PresetFact>,
) {
    /** A profile from this preset; validated like any profile. */
    public fun profile(
        id: ProviderId,
        credential: Credential,
        baseUrl: String = this.baseUrl,
        displayName: String = this.displayName,
    ): ProviderProfile =
        ProviderProfile(
            id = id,
            displayName = displayName,
            protocol = protocol,
            baseUrl = baseUrl,
            credential = credential,
            reasoningField = reasoningField,
            echoReasoning = echoReasoning,
            preset = this.id,
        )
}

/** One statement about a provider and how it is known. */
public data class PresetFact(
    /** The statement. */
    val text: String,
    /** Its source. */
    val status: FactStatus,
)

/** How a [PresetFact] is known. */
public enum class FactStatus {
    /** Checked against the live service by PhoneAgent's end-to-end tests. */
    VERIFIED,

    /** Stated in the provider's documentation; not checked live by us. */
    DOCUMENTED,

    /** Inferred, or reported by third parties; treat as a guess until verified. */
    UNVERIFIED,
}

/** The built-in presets (model-layer §3.1). */
public object ProviderPresets {
    private fun verified(text: String) = PresetFact(text, FactStatus.VERIFIED)

    private fun documented(text: String) = PresetFact(text, FactStatus.DOCUMENTED)

    private fun unverified(text: String) = PresetFact(text, FactStatus.UNVERIFIED)

    private val documentedUsage = documented("Streams usage with stream_options.include_usage.")
    private val noParallelParam =
        unverified("parallel_tool_calls is not documented, so it is not sent.")

    /** OpenAI Responses (API key). */
    public val OPENAI: ProviderPreset =
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            protocol = ModelProtocol.OPENAI_RESPONSES,
            baseUrl = "https://api.openai.com/v1",
            needsKey = true,
            reasoningField = null,
            echoReasoning = false,
            chat = ChatQuirks(maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS),
            officialHosts = setOf("api.openai.com"),
            facts =
                listOf(
                    documented(
                        "Stateless Responses: store:false, encrypted reasoning returned via include and sent back.",
                    ),
                    documented("Non-reasoning models reject include=reasoning.encrypted_content (400)."),
                    documented("Chat Completions takes max_completion_tokens; max_tokens is deprecated."),
                ),
        )

    /** DeepSeek. */
    public val DEEPSEEK: ProviderPreset =
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.deepseek.com",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(parallelToolCalls = false, recoverLeakedCalls = true),
            officialHosts = setOf("api.deepseek.com"),
            facts =
                listOf(
                    documented("Thinking mode returns reasoning_content; it must be sent back on tool-call steps."),
                    unverified("Accepts reasoning_effort in thinking mode."),
                    unverified(
                        "Some models leak tool calls as DSML markup at the end of the text; the adapter " +
                            "recovers complete calls of declared tools.",
                    ),
                    noParallelParam,
                    documentedUsage,
                ),
        )

    /** Alibaba Cloud Model Studio (DashScope) in OpenAI-compatible mode. */
    public val QWEN: ProviderPreset =
        ProviderPreset(
            id = "qwen",
            displayName = "Qwen (DashScope)",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(reasoningEffort = ReasoningEffortStyle.NONE, recoverLeakedCalls = true),
            officialHosts = setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"),
            facts =
                listOf(
                    documented("International accounts use dashscope-intl.aliyuncs.com."),
                    unverified(
                        "Qwen-family models sometimes write <tool_call> JSON blocks into the text; the adapter " +
                            "recovers complete calls of declared tools.",
                    ),
                    documented("Thinking is switched with enable_thinking, not reasoning_effort."),
                    documented("Accepts parallel_tool_calls."),
                    documented("Tool-call continuation fragments carry an empty id."),
                    unverified("Echoing reasoning_content is accepted (and may be ignored)."),
                    documentedUsage,
                ),
        )

    /** Moonshot Kimi. */
    public val KIMI: ProviderPreset =
        ProviderPreset(
            id = "kimi",
            displayName = "Kimi (Moonshot)",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.moonshot.cn/v1",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(parallelToolCalls = false, reasoningEffort = ReasoningEffortStyle.NONE),
            officialHosts = setOf("api.moonshot.cn", "api.moonshot.ai"),
            facts =
                listOf(
                    documented("International accounts use api.moonshot.ai."),
                    documented("Thinking models need reasoning_content kept on tool-call steps."),
                    documented("Streaming usage arrives inside the final choice (usage.cached_tokens)."),
                    noParallelParam,
                ),
        )

    /** Zhipu GLM (BigModel). */
    public val GLM: ProviderPreset =
        ProviderPreset(
            id = "glm",
            displayName = "GLM (Zhipu)",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(parallelToolCalls = false, reasoningEffort = ReasoningEffortStyle.NONE),
            officialHosts = setOf("open.bigmodel.cn", "api.z.ai"),
            facts =
                listOf(
                    documented("International accounts use https://api.z.ai/api/paas/v4."),
                    documented("Thinking is switched with thinking.type, not reasoning_effort."),
                    documented(
                        "finish_reason \"sensitive\" (filtered) and \"network_error\" (upstream failure) end streams.",
                    ),
                    unverified("Echoing reasoning_content keeps interleaved thinking."),
                    noParallelParam,
                ),
        )

    /** Volcengine Ark (Doubao). */
    public val DOUBAO: ProviderPreset =
        ProviderPreset(
            id = "doubao",
            displayName = "Doubao (Volcengine Ark)",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(parallelToolCalls = false, reasoningEffort = ReasoningEffortStyle.NONE),
            officialHosts = setOf("ark.cn-beijing.volces.com"),
            facts =
                listOf(
                    documented("Models are named by model id or by endpoint id (ep-…)."),
                    documented("Thinking is switched with thinking.type, not reasoning_effort."),
                    unverified("Echoing reasoning_content on tool-call steps is accepted."),
                    unverified("GET /models may not list endpoint ids."),
                    noParallelParam,
                ),
        )

    /** SiliconFlow. */
    public val SILICONFLOW: ProviderPreset =
        ProviderPreset(
            id = "siliconflow",
            displayName = "SiliconFlow",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.siliconflow.cn/v1",
            needsKey = true,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            chat = ChatQuirks(parallelToolCalls = false, reasoningEffort = ReasoningEffortStyle.NONE),
            officialHosts = setOf("api.siliconflow.cn", "api.siliconflow.com"),
            facts =
                listOf(
                    documented("International accounts use api.siliconflow.com."),
                    documented(
                        "Streams repeat the call id and send name \"\" on continuation fragments; usage is cumulative.",
                    ),
                    documented("Errors use a flat {code, message} body (e.g. 30001 = balance)."),
                    unverified("Echoing reasoning_content is accepted."),
                    noParallelParam,
                ),
        )

    /** OpenRouter. */
    public val OPENROUTER: ProviderPreset =
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://openrouter.ai/api/v1",
            needsKey = true,
            reasoningField = "reasoning",
            echoReasoning = false,
            chat = ChatQuirks(reasoningEffort = ReasoningEffortStyle.OPENROUTER),
            officialHosts = setOf("openrouter.ai"),
            facts =
                listOf(
                    documented("Sends \": OPENROUTER PROCESSING\" keep-alive comments."),
                    documented("Reasoning level goes in reasoning.effort."),
                    documented(
                        "Some upstreams need reasoning_details echoed; unsupported, so reasoning is not echoed.",
                    ),
                    documented("Mid-stream upstream failures arrive as an error chunk."),
                ),
        )

    /** Ollama's OpenAI compatibility endpoint. */
    public val OLLAMA: ProviderPreset =
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://127.0.0.1:11434/v1",
            needsKey = false,
            reasoningField = "reasoning",
            echoReasoning = true,
            chat = ChatQuirks(recoverLeakedCalls = true),
            officialHosts = emptySet(),
            facts =
                listOf(
                    documented(
                        "On a phone, 127.0.0.1 is the phone itself: use the LAN address of the Ollama computer.",
                    ),
                    unverified(
                        "When a model's chat template does not parse its tool calls, they arrive as <tool_call> " +
                            "or DSML text; the adapter recovers complete calls of declared tools.",
                    ),
                    verified("Thinking models stream delta.reasoning (2026-11-01, qwen3.8)."),
                    verified(
                        "Accepts parallel_tool_calls:false and stream_options.include_usage; a tool call arrives " +
                            "whole in one chunk with finish_reason \"tool_calls\" (2026-11-01, qwen3.8).",
                    ),
                    unverified("Older releases report finish_reason \"stop\" for tool-call turns."),
                ),
        )

    /** LM Studio's OpenAI compatibility endpoint. */
    public val LM_STUDIO: ProviderPreset =
        ProviderPreset(
            id = "lmstudio",
            displayName = "LM Studio",
            protocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "http://127.0.0.1:1234/v1",
            needsKey = false,
            reasoningField = "reasoning_content",
            echoReasoning = false,
            chat = ChatQuirks(reasoningEffort = ReasoningEffortStyle.NONE),
            officialHosts = emptySet(),
            facts =
                listOf(
                    documented(
                        "On a phone, 127.0.0.1 is the phone itself: use the LAN address of the LM Studio computer.",
                    ),
                    documented("reasoning_content appears only with the \"separate reasoning\" setting on."),
                    unverified("Whether echoed reasoning is used is unknown, so it is not echoed."),
                ),
        )

    /** All presets, in the order the app offers them. */
    public val ALL: List<ProviderPreset> =
        listOf(OPENAI, DEEPSEEK, QWEN, KIMI, GLM, DOUBAO, SILICONFLOW, OPENROUTER, OLLAMA, LM_STUDIO)

    /** The preset with [id], or `null`. */
    public fun byId(id: String): ProviderPreset? = ALL.firstOrNull { it.id == id }

    /**
     * The preset whose official host [baseUrl] points at, or `null`: a custom profile pointing at
     * an official endpoint should use the preset instead (model-layer §4.1 step 5).
     */
    public fun matchingOfficial(baseUrl: String): ProviderPreset? {
        val host =
            baseUrl
                .trim()
                .toHttpUrlOrNull()
                ?.host
                ?.lowercase(Locale.ROOT) ?: return null
        return ALL.firstOrNull { host in it.officialHosts }
    }
}
