package dev.phoneagent.core.model

import dev.phoneagent.core.ids.ToolCallId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A complete model reply. Refusals and failures are not responses: they are thrown as [ModelError].
 *
 * @property parts reply content: [Part.Text], [Part.ToolCall] and [Part.Reasoning] only.
 * @property stopReason why generation stopped. Adapter obligation: a reply that ended normally
 *   with complete tool calls is [StopReason.TOOL_USE]; a reply cut off at the output cap is
 *   [StopReason.MAX_TOKENS]; one that ended for a reason the adapter does not map is
 *   [StopReason.OTHER]. What the loop does with each outcome (phase1 §3.7):
 *   - TOOL_USE or END_TURN with tool calls: a normal end, and the calls run. A gateway may report
 *     END_TURN beside complete calls, so the loop never refuses them for that.
 *   - MAX_TOKENS with tool calls: the reply is recorded as it came, calls included (the history
 *     stays append-only). Before the next request every call gets a `tool/result` with
 *     `ToolStatus.SKIPPED` (an error) and a fixed text telling the model that the call did not
 *     run, that the arguments it sees (possibly `{}`) are incomplete and not what it meant to
 *     send, and to issue the call again. No policy verdict, no `tool/started`; the turn ends
 *     `TurnEndReason.MAX_TOKENS`.
 *   - OTHER with tool calls: the loop treats it as a [ModelError.BadResponse] and retries. Only the streamed prefix (an
 *     interrupted `assistant/message`, which holds no calls) and the attempt are recorded; no
 *     call runs.
 *   - OTHER with text only: a final answer, like [StopReason.END_TURN].
 * @property usage token counts, or `null` when the provider reported none.
 * @property providerMetadata provider-reported facts that are not model-visible (e.g. response id,
 *   Anthropic `input_transformations`); recorded in the log, never sent back.
 */
@Serializable
public data class ChatResponse(
    val parts: List<Part>,
    val stopReason: StopReason,
    val usage: Usage? = null,
    val providerMetadata: JsonObject? = null,
) {
    init {
        require(parts.all { it is Part.Text || it is Part.ToolCall || it is Part.Reasoning }) {
            "A response may contain only text, tool calls and reasoning"
        }
    }

    /** The tool calls, in model order. */
    public val toolCalls: List<Part.ToolCall> get() = parts.filterIsInstance<Part.ToolCall>()
}

/** Why the model stopped. */
@Serializable
public enum class StopReason {
    /** Finished its turn. */
    END_TURN,

    /** Stopped to have tools run; the reply's tool calls are complete. */
    TOOL_USE,

    /** Hit the output cap; tool calls of such a reply are answered SKIPPED, never run ([ChatResponse.stopReason]). */
    MAX_TOKENS,

    /**
     * Any other provider reason; the adapter keeps the raw value in `providerMetadata`. With tool
     * calls the reply is a bad response; with text only, a final answer ([ChatResponse.stopReason]).
     */
    OTHER,
}

/**
 * Token counts of one request.
 *
 * @property inputTokens all input tokens, including cached ones (OpenAI `prompt_tokens` semantics;
 *   Anthropic adapters add `cache_read` and `cache_creation` to `input_tokens`).
 * @property outputTokens all output tokens, including reasoning.
 * @property cacheReadTokens input tokens served from cache.
 * @property cacheWriteTokens input tokens written to cache.
 * @property reasoningTokens part of [outputTokens] spent on reasoning, or `null` when not reported.
 */
@Serializable
public data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val reasoningTokens: Int? = null,
) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0 && cacheReadTokens >= 0 && cacheWriteTokens >= 0) {
            "Token counts must be >= 0"
        }
        require(cacheReadTokens + cacheWriteTokens <= inputTokens) { "Cached tokens exceed input tokens" }
        require(reasoningTokens == null || reasoningTokens in 0..outputTokens) { "Reasoning tokens exceed output" }
    }
}

/**
 * Streaming fragment passed to `ChatModel.complete`'s `onDelta`. For display only: the final
 * [ChatResponse] is authoritative and is what gets recorded. The [Text] fragments, in order, are
 * always a prefix of the final reply's text. [Reasoning], [ToolCallStarted] and [ToolCallArgs] are
 * previews that promise nothing: when the server degrades the reply, they may be missing from the
 * final [ChatResponse], and the UI re-renders from that.
 */
public sealed interface Delta {
    /** Visible reply text, appended to what came before. */
    public data class Text(
        /** The fragment. */
        val text: String,
    ) : Delta

    /** Readable reasoning text (summaries), appended. */
    public data class Reasoning(
        /** The fragment. */
        val text: String,
    ) : Delta

    /** A tool call started. */
    public data class ToolCallStarted(
        /** Its id. */
        val id: ToolCallId,
        /** Tool name. */
        val name: String,
    ) : Delta

    /** More raw argument text for the call [id]. */
    public data class ToolCallArgs(
        /** The call. */
        val id: ToolCallId,
        /** Raw JSON fragment. */
        val fragment: String,
    ) : Delta
}
