package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Delta
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.model.openai.WireSupport.array
import dev.phoneagent.core.model.openai.WireSupport.int
import dev.phoneagent.core.model.openai.WireSupport.obj
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Assembles one Chat Completions reply from stream chunks (or from a whole JSON completion when
 * the server ignored `stream:true`) and reports display deltas as they arrive. Tolerates the
 * gateway quirks in `core/src/test/resources/wire/chat/`: `usage:null` and usage inside the
 * choice, keep-alive comments, missing `[DONE]`, tool calls without `index` or `id`, an `index`
 * reused for a second call, names repeated in every fragment, mid-stream error chunks.
 *
 * Complete means `[DONE]` or a `finish_reason` arrived; a body that ends before either was cut
 * ([ModelError.Network]). Where [ChatReading] allows it, a reply whose text is nothing but leaked
 * calls is recovered at the end ([LeakedToolMarkup]); the preview deltas then hold back text that
 * may still be such markup, and the end shows what they missed ([DeltaLedger.catchUp]).
 *
 * A reply whose content holds a request secret fails as [CredentialEcho] ([EchoGuard]): each delta
 * is checked before it is shown, and the reply's parts and the argument text shown for each call
 * before the end shows what the deltas missed. A reply that fails otherwise after showing argument
 * text that holds a secret fails as [CredentialEcho] too ([failed]).
 *
 * @param redactor masks credentials in provider text that goes into errors; reply metadata fields
 *   that hold one are left out ([Redactor.verbatim]).
 */
