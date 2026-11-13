package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.RequestSettings
import dev.phoneagent.core.model.clampTo
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionRecord
import kotlinx.coroutines.awaitCancellation

/** What the model did with one request of a recording. */
sealed interface ScriptedOutcome {
    /** The request's number, from 1. */
    val n: Int

    /** It replied [response] (tool arguments may hold templates). */
    data class Reply(
        override val n: Int,
        val response: ChatResponse,
    ) : ScriptedOutcome

    /** It streamed [prefix] (or nothing) and then failed as [attempt] records. */
    data class Failure(
        override val n: Int,
        val prefix: String?,
        val attempt: AssistantAttempt,
    ) : ScriptedOutcome

    /** It streamed [prefix] (or nothing) and never finished: a steer, a stop or the end cut it off. */
    data class Unfinished(
        override val n: Int,
        val prefix: String?,
    ) : ScriptedOutcome
}

/**
 * The model's side of a recording, read from the log (WP08 design §3): for request `n`, the outcome
 * records (`assistant/message`, `assistant/attempt`) after its `logSeq` and up to the next request's.
 */
object ModelScript {
    /**
     * The outcome of each of [requests] in [records]. Fails with [IllegalArgumentException] when an
     * outcome record lies before the first request or is not explained by its request (two
     * replies, a reply after a failure), a reply came from another model than its request went
     * to, a failure's message cannot be rebuilt ([ModelErrors]), or a template is malformed
     * ([RequestTemplates.patterns]).
     */
    fun of(
        records: List<SessionRecord>,
        requests: List<RecordedRequest>,
    ): List<ScriptedOutcome> {
        val outcomes = records.filter { it.event is AssistantMessage || it.event is AssistantAttempt }
        val early = outcomes.filter { record -> requests.none { it.logSeq < record.seq } }.map { it.seq }
        require(early.isEmpty()) { "outcome records $early come before any request" }
        return requests.mapIndexed { index, request ->
            val until = requests.getOrNull(index + 1)?.logSeq ?: Long.MAX_VALUE
            outcome(request, outcomes.filter { it.seq > request.logSeq && it.seq <= until })
        }
    }

    private fun outcome(
        request: RecordedRequest,
        window: List<SessionRecord>,
    ): ScriptedOutcome {
        val n = request.n
        val first = window.firstOrNull()?.event
        val rest = window.drop(1)
        return when {
            first == null -> {
                ScriptedOutcome.Unfinished(n, null)
            }

            first is AssistantAttempt -> {
                require(rest.isEmpty()) { "request #$n: records ${rest.map { it.seq }} after its failure" }
                ScriptedOutcome.Failure(n, null, first.also(ModelErrors::rebuild))
            }

            first is AssistantMessage && first.interrupted -> {
                val prefix = prefixOf(n, first)
                val attempt = rest.singleOrNull()?.event
                require(rest.isEmpty() || attempt is AssistantAttempt) {
                    "request #$n: records ${rest.map { it.seq }} after its interrupted reply"
                }
                if (attempt is AssistantAttempt) {
                    ScriptedOutcome.Failure(
                        n,
                        prefix,
                        attempt.also(ModelErrors::rebuild),
                    )
                } else {
                    ScriptedOutcome.Unfinished(n, prefix)
                }
            }

            else -> {
                val reply = first as AssistantMessage
                require(rest.isEmpty()) { "request #$n: records ${rest.map { it.seq }} after its reply" }
                require(reply.provider == request.model.provider && reply.model == request.model.model) {
                    "request #$n went to ${request.model.model.value}, " +
                        "the reply at seq ${window.first().seq} came from ${reply.model.value}"
                }
                val stop =
                    requireNotNull(
                        reply.stopReason,
                    ) { "request #$n: a reply that is not interrupted needs its stop reason" }
                // A bad template fails here, when the fixture is read, not at the request.
                reply.parts.filterIsInstance<Part.ToolCall>().forEach { RequestTemplates.patterns(it.argsJson) }
                ScriptedOutcome.Reply(n, ChatResponse(reply.parts, stop, reply.usage, reply.providerMetadata))
            }
        }
    }

    private fun prefixOf(
        n: Int,
        message: AssistantMessage,
    ): String {
        val text = message.parts.singleOrNull() as? Part.Text
        return requireNotNull(
            text,
        ) { "request #$n: an interrupted reply holds one text part, the delivered prefix" }.text
    }
}

