package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.Usage
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
 * `request/header`: everything in a model request except the messages. Written before a request
 * whenever it differs from the previous header. A request is exactly
 * `header.toRequest(deriveMessages(records))`.
 *
 * @property role which model role this request serves.
 * @property provider provider profile used.
 * @property model wire model name.
 * @property protocol adapter protocol.
 * @property system system prompt blocks, verbatim.
 * @property tools tool definitions in manifest order, verbatim.
 * @property cache explicit cache breakpoints.
 * @property contextEdits context trimming mode.
 * @property maxOutputTokens output cap, or `null`.
 * @property reasoning reasoning level, or `null`.
 */
@Serializable
@SerialName("request/header")
public data class RequestHeader(
    val role: ModelRole,
    val provider: ProviderId,
    val model: ModelId,
    val protocol: ModelProtocol,
    val system: List<PromptBlock>,
    val tools: List<ToolDef>,
    val cache: CacheLayout,
    val contextEdits: ContextEditPolicy,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningLevel? = null,
) : SessionEvent {
    /** The request this header produces with [messages]. */
    public fun toRequest(messages: List<Message>): ChatRequest =
        ChatRequest(system, tools, messages, cache, contextEdits, maxOutputTokens, reasoning)
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
) : SessionEvent

/**
 * `assistant/message`: a model reply, or the prefix the user already saw when the stream was
 * interrupted. Model-visible as an assistant message.
 *
 * @property attempt attempt number within the step, from 1.
 * @property provider who answered.
 * @property model which model answered.
 * @property parts [Part.Text], [Part.ToolCall] and [Part.Reasoning] only.
 * @property stopReason why generation stopped; `null` exactly when [interrupted].
 * @property usage token counts, or `null` when not reported (always `null` when interrupted).
 * @property interrupted the stream was cut (steer, stop, error) after [parts] were delivered; such a
 *   message never holds tool calls (half-streamed calls are dropped).
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
        require(interrupted == (stopReason == null)) {
            "assistant/message: stopReason must be null exactly when interrupted"
        }
        require(parts.all { it is Part.Text || it is Part.ToolCall || it is Part.Reasoning }) {
            "assistant/message holds text, tool calls and reasoning only"
        }
        require(!interrupted || parts.none { it is Part.ToolCall }) {
            "an interrupted assistant/message has no tool calls"
        }
    }
}

/**
 * `assistant/attempt`: an attempt that failed before anything reached the user. Not model-visible.
 *
 * @property attempt attempt number within the step, from 1.
 * @property error failure class.
 * @property message the error's diagnostic message.
 * @property httpStatus HTTP status when there was one, else `null`.
 * @property retryAfterMs server-requested delay, or `null`.
 */
@Serializable
@SerialName("assistant/attempt")
public data class AssistantAttempt(
    val attempt: Int,
    val error: ModelErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
) : SessionEvent
