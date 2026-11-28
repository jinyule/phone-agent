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
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `tool/call`: dispatch of a validated call began (before `toolPreExecute`). Sources: the
 * `assistant/message` that requested it. Calls that are never dispatched (invalid arguments,
 * skipped, cut off at the output cap) have none.
 *
 * The calls that need a result are every tool call of an `assistant/message` (an interrupted one
 * has none), dispatched or not, and every dispatched call. Stop handling, crash repair, the
 * output-cap skip and the model view all use this one set, and each call in it has its
 * `tool/result` before its step ends (`SessionInvariants`). For a call still without one:
 * - a stop (any `StopCause`) writes CANCELLED, with a text decided by [ToolStarted]: without
 *   one, it did not run; with one, it may have taken effect, observe first (`ToolStatus.CANCELLED`);
 * - repair (D14) writes NOT_STARTED without a `tool/started` and OUTCOME_UNKNOWN with one;
 * - a call that already has a result keeps it.
 */
@Serializable
@SerialName("tool/call")
public data class ToolCallRecorded(
    /** The call, with its task id. */
    val call: ToolCall,
) : SessionEvent

/**
 * `tool/started`: the call crossed the persisted execution boundary. The loop writes it right
 * before `Tool.execute`, in this order: append, flush, execute. Not ignorable: policy context
 * (`PolicyContext.operatedPackages`) and repair are derived from it.
 *
 * It does not say the effect happened: after a crash between this record and the action, the
 * call counts as "may have started". A call that never reaches `Tool.execute` has none: refused,
 * handed off, waiting for or denied an approval, or answered by an interceptor.
 *
 * @property callId the call.
 * @property verdictSeq seq of the `policy/verdict` the call runs on (>= 1): the call's latest
 *   verdict, an Allow; after a re-check, the re-check's.
 */
@Serializable
@SerialName("tool/started")
public data class ToolStarted(
    val callId: ToolCallId,
    val verdictSeq: Long,
) : SessionEvent {
    init {
        require(verdictSeq >= 1) { "tool/started: verdictSeq must be >= 1, got $verdictSeq" }
    }
}

/**
 * `tool/executed`: `Tool.execute` returned [status] (written before `toolPostExecute`, which may
 * still change the final `tool/result`). A tool that threw is recorded as [ToolStatus.ERROR].
 * Nothing is written when execute did not return: cancelled by a stop or by the loop's timeout,
 * the `tool/result` alone says CANCELLED or TIMEOUT. Not ignorable: `PolicyContext.outwardConfirmedPackages`
 * is derived from it.
 *
 * @property callId the call.
 * @property status what execute returned; never a status only repair writes (NOT_STARTED, OUTCOME_UNKNOWN).
 */
@Serializable
@SerialName("tool/executed")
public data class ToolExecuted(
    val callId: ToolCallId,
    val status: ToolStatus,
) : SessionEvent {
    init {
        require(status != ToolStatus.NOT_STARTED && status != ToolStatus.OUTCOME_UNKNOWN) {
            "tool/executed: $status is written by repair, not returned by a tool"
        }
    }
}

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
 * Every call that runs has one: `tool/started` is valid only on the call's latest verdict, an
 * Allow, READ calls included (no call skips the engine). Not ignorable: `tool/started` points at
 * it.
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
