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

/**
 * OpenAI Responses (model-layer §3.2): `POST {baseUrl}/responses`, stateless and streamed. Wire
 * mapping: [ResponsesWire]; stream assembly: [ResponsesStream].
 *
 * One `complete()` is one HTTP request, shaped by the [WireOptions] given at creation and nothing
 * else (no mutable state). The one exception keeps the request as it is: with
 * [Credential.ChatGptPlan] the request is cut to the plan channel's constraints ([PlanMode]) and a
 * 401 gets one fresh token from the resolver, then fails as [ModelError.SignInExpired]. Every
 * other failure goes to the caller (`requestError`); a 400 about `include` is
 * [ModelError.InvalidRequest] with the provider's `param`. Create instances with
 * [OpenAiAdapters.model].
 */
public class ResponsesModel internal constructor(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    options: WireOptions,
    private val blobs: BlobSource,
    private val env: AdapterEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/responses".toHttpUrl()
    private val inputs = WireInputs.of(profile, options)
    private val plan = inputs.plan
    private val wire =
        ResponsesWire(profile.id, info.model, info.capabilities.vision, info.capabilities.reasoningLevels)

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        WireSupport.preflight(request)
        val images = if (info.capabilities.vision) ImageData.load(request, blobs) else ImageData.NONE
        val built = wire.body(request, images, inputs.options.responses.includeEncryptedReasoning)
        val body = if (plan) PlanMode.enforce(built) else built
        var secret = env.secret(profile.credential, rejected = false)
        // Every secret sent so far: after a refresh the rejected token may still be echoed.
        var redactor = Redactor(listOfNotNull(secret))
        var refreshed = false
        while (true) {
            val post = env.post(endpoint, inputs.headers, secret, body)
            val exchange = env.exchange(post, redactor) { read(it, redactor, onDelta) }
            if (exchange is Exchange.Done) return exchange.value
            val failure = (exchange as Exchange.Failed).failure
            if (failure.status != Status.UNAUTHORIZED || !plan) throw env.error(failure, plan, redactor)
            if (refreshed) throw ModelError.SignInExpired()
            refreshed = true
            secret = env.secret(profile.credential, rejected = true)
            redactor = redactor.with(secret)
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
}
