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
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI Chat Completions and compatible gateways (model-layer §3.1): `POST {baseUrl}/chat/completions`,
 * always streamed. Wire mapping: [ChatCompletionsWire]; stream assembly: [ChatCompletionsStream].
 *
 * Besides one HTTP exchange per call, the adapter negotiates two optional fields: when the provider
 * answers 400 naming `parallel_tool_calls` or `stream_options`, the same request is sent once more
 * without it and the field stays off for this instance. Nothing model-visible changes. All other
 * retries are the caller's (`requestError`). Create instances with [OpenAiAdapters.model].
 */
public class ChatCompletionsModel internal constructor(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    quirks: ChatQuirks,
    private val blobs: BlobSource,
    private val env: AdapterEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/chat/completions".toHttpUrl()
    private val headers = profile.httpHeaders()
    private val options = AtomicReference(ChatWireOptions(quirks))
    private val reasoningFields = listOfNotNull(profile.reasoningField).ifEmpty { DEFAULT_REASONING_FIELDS }
    private val wire =
        ChatCompletionsWire(
            profile.id,
            info.model,
            profile.reasoningField,
            profile.echoReasoning,
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
        var current = options.get()
        while (true) {
            val body = wire.body(request, images, current)
            when (val exchange = env.exchange(env.post(endpoint, headers, secret, body)) { read(it, onDelta) }) {
                is Exchange.Done -> {
                    return exchange.value
                }

                is Exchange.Failed -> {
                    current = without(current, exchange.failure) ?: throw env.error(exchange.failure, plan = false)
                    options.set(current)
                }
            }
        }
    }

    private suspend fun read(
        reply: HttpReply,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val stream = ChatCompletionsStream(profile.id, info.model, reasoningFields, env.newCallId, onDelta)
        try {
            reply.readEvents(stream::accept, stream::acceptJson)
            return stream.finish()
        } catch (e: IllegalArgumentException) {
            // Provider values that break neutral-type invariants (ids, counts) are protocol violations.
            throw ModelError.BadResponse("reply violates a model invariant: ${e.message}", e)
        }
    }

    /** [current] minus the optional field a 400 names, or `null` when the failure is not about one. */
    private fun without(
        current: ChatWireOptions,
        failure: HttpFailure,
    ): ChatWireOptions? {
        if (failure.status != Status.BAD_REQUEST) return null
        val body = failure.body.lowercase(Locale.ROOT)
        return when {
            current.parallelToolCalls && "parallel_tool_calls" in body -> current.copy(parallelToolCalls = false)
            current.streamUsage && "stream_options" in body -> current.copy(streamUsage = false)
            else -> null
        }
    }
}

/** Fields probed when the profile names none (DeepSeek/Qwen/Kimi style, then Ollama/OpenRouter style). */
private val DEFAULT_REASONING_FIELDS = listOf("reasoning_content", "reasoning")