internal class ChatCompletionsStream(
    private val provider: ProviderId,
    private val model: ModelId,
    private val reading: ChatReading,
    private val newCallId: () -> String,
    private val redactor: Redactor,
    onDelta: (Delta) -> Unit,
) {
    private val guard = redactor.guard()
    private val ledger = DeltaLedger(onDelta, guard)
    private val text = StreamedText(ledger, holdBack = reading.recovers)
    private val refusal = StringBuilder()
    private val calls = ToolCallAssembler(newCallId, ledger)
    private var usage: Usage? = null
    private var finishReason: String? = null
    private var done = false
    private var chunks = 0
    private var responseId: String? = null
    private var reportedModel: String? = null

    /** Consumes one SSE event; `true` once the stream is complete (`[DONE]`). */
    fun accept(event: SseEvent): Boolean {
        val data = event.data.trim()
        done = data == DONE
        if (!done) chunk(parse(data), deltaKey = "delta")
        return done
    }

    /** Consumes a whole (non-streamed) completion object. */
    fun acceptJson(body: String) {
        chunk(parse(body), deltaKey = "message")
        done = true
    }

    /**
     * What a reply that failed with [error] fails with: [CredentialEcho.error] when the argument
     * text shown before the failure holds a secret ([DeltaLedger.showsSecretInArguments]), so the
     * UI takes it back; else [error].
     */
    fun failed(error: ModelError): ModelError = if (ledger.showsSecretInArguments()) CredentialEcho.error() else error

    /** The reply; throws [ModelError] for cut streams, refusals and malformed replies. */
    fun finish(): ChatResponse {
        checkComplete()
        val reason = finishReason
        checkOutcome(reason)
        val native = calls.parts()
        val recovery =
            if (native.isEmpty() && reading.recovers) {
                LeakedToolMarkup.recover(text.answer(), reading.recoverableTools, reading.leakedFormats)
            } else {
                null
            }
        val recovered = recovery.orEmpty().map { Part.ToolCall(ToolCallId(newCallId()), it.name, it.argsJson) }
        val answer = if (recovery != null) "" else text.answer()
        val toolCalls = native + recovered
        val parts =
            buildList {
                text.reasoning(provider, model)?.let(::add)
                if (answer.isNotEmpty()) add(Part.Text(answer))
                addAll(toolCalls)
            }
        guard.parts(parts)
        ledger.checkShownArguments()
        val diverged = ledger.catchUp(parts)
        val meta =
            metadata(
                responseId?.let(redactor::verbatim),
                reportedModel?.let(redactor::verbatim),
                reason?.let(redactor::verbatim),
                recovered.map { it.id },
                diverged,
            )
        return ChatResponse(parts, stopReason(reason, toolCalls.isNotEmpty()), usage, meta)
    }

    private fun checkComplete() {
        if (!done && finishReason == null) throw ModelError.Network("stream ended before [DONE] or a finish_reason")
        if (chunks == 0) throw ModelError.BadResponse("stream carried no completion chunks")
    }

    private fun checkOutcome(reason: String?) {
        val refused = reason?.takeIf { it in REFUSALS } ?: REFUSAL.takeIf { refusal.isNotEmpty() }
        if (refused != null) throw ModelError.Refusal(refused)
        if (reason in FAILURES) {
            throw ModelError.Server(Status.INTERNAL_ERROR, "stream ended with finish_reason=$reason")
        }
    }

    private fun parse(data: String): JsonObject =
        try {
            Json.parseToJsonElement(data) as? JsonObject ?: throw ModelError.BadResponse("chunk is not a JSON object")
        } catch (ignoredQuotingParseError: SerializationException) {
            // Neither its message nor the data is quoted: an escape no parser has read can hide a secret.
            throw ModelError.BadResponse("chunk is not JSON (${Unquoted.size(data)})")
        }

    private fun chunk(
        root: JsonObject,
        deltaKey: String,
    ) {
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            throw ProviderErrors.fromStream(ProviderError.of(root, redactor), plan = false)
        }
        chunks++
        responseId = responseId ?: root.str("id")?.takeIf { it.isNotEmpty() }
        reportedModel = reportedModel ?: root.str("model")?.takeIf { it.isNotEmpty() }
        val choice = root.array("choices")?.firstOrNull() as? JsonObject
        (usageOf(root.obj("usage")) ?: usageOf(choice?.obj("usage")))?.let { usage = it }
        choice?.str("finish_reason")?.takeIf { it.isNotEmpty() }?.let { finishReason = it }
        choice?.obj(deltaKey)?.let(::delta)
    }

    private fun delta(delta: JsonObject) {
        reading.reasoningFields.forEach { field -> delta.str(field)?.let { text.appendReasoning(field, it) } }
        textOf(delta["content"]).forEach(text::appendAnswer)
        delta.str("refusal")?.let(refusal::append)
        delta.array("tool_calls")?.forEach { (it as? JsonObject)?.let(calls::merge) }
    }

    private companion object {
        const val DONE = "[DONE]"
        val STOP = setOf("stop", "end_turn", "eos")
        val CALLS = setOf("tool_calls", "function_call")
        val LENGTH = setOf("length", "max_tokens")
        const val REFUSAL = "refusal"
        val REFUSALS = setOf("content_filter", "sensitive", REFUSAL)
        val FAILURES = setOf("error", "network_error")

        /** Answer text of a delta's `content`: a string, or the text parts of a content-part array. */
        fun textOf(content: JsonElement?): List<String> =
            when (content) {
                is JsonPrimitive -> {
                    listOfNotNull(content.takeIf { it.isString }?.content)
                }

                is JsonArray -> {
                    content.mapNotNull { part ->
                        (part as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text")
                    }
                }

                is JsonObject, null -> {
                    emptyList()
                }
            }

        /**
         * Calls make a tool step only when the reply ended normally ([STOP], [CALLS] or no reason);
         * a reason the adapter does not know is `OTHER` with or without calls, so they never run.
         * Refusals and failures were already thrown by `checkOutcome`.
         */
        fun stopReason(
            reason: String?,
            hasCalls: Boolean,
        ): StopReason =
            when {
                reason in LENGTH -> StopReason.MAX_TOKENS

                reason != null && reason !in STOP && reason !in CALLS -> StopReason.OTHER

                // Ollama reports "stop" next to tool calls; calls decide.
                hasCalls -> StopReason.TOOL_USE

                reason in CALLS -> StopReason.OTHER

                else -> StopReason.END_TURN
            }

        fun metadata(
            id: String?,
            model: String?,
            finishReason: String?,
            recovered: List<ToolCallId>,
            diverged: List<String>,
        ): JsonObject? {
            val meta =
                buildJsonObject {
                    id?.let { put("id", it) }
                    model?.let { put("model", it) }
                    finishReason?.let { put("finish_reason", it) }
                    if (recovered.isNotEmpty()) {
                        putJsonArray(RECOVERED_TOOL_CALLS) { recovered.forEach { add(it.value) } }
                    }
                    if (diverged.isNotEmpty()) putJsonArray(PREVIEW_DIVERGED) { diverged.forEach { add(it) } }
                }
            return meta.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * How [ChatCompletionsStream] reads one reply.
 *
 * @property reasoningFields delta fields that carry reasoning, in priority order; the first one seen wins.
 * @property recoverableTools tools whose calls may be recovered from the answer text (the request's tools).
 * @property leakedFormats formats recovered ([ChatQuirks.recoverLeakedCalls]).
 */
internal data class ChatReading(
    val reasoningFields: List<String>,
    val recoverableTools: Set<String>,
    val leakedFormats: Set<LeakedCallFormat>,
) {
    /** Whether recovery, and with it the preview hold-back, is on: both sets are non-empty. */
    val recovers: Boolean get() = recoverableTools.isNotEmpty() && leakedFormats.isNotEmpty()
}

/** `providerMetadata` key listing the ids of calls recovered from the answer text. */
internal const val RECOVERED_TOOL_CALLS = "recovered_tool_calls"

/**
 * Answer and reasoning text of one streamed reply. With [holdBack], answer deltas show nothing
 * while the answer is blank or starts with `<` (it may still be wholly leaked calls, which a
 * recovery turns into an empty text); once it starts with anything else it can only be kept, so
 * everything is shown from then on. So they never show text the reply takes back. Reasoning keeps
 * the first field it came in.
 */
internal class StreamedText(
    private val ledger: DeltaLedger,
    private val holdBack: Boolean,
) {
    private val answer = StringBuilder()
    private var shown = 0
    private var holding = holdBack
    private val reasoning = StringBuilder()
    private var reasoningField: String? = null

    /** Appends answer text and shows what is safe to show. */
    fun appendAnswer(fragment: String) {
        if (fragment.isEmpty()) return
        answer.append(fragment)
        if (holding) holding = answer.firstOrNull { !it.isWhitespace() }.let { it == null || it == '<' }
        if (!holding) {
            ledger.text(answer.substring(shown))
            shown = answer.length
        }
    }

    /** Appends reasoning from [field]; fragments from a second field are ignored. */
    fun appendReasoning(
        field: String,
        fragment: String,
    ) {
        if (fragment.isEmpty()) return
        if (reasoningField == null) reasoningField = field
        if (field == reasoningField) {
            reasoning.append(fragment)
            ledger.reasoning(fragment)
        }
    }

    /** The whole answer text. */
    fun answer(): String = answer.toString()

    /** The reasoning as a part (`{"<field>": text}`, the text also as summary), or `null` without any. */
    fun reasoning(
        provider: ProviderId,
        model: ModelId,
    ): Part.Reasoning? =
        reasoningField?.let { field ->
            val value = reasoning.toString()
            Part.Reasoning(
                provider,
                model,
                ReasoningKind.CHAT_REASONING_FIELD,
                chatReasoningOpaque(field, value),
                value,
            )
        }
}

/**
 * Tool calls of one streamed reply, assembled from `delta.tool_calls` fragments. Some gateways
 * split the name over fragments, so a call's name counts as complete only once its own arguments
 * start; another call's fragments say nothing about it. Calls are announced
 * ([Delta.ToolCallStarted]) in order: each one once its name is complete and every call before it
 * is announced, the rest at the end of the reply ([DeltaLedger.catchUp]). Arguments that arrive
 * before a call is announced come with the announcement. Its id is the provider's, or a fresh one
 * when the provider sends none.
 */
internal class ToolCallAssembler(
    private val newCallId: () -> String,
    private val ledger: DeltaLedger,
) {
    private class Slot(
        val index: Int?,
    ) {
        var id: String? = null
        val name = StringBuilder()
        val args = StringBuilder()

        /** The name is complete: this call's own arguments have started. */
        var named = false
        var started = false
    }

    private val slots = mutableListOf<Slot>()

    /** Folds one fragment into its call. */
    fun merge(fragment: JsonObject) {
        val id = fragment.str("id")?.takeIf { it.isNotEmpty() }
        val function = fragment.obj("function")
        val slot = slotFor(fragment.int("index"), id)
        if (slot.id == null && !slot.started) slot.id = id
        function?.str("name")?.takeIf { it.isNotEmpty() }?.let { name ->
            // Some gateways repeat the whole name in every fragment; others split it.
            if (slot.name.toString() != name) slot.name.append(name)
        }
        val args = argumentsOf(function?.get("arguments"))
        slot.args.append(args)
        if (slot.started) {
            ledger.args(ToolCallId(checkNotNull(slot.id)), args)
        } else if (args.isNotEmpty() && slot.name.isNotEmpty()) {
            slot.named = true
            startReady()
        }
    }

    /**
     * The finished calls, in order; arguments that are empty (absent or `null` in every fragment)
     * become `{}`. A call it cannot use fails by its position, never quoting its id or name.
     */
    fun parts(): List<Part.ToolCall> =
        slots.mapIndexed { position, slot ->
            if (slot.name.isEmpty()) throw ModelError.BadResponse("tool call $position has no name")
            val id = slot.id ?: newCallId()
            try {
                Part.ToolCall(ToolCallId(id), slot.name.toString(), slot.args.toString().ifEmpty { "{}" })
            } catch (e: IllegalArgumentException) {
                throw ModelError.BadResponse(
                    "tool call $position has an unusable id (${Unquoted.size(id)})",
                    Unquoted.bare(e),
                )
            }
        }

    /** Announces, in order, the calls not announced yet up to the first whose name may still grow. */
    private fun startReady() {
        slots.takeWhile { it.named }.filter { !it.started }.forEach(::start)
    }

    private fun start(slot: Slot) {
        slot.started = true
        val callId = ToolCallId(slot.id ?: newCallId().also { slot.id = it })
        ledger.started(callId, slot.name.toString())
        ledger.args(callId, slot.args.toString())
    }

    /**
     * The call a fragment continues. With an `index`: the latest call at that index, unless the
     * fragment names a different id (a gateway reusing index 0 for a second call). Without one:
     * the call with that id, else the latest call.
     */
    private fun slotFor(
        index: Int?,
        id: String?,
    ): Slot {
        val existing =
            if (index != null) {
                slots.lastOrNull { it.index == index }?.takeIf { id == null || it.id == null || it.id == id }
            } else {
                slots.lastOrNull { id == null || it.id == id }
            }
        return existing ?: Slot(index).also { slots += it }
    }

    /**
     * The argument text of one fragment: a string as it is; absent or `null` adds nothing; any other
     * JSON value as its JSON text, never replaced. A few gateways send an object; a number, boolean
     * or array is kept for the tool layer's own check to refuse.
     */
    private fun argumentsOf(raw: JsonElement?): String =
        when (raw) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (raw.isString) raw.content else raw.toString()
            is JsonObject, is JsonArray -> raw.toString()
        }
}

/**
 * Chat Completions usage, tolerant of `null`, of DeepSeek's `prompt_cache_hit_tokens` / `…miss…`
 * and Kimi's top-level `cached_tokens`. Counts are clamped so a provider's inconsistent numbers
 * never fail a reply.
 */
internal fun usageOf(usage: JsonObject?): Usage? = usage?.let(::usageFrom)

private fun usageFrom(usage: JsonObject): Usage? {
    val hit = usage.int("prompt_cache_hit_tokens")
    val miss = usage.int("prompt_cache_miss_tokens")
    val input = (usage.int("prompt_tokens") ?: hit?.let { h -> miss?.let { h + it } })?.coerceAtLeast(0)
    return input?.let {
        val output = (usage.int("completion_tokens") ?: 0).coerceAtLeast(0)
        val details = usage.obj("prompt_tokens_details")?.int("cached_tokens") ?: 0
        val cached = maxOf(details, hit ?: 0, usage.int("cached_tokens") ?: 0).coerceIn(0, input)
        val reasoning = usage.obj("completion_tokens_details")?.int("reasoning_tokens")?.coerceIn(0, output)
        Usage(input, output, cacheReadTokens = cached, reasoningTokens = reasoning)
    }
}
