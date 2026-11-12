package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.ReasoningDropped
import dev.phoneagent.core.session.SessionRecord

/**
 * The `requestError` recovery from a stale thinking signature (model-layer §5, "恢复兜底"), in
 * the default chain after [RetryInterceptor]. On a [ModelError.PrefixBindingMismatch] it appends
 * `context/reasoning-dropped` up to the latest complete reply and retries at once, so the next
 * attempt is built without any reasoning block of the replies so far (compaction blocks, text,
 * calls and results stay: [ReasoningDropped]).
 *
 * It retries only when that drop removes something: some complete reply after the latest drop
 * still holds a reasoning block. Otherwise (no reply yet, or a mismatch again right after a drop)
 * the error goes down the chain, whose end fails the step: a retry would send the same request.
 * So each mismatch is retried at most once, and the decision comes from the log alone.
 * Interrupted replies never count: the model view leaves out their reasoning already.
 * Other errors go down the chain untouched.
 */
public class ReasoningDropRecovery : Interceptor<RequestFailure, Recovery> {
    override suspend fun intercept(
        input: RequestFailure,
        chain: Chain<RequestFailure, Recovery>,
    ): Recovery {
        val upTo = if (input.error is ModelError.PrefixBindingMismatch) staleUpTo(input.log.records()) else null
        if (upTo == null) return chain.proceed(input)
        input.log.append(ReasoningDropped(upTo))
        return Recovery.Retry(0)
    }

    /** The latest complete reply's seq when a drop up to it removes a reasoning block, else `null`. */
    private fun staleUpTo(records: List<SessionRecord>): Long? {
        val droppedUpTo = records.mapNotNull { (it.event as? ReasoningDropped)?.upToSeq }.maxOrNull() ?: 0L
        val replies = records.filter { (it.event as? AssistantMessage)?.interrupted == false }
        val stale = replies.any { it.seq > droppedUpTo && it.holdsReasoning() }
        return if (stale) replies.last().seq else null
    }

    private fun SessionRecord.holdsReasoning(): Boolean =
        (event as AssistantMessage).parts.any { it is Part.Reasoning && it.kind.isReasoning }
}
