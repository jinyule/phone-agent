package dev.phoneagent.core.session

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
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

/** `approval/decided`: how the card at [promptId] ended. Only [ConfirmResult.APPROVED] lets the call run. */
@Serializable
@SerialName("approval/decided")
public data class ApprovalDecided(
    /** The prompt. */
    val promptId: PromptId,
    /** The result returned to the waiting call. */
    val result: ConfirmResult,
) : SessionEvent

/**
 * `question/asked`: `ask_user` put a question to the user.
 *
 * @property promptId the prompt to answer.
 * @property callId the `ask_user` call.
 * @property question what was asked.
 */
@Serializable
@SerialName("question/asked")
public data class QuestionAsked(
    val promptId: PromptId,
    val callId: ToolCallId,
    val question: Question,
) : SessionEvent

/** `question/answered`: how the question at [promptId] ended. */
@Serializable
@SerialName("question/answered")
public data class QuestionAnswered(
    /** The prompt. */
    val promptId: PromptId,
    /** The result returned to the waiting call. */
    val result: AskResult,
) : SessionEvent

/**
 * `handoff/start`: the task was handed to the user (phase1 §7.3).
 *
 * @property promptId answered by `UserReply.HandoffDone` / `Decline`.
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

/**
 * `handoff/end`: the task is back on the agent display (or never left it); the next step must
 * observe first.
 *
 * @property promptId the prompt.
 * @property result the result returned to the waiting call.
 * @property detail why it failed, for diagnostics; `null` unless [result] is [HandoffResult.FAILED].
 */
@Serializable
@SerialName("handoff/end")
public data class HandoffEnded(
    val promptId: PromptId,
    val result: HandoffResult,
    val detail: String? = null,
) : SessionEvent {
    init {
        require(detail == null || result == HandoffResult.FAILED) { "handoff/end: detail only for FAILED" }
    }
}

/**
 * `prompt/withdrawn`: the prompt at [promptId] (card, question or handoff) was withdrawn because
 * the task stopped while it waited. The waiting call was cancelled and got no result.
 */
@Serializable
@SerialName("prompt/withdrawn")
public data class PromptWithdrawn(
    /** The prompt. */
    val promptId: PromptId,
) : SessionEvent
