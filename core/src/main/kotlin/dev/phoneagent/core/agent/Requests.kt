package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.session.AssistantAttempt
import dev.phoneagent.core.session.AssistantMessage
import dev.phoneagent.core.session.RequestHeader
import dev.phoneagent.core.session.RequestRetry
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.deriveMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** How a step's model request ended. */
internal sealed interface Requested {
    /** The reply, recorded as [record]. */
    class Replied(
        val record: SessionRecord,
        val message: AssistantMessage,
    ) : Requested

    /** `requestError` gave up; [detail] is the last error. */
    class Failed(
        val detail: String?,
    ) : Requested
}

/**
 * The attempt loop of one step (phase1 §3.7, §7.2 step 3): `request` chain → `request/header`
 * when it changed → `deriveMessages` → flush → `ChatModel.complete`. A steer cancels the
 * attempt in flight (or a retry wait) and the step goes on with a new attempt that includes the
 * steer; what the user already saw is kept as an interrupted `assistant/message`. A failed
 * attempt is recorded (the interrupted prefix if any, then `assistant/attempt`) and handed to the
 * `requestError` chain, which decides on a retry (`request/retry`). A reply that breaks the reply
 * contract ([replyProblem]: e.g. a call id already used in this session) is such a failure, a
 * `ModelError.BadResponse`: none of it but the streamed prefix is recorded and nothing of it runs.
 * An attempt ended by a stop or a bug leaves its prefix in [TurnState.streaming] for the turn's
 * ending to record.
 */
internal class Requests(
    private val loop: LoopContext,
    private val takeSteers: suspend () -> Unit,
) {
    private val interceptors = loop.setup.interceptors
    private val requestChain = interceptors.request.chain { it }
    private val errorChain = interceptors.requestError.chain { Recovery.Fail }

    suspend fun request(state: TurnState): Requested {
        var retries = 0
        while (true) {
            if (loop.inbox.hasSteer()) takeSteers()
            state.attempt += 1
            val plan = requestChain.proceed(loop.setup.plan)
            val request = assemble(plan)
            loop.log.flush()
            when (val attempt = attempt(state, plan, request)) {
                is Attempted.Replied -> {
                    return Requested.Replied(attempt.record, attempt.message)
                }

                Attempted.Steered -> {
                    continue // the next attempt includes the steer
                }

                is Attempted.Failed -> {
                    val recovery = errorChain.proceed(RequestFailure(attempt.error, state.attempt, retries))
                    when (recovery) {
                        Recovery.Fail -> {
                            return Requested.Failed(attempt.error.message)
                        }

                        is Recovery.Retry -> {
                            retries += 1
                            loop.log.append(RequestRetry(state.attempt + 1, recovery.delayMs, attempt.error.kind))
                            steerable { delay(recovery.delayMs) }
                        }
                    }
                }
            }
        }
    }

    /** The frozen request of [plan] over the log; writes `request/header` only when it changed. */
    private suspend fun assemble(plan: RequestPlan): ChatRequest {
        val info = plan.model.info
        val header =
            RequestHeader(
                info.provider,
                info.model,
                info.protocol,
                plan.wire,
                plan.system,
                plan.tools,
                plan.cache,
                plan.contextEdits,
                plan.maxOutputTokens,
                plan.reasoning,
            )
        val records = loop.log.records()
        if (records.asReversed().firstNotNullOfOrNull { it.event as? RequestHeader } != header) loop.log.append(header)
        return header.toRequest(deriveMessages(records))
    }

    private suspend fun attempt(
        state: TurnState,
        plan: RequestPlan,
        request: ChatRequest,
    ): Attempted {
        val number = state.attempt
        val streaming = Streaming(number, plan.model.info)
        state.streaming = streaming
        val outcome =
            steerable {
                plan.model.complete(request) { delta ->
                    streaming.add(delta)
                    loop.emit(LoopEvent.Streamed(state.turn, state.step, number, delta))
                }
            }
        // From here on the attempt records its own outcome.
        state.streaming = null
        return when (outcome) {
            is Outcome.Done -> {
                reply(number, plan, streaming, outcome.value)
            }

            Outcome.Steered -> {
                streaming.interrupted()?.let { loop.log.append(it) }
                Attempted.Steered
            }

            is Outcome.Failed -> {
                failed(number, streaming, outcome.error)
            }
        }
    }

    /** Records the failed attempt: what the user saw as an interrupted prefix, then `assistant/attempt`. */
    private suspend fun failed(
        number: Int,
        streaming: Streaming,
        error: ModelError,
    ): Attempted.Failed {
        streaming.interrupted()?.let { loop.log.append(it) }
        val status = (error as? ModelError.Server)?.status
        loop.log.append(AssistantAttempt(number, error.kind, "${error.message}", status, error.retryAfterMs()))
        return Attempted.Failed(error)
    }

    /** Records [response] as the step's reply, unless it breaks the reply contract ([replyProblem]). */
    private suspend fun reply(
        number: Int,
        plan: RequestPlan,
        streaming: Streaming,
        response: ChatResponse,
    ): Attempted {
        replyProblem(response, loop.log.records())?.let { return failed(number, streaming, ModelError.BadResponse(it)) }
        val info = plan.model.info
        val message =
            AssistantMessage(
                attempt = number,
                provider = info.provider,
                model = info.model,
                parts = response.parts,
                stopReason = response.stopReason,
                usage = response.usage,
                providerMetadata = response.providerMetadata,
            )
        return Attempted.Replied(loop.log.append(message), message)
    }

    /**
     * Runs [block] as the step's in-flight work, so that a steer cancels it: [Outcome.Steered]
     * when a steer cancelled it or was already queued (then it does not start). A [ModelError] is
     * [Outcome.Failed]; so is a cancellation that is neither a steer nor a stop of the task (a
     * broken model contract, reported as a retryable [ModelError.Network]). A stop propagates.
     */
    private suspend fun <T> steerable(block: suspend () -> T): Outcome<T> =
        supervisorScope {
            val work = async(start = CoroutineStart.LAZY) { block() }
            if (!loop.inbox.register(work)) {
                work.cancel()
                return@supervisorScope Outcome.Steered
            }
            try {
                Outcome.Done(work.await())
            } catch (expected: SteerCancellation) {
                // The steer is in the inbox; the caller starts a new attempt that includes it.
                Outcome.Steered
            } catch (error: ModelError) {
                Outcome.Failed(error)
            } catch (cancelled: CancellationException) {
                ensureActive()
                Outcome.Failed(ModelError.Network("model call cancelled unexpectedly", cancelled))
            } finally {
                loop.inbox.unregister(work)
            }
        }

    private sealed interface Outcome<out T> {
        class Done<T>(
            val value: T,
        ) : Outcome<T>

        data object Steered : Outcome<Nothing>

        class Failed(
            val error: ModelError,
        ) : Outcome<Nothing>
    }

    private sealed interface Attempted {
        class Replied(
            val record: SessionRecord,
            val message: AssistantMessage,
        ) : Attempted

        data object Steered : Attempted

        class Failed(
            val error: ModelError,
        ) : Attempted
    }
}

/** The cancellation a steer sends to the in-flight request (phase1 §7.2). */
internal class SteerCancellation : CancellationException("a steer arrived")