/**
 * The main model of a replayed session: answers each request with the recording's outcome for it
 * ([ModelScript]) and gives the recording's settings from [prepare] (WP08 design §3). It does not
 * compare requests; [RequestTape] keeps them for the comparison with `requests.expected.jsonl`.
 *
 * Replay failures are [AssertionError]s (they pass through the loop's `catch (e: Exception)`): a
 * request beyond the script, a level that does not clamp to the recorded one, a template that
 * matches nothing.
 *
 * @param info the recording's model ([Scenario.model]).
 * @param requests the recorded requests, all to this model.
 * @param script their outcomes, one each.
 */
class ReplayChatModel(
    override val info: ModelInfo,
    private val requests: List<RecordedRequest>,
    private val script: List<ScriptedOutcome>,
) : ChatModel {
    init {
        val model = ModelRef.of(info)
        val others = requests.filter { it.model != model }.map { it.n }
        require(
            others.isEmpty(),
        ) { "requests $others went to another model than $model; one model per session is replayable" }
        require(script.map { it.n } == requests.map { it.n }) { "the script must hold one outcome per request" }
    }

    private var sent = 0
    private val resolved = mutableMapOf<ToolCallId, RequestTemplates.Resolved>()

    /** How many requests were answered. */
    val answered: Int get() = sent

    /** The templates each replayed reply resolved, by call: what [RequestTemplates.restore] puts back. */
    val templates: Map<ToolCallId, RequestTemplates.Resolved> get() = resolved

    /**
     * The settings the next request was recorded with: its level, wire and context edits.
     * [reasoning] clamped to this model's levels must be the level the recording sent; anything
     * else fails with [AssertionError].
     */
    override fun prepare(reasoning: ReasoningLevel?): RequestSettings {
        val upcoming =
            requests.getOrNull(sent)
                ?: throw AssertionError("prepare after the last recorded request (#${requests.size})")
        val level = reasoning?.clampTo(info.capabilities.reasoningLevels)
        if (level != upcoming.request.reasoning) {
            throw AssertionError(
                "request #${upcoming.n}: $reasoning clamps to $level, the recording sent ${upcoming.request.reasoning}",
            )
        }
        return RequestSettings(level, upcoming.request.wire, upcoming.request.contextEdits)
    }

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        val outcome = script.getOrNull(sent) ?: throw AssertionError("script exhausted at request #${sent + 1}")
        sent++
        return when (outcome) {
            is ScriptedOutcome.Reply -> {
                val reply = RequestTemplates.resolve(outcome.response, request, "request #${outcome.n}")
                resolved += RequestTemplates.changes(outcome.response, reply)
                reply.parts.filterIsInstance<Part.Text>().forEach { onDelta(Delta.Text(it.text)) }
                reply
            }

            is ScriptedOutcome.Failure -> {
                outcome.prefix?.let { onDelta(Delta.Text(it)) }
                throw ModelErrors.rebuild(outcome.attempt)
            }

            is ScriptedOutcome.Unfinished -> {
                outcome.prefix?.let { onDelta(Delta.Text(it)) }
                awaitCancellation()
            }
        }
    }

    /** Fails with [AssertionError] unless every recorded request was made. */
    fun assertConsumed() {
        if (sent != script.size) throw AssertionError("the recording made ${script.size} requests, the replay $sent")
    }

    companion object {
        /** The model of [fixture]. */
        fun of(fixture: SessionFixture): ReplayChatModel =
            ReplayChatModel(
                fixture.scenario.model,
                fixture.requests,
                ModelScript.of(fixture.log.records, fixture.requests),
            )
    }
}

/**
 * Keeps every request [model] receives, with the seq of the log's last record when it was sent
 * ([lastSeq]): recording and replay wrap the model the same way, so both write
 * `requests.expected.jsonl` from the same capture.
 */
class RequestTape(
    private val model: ChatModel,
    private val lastSeq: suspend () -> Long,
) : ChatModel by model {
    private val taken = mutableListOf<RecordedRequest>()

    /** The requests so far, numbered from 1. */
    val requests: List<RecordedRequest> get() = taken.toList()

    override suspend fun complete(
        request: ChatRequest,
        onDelta: (Delta) -> Unit,
    ): ChatResponse {
        taken += RecordedRequest(taken.size + 1, lastSeq(), ModelRef.of(model.info), request)
        return model.complete(request, onDelta)
    }
}
