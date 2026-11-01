package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolResult

/**
 * The interceptors of one task, per fixed point (phase1 §3.8), each list in chain order (add to
 * the defaults with `copy`). The loop adds its own fixed parts around them: the step limit before
 * `preStep`, the request flush after `request`, the PolicyEngine guard after `toolPreExecute`, the
 * write-action flush and the tool timeout inside `toolExecute`. A throwing interceptor ends the
 * current turn only.
 *
 * @property preStep before every step: refuse it, append runtime context, compact.
 * @property request before every attempt: choose the model and reasoning; cannot touch messages.
 * @property requestError after a failed attempt: decide to retry; [RetryInterceptor] by default.
 * @property toolPreExecute before every validated call: allow, refuse, ask or hand off. The policy
 *   guard always runs after this chain and the stricter verdict wins, so nothing here can bypass it.
 * @property toolExecute around the tool's execution (timeout already applied inside).
 * @property toolPostExecute after every validated call: replace the result, attach context.
 * @property turnStopping when the turn would end with a final answer and no steer is queued.
 */
public data class Interceptors(
    val preStep: List<Interceptor<PreStepInput, PreStepResult>> = emptyList(),
    val request: List<Interceptor<RequestPlan, RequestPlan>> = emptyList(),
    val requestError: List<Interceptor<RequestFailure, Recovery>> = listOf(RetryInterceptor()),
    val toolPreExecute: List<Interceptor<PreExecuteInput, Verdict>> = emptyList(),
    val toolExecute: List<Interceptor<ExecuteInput, ToolResult>> = emptyList(),
    val toolPostExecute: List<Interceptor<PostExecuteInput, PostExecuteResult>> = emptyList(),
    val turnStopping: List<Interceptor<TurnStoppingInput, TurnStopDecision>> = emptyList(),
)

/** Builds the chain of these interceptors in order, ending in [terminal]. */
internal fun <I, O> List<Interceptor<I, O>>.chain(terminal: suspend (I) -> O): Chain<I, O> =
    foldRight(Chain(terminal)) { interceptor, next -> Chain { input -> interceptor.intercept(input, next) } }

/**
 * `preStep` input.
 *
 * @property turn the turn.
 * @property step the step about to start; its inputs are already in the log.
 * @property log what the step may read and add before the request is built.
 */
public class PreStepInput(
    public val turn: Int,
    public val step: Int,
    public val log: StepLog,
)

/** The session log as `preStep` interceptors see it. Writes go through the task's single store. */
public interface StepLog {
    /** The task. */
    public val taskId: TaskId

    /** Every record so far. */
    public suspend fun records(): List<SessionRecord>

    /**
     * Appends a `user/message` of kind `RUNTIME_CONTEXT` with [parts] unless they equal the latest
     * runtime context in the log (phase1 §7.2: appended only when it changes, earlier messages are
     * never edited). Returns whether it appended.
     */
    public suspend fun appendRuntimeContext(parts: List<Part>): Boolean

    /**
     * Appends a context event (e.g. `compaction/…` with a [SurfaceOp.Replace]). Not for loop
     * structure (`turn/…`, `step/…`, `inbox/…`, `assistant/…`, `tool/…`, `request/…`), which only
     * the loop writes.
     */
    public suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp = SurfaceOp.Append,
        sources: List<Long> = emptyList(),
    ): SessionRecord
}

/** `preStep` outcome. */
public sealed interface PreStepResult {
    /** Run the step. */
    public data object Proceed : PreStepResult

    /** Do not run it; the turn ends with `PRE_STEP_REFUSED` and [detail]. */
    public data class Refuse(
        /** Why, for the log. */
        val detail: String,
    ) : PreStepResult
}

/**
 * Everything of one attempt's request except the messages (`request`). Becomes the
 * `request/header` (written only when it changed) plus `deriveMessages(log)`.
 *
 * @property role the model's role.
 * @property model the model to call; its `info` names provider, model and protocol in the header.
 * @property system system prompt blocks.
 * @property tools tool definitions, normally the registry's manifest.
 * @property cache explicit cache breakpoints.
 * @property contextEdits who trims old context.
 * @property maxOutputTokens output cap, or `null` for the adapter's default.
 * @property reasoning reasoning level, or `null` for the provider default.
 */
public data class RequestPlan(
    val role: ModelRole,
    val model: ChatModel,
    val system: List<PromptBlock>,
    val tools: List<ToolDef>,
    val cache: CacheLayout = CacheLayout.DEFAULT,
    val contextEdits: ContextEditPolicy = ContextEditPolicy.ClientSide,
    val maxOutputTokens: Int? = null,
    val reasoning: ReasoningLevel? = null,
)

/**
 * `requestError` input: an attempt failed and the step is still open.
 *
 * @property error the failure.
 * @property attempt the attempt that failed, from 1 within the step.
 * @property retries retries already made in this step.
 */
public data class RequestFailure(
    val error: ModelError,
    val attempt: Int,
    val retries: Int,
)

/** `requestError` outcome. */
public sealed interface Recovery {
    /** Give up: the step and the turn end `FAILED`. */
    public data object Fail : Recovery

    /** Wait [delayMs] (a steer cuts the wait short), then send a new attempt; logged as `request/retry`. */
    public data class Retry(
        /** Wait before the next attempt, >= 0. */
        val delayMs: Long,
    ) : Recovery {
        init {
            require(delayMs >= 0) { "Retry delay must be >= 0, got $delayMs" }
        }
    }
}

/**
 * `toolPreExecute` input: a validated call, already recorded as `tool/call`. Passing a different
 * input to `proceed` has no effect: the recorded call is what the policy judges and what runs.
 *
 * @property call the call.
 * @property tool the tool it names.
 */
public class PreExecuteInput(
    public val call: ToolCall,
    public val tool: Tool,
)

/**
 * `toolExecute` input.
 *
 * @property call the call.
 * @property tool the tool.
 * @property context what the tool runs with.
 */
public class ExecuteInput(
    public val call: ToolCall,
    public val tool: Tool,
    public val context: ToolContext,
)

/**
 * `toolPostExecute` input: a validated call and its result (executed, refused or handed off).
 *
 * @property call the call.
 * @property tool the tool.
 * @property result the result so far.
 */
public class PostExecuteInput(
    public val call: ToolCall,
    public val tool: Tool,
    public val result: ToolResult,
)

/**
 * `toolPostExecute` outcome.
 *
 * @property result the result to record (model-visible).
 * @property context background context queued for the next step (as if by `TaskRuntime.inject`).
 */
public data class PostExecuteResult(
    val result: ToolResult,
    val context: List<ContextInput> = emptyList(),
)

/**
 * `turnStopping` input: the model gave a final answer and no steer is queued.
 *
 * @property turn the turn.
 * @property step the step that produced [reply].
 * @property continuations how often this turn was already continued.
 * @property reply the final answer.
 */
public class TurnStoppingInput(
    public val turn: Int,
    public val step: Int,
    public val continuations: Int,
    public val reply: AssistantMessage,
)

/** `turnStopping` outcome. */
public sealed interface TurnStopDecision {
    /** Let the turn end `COMPLETED`. */
    public data object End : TurnStopDecision

    /**
     * Keep the turn going: [context] is appended as an injected user message and another step
     * runs. Past `LoopConfig.maxContinuationsPerTurn` the turn ends with `LIMIT` instead.
     */
    public data class Continue(
        /** What to tell the model. */
        val context: ContextInput,
    ) : TurnStopDecision
}
