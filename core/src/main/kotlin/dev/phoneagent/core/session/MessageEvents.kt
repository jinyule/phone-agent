package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.model.WireProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `user/message`: content on the user side of the conversation. Model-visible as a user message.
 *
 * @property kind who it comes from; compaction keeps [UserMessageKind.USER] content verbatim.
 * @property parts [Part.Text] and [Part.Image] only.
 * @property source origin id for [UserMessageKind.INJECTED] (e.g. `timer`), else `null`.
 */
@Serializable
@SerialName("user/message")
public data class UserMessage(
    val kind: UserMessageKind,
    val parts: List<Part>,
    val source: String? = null,
) : SessionEvent {
    init {
        require(parts.isNotEmpty()) { "user/message must not be empty" }
        require(parts.all { it is Part.Text || it is Part.Image }) { "user/message holds text and images only" }
    }
}

/** Origin of a [UserMessage]. */
@Serializable
public enum class UserMessageKind {
    /** The user's own words or photos. */
    USER,

    /** Runtime context the agent appends when it changes (foreground app, time, hints). */
    RUNTIME_CONTEXT,

    /** Background context from `TaskRuntime.inject`. */
    INJECTED,
}

/**
 * `request/header`: everything in a main-conversation request except the messages. Written before
 * a request whenever it differs from the previous header; a main request is exactly
 * `header.toRequest(deriveMessages(records))` with the latest header (see [SessionEvent], "Model
 * view"). The order of one request (phase1 §3.7): the `request` chain settles the model and the
 * wanted reasoning level → `ChatModel.prepare` on that final model → the same `RequestSettings`
 * fills [reasoning] and [wire] → the header is appended if it changed → `deriveMessages` → the
 * request is frozen → flush → `complete`. When the chain changes the model or the level, prepare
 * runs again; an earlier wire is never reused. Side requests (vision, light judgments) are
 * recorded as [SideRequest] instead and never use a header, so a header always serves the main
 * role. Validated like the [ChatRequest] it produces, so a bad header fails where it is read.
 *
 * @property provider provider profile used.
 * @property model wire model name.
 * @property protocol adapter protocol.
 * @property wire the non-secret wire settings frozen for this request (B1, [WireProfile]).
 * @property system system prompt blocks, verbatim.
 * @property tools tool definitions in manifest order, verbatim.
 * @property cache explicit cache breakpoints.
 * @property contextEdits context trimming mode.
 * @property maxOutputTokens output cap, or `null`.
 * @property reasoning the effective reasoning level after clamping to the model, or `null`.
 */
@Serializable
@SerialName("request/header")
public data class RequestHeader(
    val provider: ProviderId,
    val model: ModelId,
    val protocol: ModelProtocol,
    val wire: WireProfile,
    val system: List<PromptBlock>,
    val tools: List<ToolDef>,
    val cache: CacheLayout,
    val contextEdits: ContextEditPolicy,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningLevel? = null,
) : SessionEvent {
    init {
        // Builds the request without messages: every check ChatRequest makes applies to the header.
        toRequest(emptyList())
    }

    /** The request this header produces with [messages]. */
    public fun toRequest(messages: List<Message>): ChatRequest =
        ChatRequest(system, tools, messages, wire, cache, contextEdits, maxOutputTokens, reasoning)
}

/**
 * `request/retry`: a `requestError` interceptor decided to retry after a failed attempt.
 *
 * @property attempt the attempt that will run next, >= 2.
 * @property delayMs wait before it, >= 0.
 * @property error the failure being retried.
 */
@Serializable
@SerialName("request/retry")
public data class RequestRetry(
    val attempt: Int,
    val delayMs: Long,
    val error: ModelErrorKind,
) : SessionEvent {
    init {
        require(attempt >= 2) { "request/retry: the next attempt is >= 2, got $attempt" }
        require(delayMs >= 0) { "request/retry: delayMs must be >= 0, got $delayMs" }
    }
}

