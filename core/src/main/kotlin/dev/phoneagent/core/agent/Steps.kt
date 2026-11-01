package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.InboxEntryKind
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.StepEnded
import dev.phoneagent.core.session.StepOutcome
import dev.phoneagent.core.session.StepStarted
import dev.phoneagent.core.session.SurfaceOp
import dev.phoneagent.core.session.TurnEndReason
import dev.phoneagent.core.session.UserMessage
import dev.phoneagent.core.session.UserMessageKind
import dev.phoneagent.core.tool.ToolControl

/**
 * The steps of one turn (phase1 §3.7, §7.2). Each step: take the step's inputs (at the turn's
 * start every `NEXT_STEP` entry and one followup, later only `NEXT_STEP`) → step limit → `preStep`
 * → `step/start` → the model request → the reply's first tool call (the others are SKIPPED) →
 * `step/end`. The turn ends when a reply has no tool call and no steer is queued (after
 * `turnStopping`), when a result carries [ToolControl.EndTurn], when a reply hit the output cap
 * (its calls are not run), when `preStep` refuses, at a limit, or when the request fails.
 */
internal class Steps(
    private val loop: LoopContext,
    prompts: Prompts,
) {
    private val requests = Requests(loop) { takeInputs(turnStart = false) }
    private val calls = Calls(loop, prompts)
    private val interceptors = loop.setup.interceptors
    private val preStepChain = interceptors.preStep.chain { PreStepResult.Proceed }
    private val stoppingChain = interceptors.turnStopping.chain { TurnStopDecision.End }
    private val stepLog = LoopStepLog(loop)

    /** Runs steps until the turn ends. */
    suspend fun run(state: TurnState): TurnEnd {
        while (true) {
            state.step += 1
            step(state)?.let { return it }
        }
    }

    private suspend fun step(state: TurnState): TurnEnd? {
        loop.phase.value = Phase.Running(state.turn, state.step)
        val limit = loop.config.maxStepsPerTurn
        if (state.step > limit) return TurnEnd(TurnEndReason.LIMIT, "step limit $limit reached")
        takeInputs(turnStart = state.step == 1)
        return when (val decision = preStepChain.proceed(PreStepInput(state.turn, state.step, stepLog))) {
            is PreStepResult.Refuse -> TurnEnd(TurnEndReason.PRE_STEP_REFUSED, decision.detail)
            PreStepResult.Proceed -> request(state)
        }
    }

    private suspend fun request(state: TurnState): TurnEnd? {
        loop.log.append(StepStarted(state.turn, state.step))
        state.attempt = 0
        return when (val requested = requests.request(state)) {
            is Requested.Failed -> {
                endStep(state, StepOutcome.FAILED)
                TurnEnd(TurnEndReason.FAILED, requested.detail)
            }

            is Requested.Replied -> {
                afterReply(state, requested.record, requested.message)
            }
        }
    }

    private suspend fun afterReply(
        state: TurnState,
        record: SessionRecord,
        reply: AssistantMessage,
    ): TurnEnd? {
        val toolCalls = reply.parts.filterIsInstance<Part.ToolCall>()
        return when {
            reply.stopReason == StopReason.MAX_TOKENS -> {
                // A cut-off reply's calls may be incomplete: none of them runs.
                toolCalls.forEach { calls.skip(it, loop.texts.maxTokens, record.seq) }
                endStep(state, StepOutcome.COMPLETED)
                TurnEnd(TurnEndReason.MAX_TOKENS)
            }

            toolCalls.isEmpty() -> {
                endStep(state, StepOutcome.COMPLETED)
                finalAnswer(state, reply)
            }

            else -> {
                act(state, toolCalls, record.seq)
            }
        }
    }

    /** Runs the first of [toolCalls] only (model-layer §3.1); the others are SKIPPED. */
    private suspend fun act(
        state: TurnState,
        toolCalls: List<Part.ToolCall>,
        replySeq: Long,
    ): TurnEnd? {
        val control = calls.run(state, toolCalls.first(), replySeq)
        toolCalls.drop(1).forEach { calls.skip(it, loop.texts.skipped, replySeq) }
        endStep(state, StepOutcome.COMPLETED)
        return when (control) {
            is ToolControl.EndTurn -> TurnEnd(TurnEndReason.END_NOT_DONE, control.summary)
            null -> null
        }
    }

    private suspend fun finalAnswer(
        state: TurnState,
        reply: AssistantMessage,
    ): TurnEnd? {
        if (loop.inbox.hasSteer()) return null
        val input = TurnStoppingInput(state.turn, state.step, state.continuations, reply)
        return when (val decision = stoppingChain.proceed(input)) {
            TurnStopDecision.End -> TurnEnd(TurnEndReason.COMPLETED)
            is TurnStopDecision.Continue -> continueTurn(state, decision.context)
        }
    }

    private suspend fun continueTurn(
        state: TurnState,
        context: ContextInput,
    ): TurnEnd? {
        val limit = loop.config.maxContinuationsPerTurn
        if (state.continuations >= limit) return TurnEnd(TurnEndReason.LIMIT, "continuation limit $limit reached")
        state.continuations += 1
        loop.log.append(UserMessage(UserMessageKind.INJECTED, context.parts, context.source))
        return null
    }

    private suspend fun endStep(
        state: TurnState,
        outcome: StepOutcome,
    ) {
        loop.log.append(StepEnded(state.turn, state.step, outcome))
    }

    /** Takes the step's inputs from the inbox and records each as a user message. */
    private suspend fun takeInputs(turnStart: Boolean) {
        for (entry in loop.inbox.take(turnStart)) {
            val kind =
                when (entry.kind) {
                    InboxEntryKind.FOLLOWUP, InboxEntryKind.STEER -> UserMessageKind.USER
                    InboxEntryKind.INJECT -> UserMessageKind.INJECTED
                }
            loop.log.append(UserMessage(kind, entry.parts, entry.source))
        }
    }
}

/** The [StepLog] `preStep` interceptors get. */
private class LoopStepLog(
    private val loop: LoopContext,
) : StepLog {
    override val taskId: TaskId get() = loop.setup.taskId

    override suspend fun records(): List<SessionRecord> = loop.log.records()

    override suspend fun appendRuntimeContext(parts: List<Part>): Boolean {
        val latest =
            loop.log.records().asReversed().firstNotNullOfOrNull { record ->
                (record.event as? UserMessage)?.takeIf { it.kind == UserMessageKind.RUNTIME_CONTEXT }
            }
        if (latest?.parts == parts) return false
        loop.log.append(UserMessage(UserMessageKind.RUNTIME_CONTEXT, parts))
        return true
    }

    override suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp,
        sources: List<Long>,
    ): SessionRecord = loop.log.append(event, surfaceOp, sources)
}
