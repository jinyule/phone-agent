package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OpenAI Chat Completions and compatible gateways (model-layer §3.1): `POST {baseUrl}/chat/completions`,
 * always streamed. Wire mapping: [ChatCompletionsWire]; stream assembly: [ChatCompletionsStream].
 *
 * One `complete()` is one HTTP request, shaped by the request and its wire snapshot and nothing
 * else ([WireRules]): [prepare] gives the snapshot of the [WireOptions] given at creation, and
 * there is no mutable state, so concurrent calls and later calls send the same bytes for the same
 * request, and a request read back from a log sends what it sent then. Every failure goes to the
 * caller (`requestError`), which retries and logs; a 400 about an optional field is
 * [ModelError.InvalidRequest] with the provider's `param`. Create instances with
 * [OpenAiAdapters.model].
 */
public class ChatCompletionsModel internal constructor(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    options: WireOptions,
    private val blobs: BlobSource,
    private val env: AdapterEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/chat/completions".toHttpUrl()
    private val rules = WireRules.of(profile, info.capabilities, options)

    override fun prepare(reasoning: ReasoningLevel?): RequestSettings = rules.prepare(reasoning)

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val sent = rules.accept(request)
        WireSupport.preflight(request)
        val images = if (sent.vision) ImageData.load(request, blobs) else ImageData.NONE
        val secret = env.secret(profile.credential, rejected = false)
        val redactor = env.redactor(secret)
        val body = ChatCompletionsWire(profile.id, info.model, sent.wire).body(request, images)
        val post = env.post(endpoint, sent.headers, secret, body)
        return when (val exchange = env.exchange(post, redactor) { read(request, sent, it, redactor, onDelta) }) {
            is Exchange.Done -> exchange.value
            is Exchange.Failed -> throw env.error(exchange.failure, false, redactor)
        }
    }

    private fun reading(
        request: ChatRequest,
        sent: SentWire,
    ): ChatReading =
        ChatReading(
            listOfNotNull(sent.wire.reasoningField).ifEmpty { DEFAULT_REASONING_FIELDS },
            request.tools.mapTo(HashSet()) { it.name },
            sent.leakedCalls,
        )

    private suspend fun read(
        request: ChatRequest,
        sent: SentWire,
        reply: HttpReply,
        redactor: Redactor,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val stream =
            ChatCompletionsStream(profile.id, info.model, reading(request, sent), env.newCallId, redactor, onDelta)
        return try {
            stream.readAll(reply)
        } catch (e: ModelError) {
            // Argument text shown before the failure may hold a secret only the whole text reveals.
            throw stream.failed(e)
        } catch (e: IllegalArgumentException) {
            // Provider values that break neutral-type invariants (ids, counts) are protocol violations,
            // named by the failure's class: its message is not quoted (model-layer §2).
            throw stream.failed(ModelError.BadResponse(INVARIANT, Unquoted.bare(e)))
        }
    }
}

/** Reads the whole body of [reply] into this stream and returns its reply. */
private suspend fun ChatCompletionsStream.readAll(reply: HttpReply): ChatResponse {
    reply.readEvents(::accept, ::acceptJson)
    return finish()
}

/** Fields probed when the profile names none (DeepSeek/Qwen/Kimi style, then Ollama/OpenRouter style). */
private val DEFAULT_REASONING_FIELDS = listOf("reasoning_content", "reasoning")
