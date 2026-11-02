package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The task's prompts to the user (cards, questions, handoffs) and their answers
 * ([TaskRuntime.answer]). At most one prompt is pending per task: a second one waits for the first.
 * Prompt ids are `prompt-<seq>`, after the log position where they are asked, so they are unique
 * within the task and stable across replays of the same log.
 */
internal class Prompts(
    private val loop: LoopContext,
) {
    private val lock = Mutex()
    private val pending = AtomicReference<Pending<*>?>(null)

    /** The [UserInteraction] of [callId]; time waiting inside it is excluded from [wait]'s timeout. */
    fun interaction(
        callId: ToolCallId,
        wait: UserWait,
    ): UserInteraction = CallInteraction(callId, wait)

    /** Resolves the pending prompt [promptId] with [reply] when it fits; see [TaskRuntime.answer]. */
    fun answer(
        promptId: PromptId,
        reply: UserReply,
    ): Boolean {
        val prompt = pending.get()
        return prompt != null && prompt.id == promptId && prompt.offer(reply)
    }

    private inner class CallInteraction(
        private val callId: ToolCallId,
        private val wait: UserWait,
    ) : UserInteraction {
        override suspend fun ask(question: Question): AskResult =
            lock.withLock {
                val id = nextId()
                loop.log.append(QuestionAsked(id, callId, question))
                val result =
                    if (loop.unattended) {
                        AskResult.Unavailable
                    } else {
                        wait.paused { awaitReply(id, WaitReason.USER, loop.config.questionTimeoutMs, ::fitQuestion) }
                            ?: AskResult.Expired
                    }
                loop.log.append(QuestionAnswered(id, result))
                result
            }

        override suspend fun confirm(card: ConfirmCard): ConfirmResult =
            lock.withLock {
                val id = nextId()
                val timeout = loop.config.confirmTimeoutMs
                loop.log.append(ApprovalAsked(id, card, expiresAt = loop.setup.clock.epochMillis() + timeout))
                val result =
                    if (loop.unattended) {
                        ConfirmResult.UNAVAILABLE
                    } else {
                        wait.paused { awaitReply(id, WaitReason.USER, timeout, ::fitCard) } ?: ConfirmResult.EXPIRED
                    }
                loop.log.append(ApprovalDecided(id, result))
                result
            }

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ): HandoffResult =
            lock.withLock {
                val id = nextId()
                loop.log.append(HandoffStarted(id, kind, reason, callId))
                val (result, detail) =
                    if (loop.unattended) HandoffResult.UNAVAILABLE to null else handOver(id, kind)
                loop.log.append(HandoffEnded(id, result, detail))
                // The user may have changed the screen: look again before the model's next request.
                freshScreen(loop, callId)
                result
            }

        /**
         * Moves the task to the user and back when there is a [dev.phoneagent.core.ports.SurfaceManager];
         * without one (main-display mode) the user acts where the task already is. Moving failures
         * ([IOException], as the ports report device trouble) end the handoff as FAILED with a
         * detail. A stop while handed over leaves the app with the user.
         */
        private suspend fun handOver(
            id: PromptId,
            kind: HandoffKind,
        ): Pair<HandoffResult, String?> {
            val surfaces = loop.setup.surfaces ?: return awaitHandoff(id) to null
            return moving("hand over") { surfaces.handToUser(loop.setup.taskId, kind) }.fold(
                onSuccess = { ticket ->
                    val result = awaitHandoff(id)
                    moving("take back") { surfaces.takeBack(ticket) }.fold({ result to null }, ::failed)
                },
                onFailure = ::failed,
            )
        }

        private suspend fun <T> moving(
            what: String,
            move: suspend () -> T,
        ): Result<T> =
            try {
                Result.success(move())
            } catch (failure: IOException) {
                Result.failure(IOException("$what failed: ${failure.message}", failure))
            }

        private fun failed(failure: Throwable): Pair<HandoffResult, String?> = HandoffResult.FAILED to failure.message

        private suspend fun awaitHandoff(id: PromptId): HandoffResult =
            wait.paused { awaitReply(id, WaitReason.HANDOFF, loop.config.handoffTimeoutMs, ::fitHandoff) }
                ?: HandoffResult.EXPIRED
    }

    private fun nextId(): PromptId = PromptId("prompt-${loop.log.lastSeq + 1}")

    /** Waits for a fitting answer to [id]; `null` when [timeoutMs] passed. Shows [Phase.Waiting] meanwhile. */
    private suspend fun <T : Any> awaitReply(
        id: PromptId,
        reason: WaitReason,
        timeoutMs: Long,
        fit: (UserReply) -> T?,
    ): T? {
        val prompt = Pending(id, fit)
        val before = loop.phase.value
        pending.set(prompt)
        loop.phase.value = Phase.Waiting(reason, id)
        try {
            withTimeoutOrNull(timeoutMs) { prompt.await() }
        } finally {
            pending.compareAndSet(prompt, null)
            loop.phase.value = before
        }
        // A reply taken before the expiry closed the prompt counts, even when the timer fired first.
        return prompt.close()
    }

    /**
     * One prompt's answer slot, PENDING until the first fitting reply takes it (ANSWERED) or it is
     * closed (CLOSED), whichever happens first: so [offer] is `true` exactly when its reply is the
     * prompt's answer. A cancelled wait (stop, shutdown, expiry) closes the slot as it is cancelled,
     * before the waiting coroutine runs again.
     */
    private class Pending<T : Any>(
        val id: PromptId,
        private val fit: (UserReply) -> T?,
    ) {
        private val slot = AtomicReference<Slot<T>>(Slot.Pending)
        private val answered = CompletableDeferred<Unit>()

        fun offer(reply: UserReply): Boolean {
            val value = fit(reply) ?: return false
            val taken = slot.compareAndSet(Slot.Pending, Slot.Answered(value))
            if (taken) answered.complete(Unit)
            return taken
        }

        /** Suspends until a reply was taken. */
        suspend fun await() {
            suspendCancellableCoroutine { waiter ->
                waiter.invokeOnCancellation { close() }
                answered.invokeOnCompletion { waiter.resume(Unit) }
            }
        }

        /** Closes the slot unless answered; returns the answer, if any. */
        fun close(): T? {
            slot.compareAndSet(Slot.Pending, Slot.Closed)
            return (slot.get() as? Slot.Answered)?.value
        }
    }

    private sealed interface Slot<out T> {
        data object Pending : Slot<Nothing>

        data object Closed : Slot<Nothing>

        class Answered<T>(
            val value: T,
        ) : Slot<T>
    }
}

private fun fitQuestion(reply: UserReply): AskResult? =
    when (reply) {
        is UserReply.Answer -> AskResult.Answered(reply.text)
        UserReply.Decline -> AskResult.Declined
        UserReply.Approve, UserReply.Reject, UserReply.HandoffDone -> null
    }

private fun fitCard(reply: UserReply): ConfirmResult? =
    when (reply) {
        UserReply.Approve -> ConfirmResult.APPROVED
        UserReply.Reject -> ConfirmResult.REJECTED
        is UserReply.Answer, UserReply.Decline, UserReply.HandoffDone -> null
    }

private fun fitHandoff(reply: UserReply): HandoffResult? =
    when (reply) {
        UserReply.HandoffDone -> HandoffResult.DONE
        UserReply.Decline -> HandoffResult.DECLINED
        UserReply.Approve, UserReply.Reject, is UserReply.Answer -> null
    }
