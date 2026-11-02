package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.kind
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Anthropic Messages (model-layer §3.3): `POST {baseUrl}/v1/messages`, streamed. Wire mapping:
 * [MessagesWire]; settings: [WireRules] from `request.wire`; stream assembly: [MessagesStream];
 * errors: [AnthropicErrors].
 *
 * One `complete()` is exactly one HTTP request, and nothing about it depends on earlier calls: an
 * API key is not refreshed, so a 401 is [ModelError.Auth]; every failure goes to the caller
 * (`requestError`), including a stale thinking signature ([ModelError.PrefixBindingMismatch]).
 */
internal class MessagesModel(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    private val blobs: BlobSource,
    private val env: AnthropicEnv,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/v1/messages".toHttpUrl()
    private val wire = MessagesWire(profile.id, info.model)

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val rules = WireRules.of(request, profile.credential.kind)
        val images = if (rules.inlineImages) ImageData.load(request, blobs) else ImageData.NONE
        val body = wire.body(request, rules, images)
        val secret = env.secret(profile.credential)
        val redactor = Redactor(listOfNotNull(secret))
        val post = env.post(endpoint, rules, wire.betas(request, rules), secret, body)
        val outcome =
            env.transport.send(post, redactor) { reply ->
                if (reply.successful) {
                    Outcome.Reply(read(reply, redactor, onDelta))
                } else {
                    Outcome.Failure(reply.status, reply.headers, redactor.text(reply.text()))
                }
            }
        return when (outcome) {
            is Outcome.Reply -> {
                outcome.response
            }

            is Outcome.Failure -> {
                throw AnthropicErrors.fromHttp(
                    outcome.status,
                    outcome.headers,
                    outcome.body,
                    env.clock.epochMillis(),
                    redactor,
                )
            }
        }
    }

    private suspend fun read(
        reply: HttpReply,
        redactor: Redactor,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val stream = MessagesStream(profile.id, info.model, redactor, onDelta)
        return try {
            stream.read(reply)
        } catch (e: IllegalArgumentException) {
            // Provider values that break neutral-type invariants (ids, counts) are protocol violations.
            throw ModelError.BadResponse(
                redactor.text("reply violates a model invariant: ${e.message}"),
                redactor.cause(e),
            )
        }
    }

    private suspend fun MessagesStream.read(reply: HttpReply): ChatResponse {
        reply.readEvents(::accept, ::acceptJson)
        return finish()
    }

    /** The result of the one HTTP exchange; failures are mapped after the response is closed. */
    private sealed interface Outcome {
        class Reply(
            val response: ChatResponse,
        ) : Outcome

        class Failure(
            val status: Int,
            val headers: Headers,
            val body: String,
        ) : Outcome
    }
}
