package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.model.clampTo
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
 *
 * [prepare] gives the snapshot of [settings] for [info]'s model behind [profile], frozen when the
 * model is made: the profile's headers are copied then, so a later edit of the profile changes no
 * snapshot. It gives [AnthropicSettings.serverContext] as the request's `contextEdits`
 * (`ClientSide` when it is `null`). Settings that do not fit the endpoint and the model are
 * refused here, on creation ([AnthropicSettings.requireFits], [ModelError.InvalidRequest] naming
 * the setting), since [prepare] never throws.
 *
 * A request whose Claude API betas (server fallback, prefix mismatch behavior, server-side
 * context management) [profile]'s endpoint does not take, because its snapshot and edits were
 * made for another one, is refused before anything is sent rather than sent with fields left
 * out: what goes out must follow from the logged request.
 */
internal class MessagesModel(
    private val profile: ProviderProfile,
    override val info: ModelInfo,
    private val blobs: BlobSource,
    private val env: AnthropicEnv,
    settings: AnthropicSettings,
) : ChatModel {
    private val endpoint = "${profile.baseUrl}/v1/messages".toHttpUrl()
    private val wire = MessagesWire(profile.id, info.model)
    private val official = AnthropicPresets.isOfficial(profile.baseUrl)
    private val snapshot = AnthropicWire.snapshot(profile, info.model, info.capabilities, null, settings)
    private val contextEdits = settings.serverContext ?: ContextEditPolicy.ClientSide

    override fun prepare(reasoning: ReasoningLevel?): RequestSettings {
        val level = reasoning?.clampTo(info.capabilities.reasoningLevels)
        val effort = level?.let { WireSetting(AnthropicWire.EFFORT_FIELD, AnthropicWire.effortOf(it)) }
        return RequestSettings(level, snapshot.copy(reasoningEffort = effort), contextEdits)
    }

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse =
        try {
            send(request, onDelta)
        } catch (overflow: ModelError.ContextOverflow) {
            // The history of this path is append-only, so the failure says why nothing trimmed it.
            throw explained(overflow, request)
        }

    /**
     * [overflow] with the reason it was not prevented (model-layer §5) when [request] had no
     * server-side compaction: this endpoint has none, or the settings turn it off.
     */
    private fun explained(
        overflow: ModelError.ContextOverflow,
        request: ChatRequest,
    ): ModelError.ContextOverflow {
        val compacts = (request.contextEdits as? ContextEditPolicy.ServerSide)?.compactAboveTokens != null
        if (compacts) return overflow
        val reason = if (official) NO_COMPACTION_SET else NO_COMPACTION_HERE
        return ModelError.ContextOverflow("${overflow.detail}; $reason")
    }

    private suspend fun send(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val rules = WireRules.of(request, profile.credential.kind)
        if ((rules.fallbacks || rules.prefixMismatch != null) && !official) {
            invalid(
                "wire.options",
                "the snapshot was made for another endpoint: it sends Claude API betas (server fallback, " +
                    "prefix mismatch behavior) this endpoint does not take; make a new snapshot for it",
            )
        }
        if (request.contextEdits is ContextEditPolicy.ServerSide && !official) {
            invalid(
                "contextEdits",
                "the request asks for server-side context management (context editing, compaction), a " +
                    "Claude API beta this endpoint does not take; prepare the request for this model",
            )
        }
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
                    Outcome.Failure(reply.status, reply.headers, reply.maskedText())
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
        } catch (e: ModelError) {
            // A secret in arguments the failure left unchecked outranks it: the shown output is discarded.
            stream.abandon()
            throw e
        } catch (e: IllegalArgumentException) {
            stream.abandon()
            // Provider values that break neutral-type invariants (ids, counts) are protocol violations,
            // named by the failure's class: its message is not quoted (model-layer §2).
            throw ModelError.BadResponse(INVARIANT, Unquoted.bare(e))
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

/** Why an overflow on the Claude API was not prevented: compaction is turned off. */
private const val NO_COMPACTION_SET =
    "server-side compaction is off in this model's settings and the history of Anthropic requests " +
        "is only appended to, so nothing trimmed it: turn compaction on or start a new task"

/** Why an overflow on another endpoint was not prevented: it has no compaction. */
private const val NO_COMPACTION_HERE =
    "this endpoint is not the Claude API, so it has no server-side compaction, and the history of " +
        "Anthropic requests is only appended to, so nothing trimmed it: start a new task"
