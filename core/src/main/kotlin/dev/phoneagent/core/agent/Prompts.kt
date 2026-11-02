package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.HandoffTicket
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SurfaceManager
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * The task's prompts to the user (cards, questions, handoffs) and their answers
 * ([TaskRuntime.answer]). Prompt ids are `prompt-<seq>`, after the log position where they are
 * asked, so they are unique within the task and stable across replays of the same log.
 *
 * At most one prompt is pending per task: a second one waits for the first's lock (phase1 §3.2).
 * 1. The lock is released only once the log closed the prompt, judged by the log ([openPrompts]):
 *    its answer or end, else `prompt/withdrawn`, whatever ended it (an answer, a cut by the call's
 *    budget or inside the tool, a failure).
 * 2. A handoff that ended without its end (a cut, a failure) is taken back first, or the call
 *    ends with a failed take-back: a take-back that fails, takes too long or began and was cut
 *    off makes handOff throw [ScreenUnavailableException] (the move and the exception's class
 *    only) in place of the cancellation or failure. Tools must let it propagate, not go on acting.
 *    It is thrown whatever cut the call off: the call's own cut then ends as ERROR, its budget's
 *    as TIMEOUT (still with [LoopTexts.mayHaveTakenEffect] and [LoopTexts.screenUnknown]).
 * 3. The only exceptions: a stopping task leaves the prompt to the repair and a handed-over app
 *    with the user; a failed log stops the task with ERROR.
 */
