package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.WireProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * One request a model received, as `requests.expected.jsonl` keeps it.
 *
 * @property n its number in the session, from 1.
 * @property logSeq seq of the last session record when it was sent (its outcome comes after).
 * @property model the model it went to.
 * @property request the request, as `ChatModel.complete` received it.
 */
data class RecordedRequest(
    val n: Int,
    val logSeq: Long,
    val model: ModelRef,
    val request: ChatRequest,
)

/** Which model a request went to. */
@Serializable
data class ModelRef(
    val provider: ProviderId,
    val model: ModelId,
    val protocol: ModelProtocol,
) {
    companion object {
        /** The reference of [info]. */
        fun of(info: ModelInfo): ModelRef = ModelRef(info.provider, info.model, info.protocol)
    }
}

/**
 * `requests.expected.jsonl`: every request of a session, one line each, so replay can require each
 * request to be byte for byte what the recording sent (engineering-baseline §3.2). The request is
 * compared at the `ChatRequest` layer: it already holds what `prepare` gave (wire snapshot, clamped
 * level); the wire bytes are a pure function of it per `mappingVersion`, covered by the adapters'
 * own golden files.
 *
 * A line holds the model and the settings (system, tools, wire, cache, context edits, output cap,
 * level) only when they differ from the line before, and the messages as `keep` (how many leading
 * messages are the previous request's) plus `append` (the rest), so an append-only session grows
 * linearly; on the other lines they are `null`. Lines are compact JSON in a fixed field order; [encode]
 * is deterministic.
 */
object RecordedRequests {
    /**
     * Every field written, nulls included, as the session log writes payloads: a later default
     * change cannot alter a line.
     */
    private val json = Json { encodeDefaults = true }

    /** The file text of [requests] (one `\n`-terminated line each, numbered from 1). */
    fun encode(requests: List<RecordedRequest>): String {
        val lines = StringBuilder()
        var previous: RecordedRequest? = null
        requests.forEachIndexed { index, recorded ->
            require(recorded.n == index + 1) { "request #${index + 1} is numbered ${recorded.n}" }
            val before = previous
            val settings = Settings.of(recorded.request)
            val keep = before?.let { common(it.request.messages, recorded.request.messages) } ?: 0
            val line =
                Line(
                    n = recorded.n,
                    logSeq = recorded.logSeq,
                    model = recorded.model.takeIf { it != before?.model },
                    settings = settings.takeIf { before == null || it != Settings.of(before.request) },
                    keep = keep,
                    append = recorded.request.messages.drop(keep),
                )
            lines.append(json.encodeToString(serializer<Line>(), line)).append('\n')
            previous = recorded
        }
        return lines.toString()
    }

    /**
     * The requests of a file [text]. Fails with [IllegalArgumentException] naming the line when a
     * line is not JSON of this layout, the numbers are not 1, 2, …, a `logSeq` goes back, the first
     * line lacks the model or settings, or `keep` exceeds the previous request's messages.
     */
    fun decode(text: String): List<RecordedRequest> {
        require(text.isEmpty() || text.endsWith("\n")) { "requests.expected.jsonl must end with a newline" }
        val requests = mutableListOf<RecordedRequest>()
        text.lineSequence().filter { it.isNotEmpty() }.forEachIndexed { index, raw ->
            val number = index + 1
            val line = lineOf(raw, number)
            val before = requests.lastOrNull()
            require(line.n == number) { "requests.expected.jsonl line $number: n is ${line.n}" }
            require(line.logSeq >= (before?.logSeq ?: 1)) {
                "requests.expected.jsonl line $number: logSeq ${line.logSeq} goes back"
            }
            val model = requireNotNull(line.model ?: before?.model) { "requests.expected.jsonl line 1 needs the model" }
            val settings =
                requireNotNull(line.settings ?: before?.let { Settings.of(it.request) }) {
                    "requests.expected.jsonl line 1 needs the settings"
                }
            val kept = before?.request?.messages.orEmpty()
            require(line.keep in 0..kept.size) {
                "requests.expected.jsonl line $number: keep ${line.keep} of ${kept.size} previous messages"
            }
            val request = settings.toRequest(kept.take(line.keep) + line.append)
            requests += RecordedRequest(line.n, line.logSeq, model, request)
        }
        return requests
    }

    private fun lineOf(
        raw: String,
        number: Int,
    ): Line =
        try {
            json.decodeFromString(serializer<Line>(), raw)
        } catch (invalid: IllegalArgumentException) {
            // SerializationException and failed `require`s of the model types are both IllegalArgumentException.
            throw IllegalArgumentException("requests.expected.jsonl line $number: ${invalid.message}", invalid)
        }

    private fun common(
        before: List<Message>,
        after: List<Message>,
    ): Int = before.indices.firstOrNull { it >= after.size || before[it] != after[it] } ?: before.size

    @Serializable
    internal data class Line(
        val n: Int,
        val logSeq: Long,
        val model: ModelRef? = null,
        val settings: Settings? = null,
        val keep: Int,
        val append: List<Message>,
    )

    @Serializable
    internal data class Settings(
        val system: List<PromptBlock>,
        val tools: List<ToolDef>,
        val wire: WireProfile,
        val cache: CacheLayout,
        val contextEdits: ContextEditPolicy,
        val maxOutputTokens: Int? = null,
        val reasoning: ReasoningLevel? = null,
    ) {
        fun toRequest(messages: List<Message>): ChatRequest =
            ChatRequest(system, tools, messages, wire, cache, contextEdits, maxOutputTokens, reasoning)

        companion object {
            fun of(request: ChatRequest): Settings =
                Settings(
                    request.system,
                    request.tools,
                    request.wire,
                    request.cache,
                    request.contextEdits,
                    request.maxOutputTokens,
                    request.reasoning,
                )
        }
    }
}
