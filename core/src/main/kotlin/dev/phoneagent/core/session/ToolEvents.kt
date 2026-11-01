package dev.phoneagent.core.session

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.ConfirmCard
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `tool/call`: dispatch of a validated call began (before `toolPreExecute`). Repair treats a
 * call with this record but no `tool/result` as OUTCOME_UNKNOWN, and a call without it as
 * NOT_STARTED (D14). Sources: the `assistant/message` that requested it.
 */
@Serializable
@SerialName("tool/call")
public data class ToolCallRecorded(
    /** The call, with its task id. */
    val call: ToolCall,
) : SessionEvent

/**
 * `tool/result`: the final result of a call, including results the loop writes itself (invalid
 * arguments, skipped, repair). Model-visible as the tool result answering [callId].
 * Sources: the `tool/call`, or the `assistant/message` when there is no `tool/call`.
 *
 * @property callId the call answered.
 * @property name tool name as the model called it.
 * @property result status and content.
 */
@Serializable
@SerialName("tool/result")
public data class ToolResultRecorded(
    val callId: ToolCallId,
    val name: String,
    val result: ToolResult,
) : SessionEvent

/**
 * `policy/verdict`: what PolicyEngine decided for a call, with its inputs, so replay can compare.
 *
 * @property callId the call.
 * @property level security level in force.
 * @property intent the resolved intent.
 * @property verdict the decision.
 */
@Serializable
@SerialName("policy/verdict")
public data class PolicyVerdictRecorded(
    val callId: ToolCallId,
    val level: SecurityLevel,
    val intent: ActionIntent,
    val verdict: Verdict,
) : SessionEvent

/**
 * `approval/asked`: a confirmation card was shown.
 *
 * @property promptId the prompt to answer.
 * @property card what is asked.
 * @property expiresAt epoch milliseconds after which it counts as rejected (15 minutes by default).
 */
@Serializable
@SerialName("approval/asked")
public data class ApprovalAsked(
    val promptId: PromptId,
    val card: ConfirmCard,
    val expiresAt: Long,
) : SessionEvent

/** `approval/decided`. */
@Serializable
@SerialName("approval/decided")
public data class ApprovalDecided(
    /** The prompt. */
    val promptId: PromptId,
    /** The outcome. */
    val decision: ApprovalDecision,
) : SessionEvent

/** Outcome of a confirmation. Only [APPROVED_ONCE] lets the call run. */
@Serializable
public enum class ApprovalDecision {
    /** The user approved this one call. */
    APPROVED_ONCE,

    /** The user rejected it. */
    REJECTED,

    /** Nobody answered in time. */
    EXPIRED,

    /** The task stopped while waiting. */
    CANCELLED,

    /** No way to ask (unattended task, no UI). */
    UNAVAILABLE,
}

/**
 * `question/asked`: `ask_user` put a question to the user.
 *
 * @property promptId the prompt to answer.
 * @property callId the `ask_user` call.
 * @property question the question, in the user's language.
 * @property options suggested answers; empty for free text.
 */
@Serializable
@SerialName("question/asked")
public data class QuestionAsked(
    val promptId: PromptId,
    val callId: ToolCallId,
    val question: String,
    val options: List<String> = emptyList(),
) : SessionEvent

/**
 * `question/answered`.
 *
 * @property promptId the prompt.
 * @property outcome how it ended.
 * @property answer the user's answer; non-null exactly when [outcome] is [QuestionOutcome.ANSWERED].
 */
@Serializable
@SerialName("question/answered")
public data class QuestionAnswered(
    val promptId: PromptId,
    val outcome: QuestionOutcome,
    val answer: String? = null,
) : SessionEvent {
    init {
        require((outcome == QuestionOutcome.ANSWERED) == (answer != null)) {
            "question/answered: answer must be present exactly when ANSWERED"
        }
    }
}

/** How a question ended. */
@Serializable
public enum class QuestionOutcome {
    /** The user answered. */
    ANSWERED,

    /** Nobody answered in time. */
    EXPIRED,

    /** The task stopped while waiting. */
    CANCELLED,
}

/**
 * `handoff/start`: the task was handed to the user (phase1 §7.3).
 *
 * @property promptId answered by `UserReply.HandoffDone` / `HandoffCancelled`.
 * @property kind why.
 * @property reason explanation shown to the user (model or policy text).
 * @property callId the call that caused it (`hand_to_user`, or a call the policy handed off), or `null`.
 */
@Serializable
@SerialName("handoff/start")
public data class HandoffStarted(
    val promptId: PromptId,
    val kind: HandoffKind,
    val reason: String,
    val callId: ToolCallId? = null,
) : SessionEvent

/** `handoff/end`: the task is back on the agent display; the next step must observe first. */
@Serializable
@SerialName("handoff/end")
public data class HandoffEnded(
    /** The prompt. */
    val promptId: PromptId,
    /** How it ended. */
    val outcome: HandoffOutcome,
) : SessionEvent

/** How a handoff ended. */
@Serializable
public enum class HandoffOutcome {
    /** The user said they finished. */
    DONE,

    /** The user gave up. */
    CANCELLED,

    /** Nobody acted in time. */
    EXPIRED,

    /** Moving the app between displays failed. */
    FAILED,
}
