package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ProviderProfile
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OpenAI Chat Completions and compatible gateways (model-layer §3.1): `POST {baseUrl}/chat/completions`,
 * always streamed. Wire mapping: [ChatCompletionsWire]; stream assembly: [ChatCompletionsStream].
 *
 * One `complete()` is one HTTP request, shaped by the [WireOptions] given at creation and nothing
 * else: there is no mutable state, so concurrent calls and later calls send the same wire for the
 * same request. Every failure goes to the caller (`requestError`), which retries and logs; a 400
 * about an optional field is [ModelError.InvalidRequest] with the provider's `param`. Create
 * instances with [OpenAiAdapters.model].
 */
public class ChatCompletionsModel internal constructor(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    options: WireOptions,
    private val blobs: BlobSource,
    private val env: AdapterEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/chat/completions".toHttpUrl()
    private val inputs = WireInputs.of(profile, options)
    private val reasoningFields = listOfNotNull(inputs.reasoningField).ifEmpty { DEFAULT_REASONING_FIELDS }
    private val wire =
        ChatCompletionsWire(
            profile.id,
            info.model,
            inputs.reasoningField,
            inputs.echoReasoning,
            info.capabilities.vision,
            info.capabilities.reasoningLevels,
        )

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        WireSupport.preflight(request)
        val images = if (info.capabilities.vision) ImageData.load(request, blobs) else ImageData.NONE
        val secret = env.secret(profile.credential, rejected = false)
        val redactor = env.redactor(secret)
        val body = wire.body(request, images, inputs.options.chat)
        val post = env.post(endpoint, inputs.headers, secret, body)
        return when (val exchange = env.exchange(post, redactor) { read(request, it, redactor, onDelta) }) {
            is Exchange.Done -> exchange.value
            is Exchange.Failed -> throw env.error(exchange.failure, false, redactor)
        }
    }

    private fun reading(request: ChatRequest): ChatReading =
        ChatReading(reasoningFields, request.tools.mapTo(HashSet()) { it.name }, inputs.options.chat.recoverLeakedCalls)

    private suspend fun read(
        request: ChatRequest,
        reply: HttpReply,
        redactor: Redactor,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val stream = ChatCompletionsStream(profile.id, info.model, reading(request), env.newCallId, redactor, onDelta)
        try {
            reply.readEvents(stream::accept, stream::acceptJson)
            return stream.finish()
        } catch (e: IllegalArgumentException) {
            // Provider values that break neutral-type invariants (ids, counts) are protocol violations.
            throw ModelError.BadResponse(
                redactor.text("reply violates a model invariant: ${e.message}"),
                redactor.cause(e),
            )
        }
    }
}

/** Fields probed when the profile names none (DeepSeek/Qwen/Kimi style, then Ollama/OpenRouter style). */
private val DEFAULT_REASONING_FIELDS = listOf("reasoning_content", "reasoning")
