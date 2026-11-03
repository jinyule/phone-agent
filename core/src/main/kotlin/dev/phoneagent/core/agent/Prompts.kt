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
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.UserInteraction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PromptWithdrawn
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.QuestionAsked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *    ends with a failed take-back: a take-back that ends any way but returning (an exception, a
 *    cancellation of the port's own, its own timeout), takes too long or began and was cut off,
 *    or a hand-over that did not settle, makes handOff throw [ScreenUnavailableException]
 *    (the move and the exception's class only) in place of the cancellation or failure. A
 *    hand-over settles only with a ticket or a [ScreenUnavailableException] from the port (the
 *    one failure it promises moved nothing, `SurfaceManager.handToUser`); any other end (another
 *    exception, an Error, the port's own timeout, a cancellation, whatever the job's state) may
 *    have moved the app and left no ticket, so it is a failed take-back even when the call was not
 *    cut, with no fresh look: a port's IOException (not cut) records `handoff/end` FAILED, any
 *    other such end (another exception, the port's own timeout or cancellation, an Error) is
 *    withdrawn as a cut is; either way where the app is is unknown, and the result is ERROR with
 *    [LoopTexts.screenUnknown]. Tools must let it propagate, not go on acting. It is thrown
 *    whatever cut the call off: the call's own cut then ends as ERROR, its budget's as TIMEOUT
 *    (still with [LoopTexts.mayHaveTakenEffect] and [LoopTexts.screenUnknown]). An Error is never
 *    replaced: it propagates with the failed take-back (or what else closing threw) suppressed.
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
                    // The user may have changed the screen: look again before the model's next request,
                    // unless the app may still be with the user (then closing throws, see [Moves.giveBack]).
                    if (moves?.unsettled != true) freshScreen(loop, callId)
                    result
                }
            }

        /**
         * Moves the task to the user and back with [moves] when there is a [SurfaceManager];
         * without one (main-display mode) the user acts where the task already is. Moving failures
         * ([IOException], as the ports report device trouble) end the handoff as FAILED with a
         * detail naming the move and the exception's class only (its text may quote the screen);
         * a hand-over that failed with anything but [ScreenUnavailableException] is unsettled, and
         * handOff then throws ([Moves.giveBack]). A move that ends otherwise (another exception, a
         * cancellation, an Error) writes no `handoff/end`: the prompt is withdrawn. A handoff the
         * call cuts off itself is taken back at once ([Moves.giveBack]); a stop while handed over
         * leaves the app with the user.
         */
        private suspend fun handOver(
            id: PromptId,
            kind: HandoffKind,
            moves: Moves?,
        ): Pair<HandoffResult, String?> = if (moves == null) awaitHandoff(id) to null else moves.handOver(id, kind)

        /**
         * The moves of one handoff over [surfaces]: its ticket is taken back at most once (it is no
         * longer held once the take-back began; [giveBack] runs once, when the prompt is settled).
         * A take-back that began and was cut off, and a hand-over that began and did not settle
         * ([unsettled]), count as a failed take-back.
         */
        private inner class Moves(
            private val surfaces: SurfaceManager,
        ) : GiveBack {
            private var held: HandoffTicket? = null
            private var handing = false
            private var backing = false

            suspend fun handOver(
                id: PromptId,
                kind: HandoffKind,
            ): Pair<HandoffResult, String?> =
                handToUser(kind).fold(
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
             * Whether the app may be with the user without a ticket: a hand-over began and did not
             * settle. Judged by how the port's call ended only, never by the job's state.
             */
            val unsettled: Boolean get() = handing

            /**
             * Hands the app over. A cut that lands before the port's call moves nothing; from just
             * before it, [handing] is set until the call settles: with a ticket (handed over) or a
             * [ScreenUnavailableException] (nothing moved, the one failure the port promises that
             * of). Any other end leaves it set: the app may have moved.
             */
            private suspend fun handToUser(kind: HandoffKind): Result<HandoffTicket> {
                currentCoroutineContext().ensureActive()
                handing = true
                return moving("hand over") {
                    val ticket =
                        try {
                            surfaces.handToUser(loop.setup.taskId, kind)
                        } catch (unmoved: ScreenUnavailableException) {
                            // Settled: the port promises nothing moved (SurfaceManager.handToUser).
                            handing = false
                            throw unmoved
                        }
                    handing = false
                    ticket
                }
            }

            /**
             * Takes the task back after the call cut its handoff off, unless the app is not with
             * the user (never handed over). A hand-over that did not settle (cut off or not), a
             * take-back that already began and was cut off, or a take-back that ends any way but
             * returning ([backWithin]) or takes longer than [LoopConfig.takeBackTimeoutMs], throws
             * [ScreenUnavailableException] naming the move only, in place of the cancellation or
             * failure: the call must not go on as if the app were back.
             */
            override suspend fun giveBack() {
                val unfinished =
                    when {
                        handing -> "hand over unsettled"
                        backing -> "cut off"
                        else -> null
                    }
                unfinished?.let { throw ScreenUnavailableException("take back failed: $it") }
                val ticket = held ?: return
                backWithin(ticket) ?: throw ScreenUnavailableException("take back failed: timed out")
            }

            /**
             * Takes [ticket] back within [LoopConfig.takeBackTimeoutMs]: its display, or `null`
             * when that passed. It runs under NonCancellable ([closeIfOpen]), so a cancellation
             * coming out of the port is the port's own: that, its own timeout and any other
             * exception are a failed take-back ([ScreenUnavailableException] with the class only, as
             * [moving]: the port's message and cause may quote the screen). An Error propagates as
             * it is, the failed take-back suppressed on it.
             */
            @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any end but a return; class only.
            private suspend fun backWithin(ticket: HandoffTicket): SurfaceRef? =
                try {
                    withTimeoutOrNull(loop.config.takeBackTimeoutMs) { takeBack(ticket) }
                } catch (failure: Exception) {
                    // Not this call's cancellation (NonCancellable): the port's own, a failed take-back like any other.
                    if (failure is CancellationException) currentCoroutineContext().ensureActive()
                    throw ScreenUnavailableException("take back failed: ${failure.javaClass.name}")
                }

            /** The port's take-back; an Error from it gets the failed take-back suppressed on its own instance. */
            @Suppress("TooGenericExceptionCaught") // Only to attach the failed take-back to an Error.
            private suspend fun takeBack(ticket: HandoffTicket): SurfaceRef =
                try {
                    surfaces.takeBack(ticket)
                } catch (bug: Error) {
                    bug.addSuppressed(ScreenUnavailableException("take back failed: ${bug.javaClass.name}"))
                    throw bug
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
     * take-back throws instead; an Error always propagates, what closing threw suppressed on it.
     * Judged by the log alone, so a record whose write began before the cut is never followed by
     * a second.
     * When the task is [stopping], the repair withdraws the prompt and a handed-over app stays with
     * the user; when the log has failed, its exception propagates and the task stops with ERROR.
     */
    @Suppress("TooGenericExceptionCaught") // Only to keep an Error from being replaced by what closing throws.
    private suspend fun <T> settled(
        id: PromptId,
        moves: GiveBack? = null,
        prompt: suspend () -> T,
    ): T {
        var bug: Error? = null
        try {
            return prompt()
        } catch (error: Error) {
            bug = error
            throw error
        } finally {
            closeIfOpen(id, moves, bug)
        }
    }

    /** Closes prompt [id] (see [settled]); with [bug] pending, what closing throws is suppressed on it instead. */
    @Suppress("TooGenericExceptionCaught") // Whatever closing throws (a failed take-back, the log's failure).
    private suspend fun closeIfOpen(
        id: PromptId,
        moves: GiveBack?,
        bug: Error?,
    ) {
        if (stopping()) return
        try {
            withContext(NonCancellable) {
                if (id in openPrompts(loop.log.records())) loop.log.append(PromptWithdrawn(id))
                moves?.giveBack()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closing: Exception) {
            if (bug == null) throw closing
            bug.addSuppressed(closing)
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