internal class Prompts(
    private val loop: LoopContext,
    /** Whether the task is stopping (a stop or a shutdown): then cut-off prompts are left to the repair. */
    private val stopping: () -> Boolean,
) {
    private val lock = Mutex()
    private val pending = AtomicReference<Pending<*>?>(null)

    /**
     * The [UserInteraction] of [callId]; time waiting inside it is excluded from [wait]'s timeout.
     * The gate confirms through it; a tool only ever gets its `forTools()` view.
     */
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
                settled(id) {
                    loop.log.append(QuestionAsked(id, callId, question))
                    val result =
                        if (loop.unattended) {
                            AskResult.Unavailable
                        } else {
                            wait.paused {
                                awaitReply(id, WaitReason.USER, loop.config.questionTimeoutMs, ::fitQuestion)
                            } ?: AskResult.Expired
                        }
                    loop.log.append(QuestionAnswered(id, result))
                    result
                }
            }

        override suspend fun confirm(card: ConfirmCard): ConfirmResult =
            lock.withLock {
                val id = nextId()
                val timeout = loop.config.confirmTimeoutMs
                settled(id) {
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
            }

        override suspend fun handOff(
            kind: HandoffKind,
            reason: String,
        ): HandoffResult =
            lock.withLock {
                val id = nextId()
                val moves = loop.setup.surfaces?.let(::Moves)
                settled(id, moves) {
                    loop.log.append(HandoffStarted(id, kind, reason, callId))
                    val (result, detail) =
                        if (loop.unattended) HandoffResult.UNAVAILABLE to null else handOver(id, kind, moves)
                    loop.log.append(HandoffEnded(id, result, detail))
                    // The user may have changed the screen: look again before the model's next request.
                    freshScreen(loop, callId)
                    result
                }
            }

        /**
         * Moves the task to the user and back with [moves] when there is a [SurfaceManager];
         * without one (main-display mode) the user acts where the task already is. Moving failures
         * ([IOException], as the ports report device trouble) end the handoff as FAILED with a
         * detail naming the move and the exception's class only (its text may quote the screen).
         * A handoff the call cuts off itself is taken back at once ([Moves.giveBack]); a stop while
         * handed over leaves the app with the user.
         */
        private suspend fun handOver(
            id: PromptId,
            kind: HandoffKind,
            moves: Moves?,
        ): Pair<HandoffResult, String?> = if (moves == null) awaitHandoff(id) to null else moves.handOver(id, kind)

        /**
         * The moves of one handoff over [surfaces]: its ticket is taken back at most once (it is no
         * longer held once the take-back began; [giveBack] runs once, when the prompt is settled),
         * and a take-back that began and was cut off counts as failed.
         */
        private inner class Moves(
            private val surfaces: SurfaceManager,
        ) : GiveBack {
            private var held: HandoffTicket? = null
            private var backing = false

            suspend fun handOver(
                id: PromptId,
                kind: HandoffKind,
            ): Pair<HandoffResult, String?> =
                moving("hand over") { surfaces.handToUser(loop.setup.taskId, kind) }.fold(
                    onSuccess = { ticket ->
                        held = ticket
                        val result = awaitHandoff(id)
                        held = null
                        backing = true
                        val back = moving("take back") { surfaces.takeBack(ticket) }
                        backing = false
                        back.fold({ result to null }, ::failed)
                    },
                    onFailure = ::failed,
                )

            /**
             * Takes the task back after the call cut its handoff off, unless the app is not with
             * the user (never handed over). A take-back that already began and was cut off, or one
             * that fails or takes longer than [LoopConfig.takeBackTimeoutMs], throws
             * [ScreenUnavailableException] naming the move and the exception's class only, in place
             * of the cancellation: the call must not go on as if the app were back.
             */
            override suspend fun giveBack() {
                if (backing) throw ScreenUnavailableException("take back failed: cut off")
                val ticket = held ?: return
                val back =
                    withTimeoutOrNull(loop.config.takeBackTimeoutMs) {
                        moving("take back") { surfaces.takeBack(ticket) }
                    } ?: throw ScreenUnavailableException("take back failed: timed out")
                back.getOrThrow()
            }
        }

        private suspend fun <T> moving(
            what: String,
            move: suspend () -> T,
        ): Result<T> =
            try {
                Result.success(move())
            } catch (failure: IOException) {
                Result.failure(ScreenUnavailableException("$what failed: ${failure.javaClass.name}"))
            }

        private fun failed(failure: Throwable): Pair<HandoffResult, String?> = HandoffResult.FAILED to failure.message

        private suspend fun awaitHandoff(id: PromptId): HandoffResult =
            wait.paused { awaitReply(id, WaitReason.HANDOFF, loop.config.handoffTimeoutMs, ::fitHandoff) }
                ?: HandoffResult.EXPIRED
    }

    /** What closing a cut-off handoff does to its app, besides `prompt/withdrawn`. */
    private fun interface GiveBack {
        suspend fun giveBack()
    }

    private fun nextId(): PromptId = PromptId("prompt-${loop.log.lastSeq + 1}")

    /**
     * Runs [prompt] for prompt [id], from its first record to its last, holding the single-prompt
     * lock. Whatever ends it (its answer, a cut by the call's budget or a cancellation inside the
     * tool, a failure), the prompt is closed in the log before the lock is released: when the log
     * still has it open, `prompt/withdrawn`, then [moves]' [GiveBack.giveBack], both under
     * NonCancellable; then what ended it propagates (a cancellation, a failure), or what a failed
     * take-back throws instead. Judged by the log alone, so a record whose write began before the
     * cut is never followed by a second.
     * When the task is [stopping], the repair withdraws the prompt and a handed-over app stays with
     * the user; when the log has failed, its exception propagates and the task stops with ERROR.
     */
    private suspend fun <T> settled(
        id: PromptId,
        moves: GiveBack? = null,
        prompt: suspend () -> T,
    ): T {
        try {
            return prompt()
        } finally {
            closeIfOpen(id, moves)
        }
    }

    private suspend fun closeIfOpen(
        id: PromptId,
        moves: GiveBack?,
    ) {
        if (stopping()) return
        withContext(NonCancellable) {
            if (id in openPrompts(loop.log.records())) loop.log.append(PromptWithdrawn(id))
            moves?.giveBack()
        }
    }

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
