package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ProviderProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI Responses (model-layer §3.2): `POST {baseUrl}/responses`, stateless and streamed. Wire
 * mapping: [ResponsesWire]; stream assembly: [ResponsesStream].
 *
 * With [Credential.ChatGptPlan] the request is cut to the plan channel's constraints ([PlanMode])
 * and a 401 gets one fresh token from the resolver before failing as [ModelError.SignInExpired].
 * When the provider answers 400 about `include` / `reasoning.encrypted_content` (models without
 * reasoning), the request is sent once more without `include`, which stays off for this instance.
 * All other retries are the caller's (`requestError`). Create instances with [OpenAiAdapters.model].
 */
public class ResponsesModel internal constructor(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    private val blobs: BlobSource,
    private val env: AdapterEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/responses".toHttpUrl()
    private val headers = profile.httpHeaders()
    private val plan = profile.credential is Credential.ChatGptPlan
    private val includeReasoning = AtomicBoolean(true)
    private val wire =
        ResponsesWire(profile.id, info.model, info.capabilities.vision, info.capabilities.reasoningLevels)

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        WireSupport.preflight(request)
        val images = if (info.capabilities.vision) ImageData.load(request, blobs) else ImageData.NONE
        var secret = env.secret(profile.credential, rejected = false)
        // Every secret sent so far: after a refresh the rejected token may still be echoed.
        var redactor = Redactor(listOfNotNull(secret))
        var include = includeReasoning.get()
        var refreshed = false
        while (true) {
            val built = wire.body(request, images, include)
            val body = if (plan) PlanMode.enforce(built) else built
            val post = env.post(endpoint, headers, secret, body)
            val exchange = env.exchange(post, redactor) { read(it, redactor, onDelta) }
            if (exchange is Exchange.Done) return exchange.value
            val failure = (exchange as Exchange.Failed).failure
            when {
                failure.status == Status.UNAUTHORIZED && plan && refreshed -> {
                    throw ModelError.SignInExpired()
                }

                failure.status == Status.UNAUTHORIZED && plan -> {
                    refreshed = true
                    secret = env.secret(profile.credential, rejected = true)
                    redactor = redactor.with(secret)
                }

                include && rejectsInclude(failure, redactor) -> {
                    include = false
                    includeReasoning.set(false)
                }

                else -> {
                    throw env.error(failure, plan, redactor)
                }
            }
        }
    }

    private suspend fun read(
        reply: HttpReply,
        redactor: Redactor,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val stream = ResponsesStream(profile.id, info.model, plan, redactor, onDelta)
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

    private fun rejectsInclude(
        failure: HttpFailure,
        redactor: Redactor,
    ): Boolean {
        if (failure.status != Status.BAD_REQUEST) return false
        val error = ProviderError.parse(failure.body, redactor)
        val message = error.message.lowercase(Locale.ROOT)
        return error.param == "include" || "encrypted_content" in message || "include" == error.code
    }
}