/**
 * `context/reasoning-dropped`: from this record on, the model view leaves out every reasoning block
 * (a [Part.Reasoning] whose kind is `ReasoningKind.isReasoning`) of `assistant/message` records
 * with seq <= [upToSeq]; their text, tool calls and compaction blocks, and all tool results, stay
 * as they were, and a reply left without parts is left out. Reasoning of later replies stays; a
 * later drop adds to this one. Written by `ReasoningDropRecovery` (`core.agent`, in the default
 * `requestError` chain) on `ModelErrorKind.PREFIX_BINDING_MISMATCH`, up to the latest complete
 * reply, before it retries without the stale blocks (model-layer §5). Not ignorable: a reader
 * that skipped it would rebuild a different request.
 *
 * @property upToSeq last record whose reasoning is dropped, >= 1 and before this record.
 */
@Serializable
@SerialName("context/reasoning-dropped")
public data class ReasoningDropped(
    val upToSeq: Long,
) : SessionEvent {
    init {
        require(upToSeq >= 1) { "context/reasoning-dropped: upToSeq must be >= 1, got $upToSeq" }
    }
}

/**
 * `assistant/message`: a main-conversation model reply, or the prefix the user already saw when
 * the stream was interrupted. Model-visible as an assistant message, except an interrupted one
 * that a later reply supersedes (see [SessionEvent], "Model view"). Side replies are
 * [SideResponse]s.
 *
 * @property attempt attempt number within the step, >= 1.
 * @property provider who answered.
 * @property model which model answered.
 * @property parts [Part.Text], [Part.ToolCall] and [Part.Reasoning] only; never empty when [interrupted]
 *   (a stream cut before anything was shown is an `assistant/attempt`). A [Part.Reasoning] is a
 *   complete block as the provider closed it: reasoning deltas of a block the stream never
 *   finished are not recorded as one.
 * @property stopReason why generation stopped; `null` exactly when [interrupted].
 * @property usage token counts, or `null` when not reported (always `null` when interrupted).
 * @property interrupted the stream was cut (steer, stop, error) after [parts] were delivered; such a
 *   message never holds tool calls (half-streamed calls are dropped). Not written for an error
 *   that discards what was streamed (`ModelError.discardsStreamed`): that attempt is only an
 *   `assistant/attempt`.
 * @property providerMetadata provider facts that are not model-visible, or `null`.
 */
@Serializable
@SerialName("assistant/message")
public data class AssistantMessage(
    val attempt: Int,
    val provider: ProviderId,
    val model: ModelId,
    val parts: List<Part>,
    val stopReason: StopReason?,
    val usage: Usage? = null,
    val interrupted: Boolean = false,
    val providerMetadata: JsonObject? = null,
) : SessionEvent {
    init {
        require(attempt >= 1) { "assistant/message: attempt must be >= 1, got $attempt" }
        require(interrupted == (stopReason == null)) {
            "assistant/message: stopReason must be null exactly when interrupted"
        }
        require(parts.all { it is Part.Text || it is Part.ToolCall || it is Part.Reasoning }) {
            "assistant/message holds text, tool calls and reasoning only"
        }
        require(!interrupted || parts.none { it is Part.ToolCall }) {
            "an interrupted assistant/message has no tool calls"
        }
        require(!interrupted || parts.isNotEmpty()) { "an interrupted assistant/message holds what was shown" }
    }
}

/**
 * `assistant/attempt`: an attempt that failed before anything reached the user, or with an error
 * that discards what was streamed (`ModelError.discardsStreamed`). Not model-visible.
 *
 * @property attempt attempt number within the step, >= 1.
 * @property error failure class.
 * @property message the error's diagnostic message.
 * @property httpStatus HTTP status when there was one, else `null`.
 * @property retryAfterMs server-requested delay (>= 0), or `null`.
 */
@Serializable
@SerialName("assistant/attempt")
public data class AssistantAttempt(
    val attempt: Int,
    val error: ModelErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
) : SessionEvent {
    init {
        require(attempt >= 1) { "assistant/attempt: attempt must be >= 1, got $attempt" }
        require(retryAfterMs == null || retryAfterMs >= 0) { "assistant/attempt: retryAfterMs must be >= 0" }
    }
}
