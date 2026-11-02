package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ModelError
import kotlin.random.Random

/**
 * The default `requestError` interceptor (model-layer §2): retries errors whose kind is
 * retryable, at most [maxRetries] times per step, waiting the server's retry-after when it gave
 * one and otherwise an exponential backoff from [baseDelayMs] up to [maxDelayMs], half fixed and
 * half random so that tasks failing together do not retry together. Anything it does not retry
 * goes down the chain (whose end fails the step).
 *
 * Adapters pass the server's retry-after through as given, which may be huge (a quota reset a day
 * away). A stated wait above [maxRetryAfterMs] is **not retried**: the error goes down the chain as
 * if it were not retryable, so the turn fails at once with the provider's message instead of
 * looking hung. It is never shortened to the cap, since a request sent before the server's time
 * would only be refused again. A stated wait is never negative (`ModelError` checks it).
 *
 * @param maxRetries retries per step, >= 0.
 * @param baseDelayMs first backoff ceiling, in 1..[maxDelayMs].
 * @param maxDelayMs largest backoff.
 * @param maxRetryAfterMs longest server-stated wait still honoured, >= [maxDelayMs]; checked when
 *   the interceptor is built, so a bad setting fails at configuration load.
 * @param random backoff jitter.
 */
public class RetryInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
    private val random: Random = Random.Default,
) : Interceptor<RequestFailure, Recovery> {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, got $maxRetries" }
        require(baseDelayMs in 1..maxDelayMs) { "need 0 < baseDelayMs <= maxDelayMs, got $baseDelayMs, $maxDelayMs" }
        require(maxRetryAfterMs >= maxDelayMs) { "maxRetryAfterMs must be >= maxDelayMs, got $maxRetryAfterMs" }
    }

    override suspend fun intercept(
        input: RequestFailure,
        chain: Chain<RequestFailure, Recovery>,
    ): Recovery {
        val delayMs = input.error.retryAfterMs() ?: backoff(input.retries)
        val retry = input.error.retryable && input.retries < maxRetries && delayMs <= maxRetryAfterMs
        return if (retry) Recovery.Retry(delayMs) else chain.proceed(input)
    }

    /** Half fixed, half random below `min(maxDelayMs, baseDelayMs * 2^retries)`, saturating, never negative. */
    private fun backoff(retries: Int): Long {
        val shift = minOf(retries, MAX_SHIFT)
        // Saturate before shifting: past this, the shift would overflow (or exceed maxDelayMs anyway).
        val ceiling = if (baseDelayMs > maxDelayMs shr shift) maxDelayMs else baseDelayMs shl shift
        return ceiling / 2 + (ceiling / 2 * random.nextDouble()).toLong()
    }

    /** Defaults (model-layer §2, after dsh). */
    public companion object {
        /** Retries per step. */
        public const val DEFAULT_MAX_RETRIES: Int = 5

        /** First backoff ceiling. */
        public const val DEFAULT_BASE_DELAY_MS: Long = 500

        /** Largest backoff. */
        public const val DEFAULT_MAX_DELAY_MS: Long = 10_000

        /** Longest server-requested wait still honoured. */
        public const val DEFAULT_MAX_RETRY_AFTER_MS: Long = 60_000

        private const val MAX_SHIFT = 20
    }
}

/** The wait the server asked for, or `null` when it stated none. */
internal fun ModelError.retryAfterMs(): Long? =
    (this as? ModelError.RateLimited)?.retryAfterMs ?: (this as? ModelError.Overloaded)?.retryAfterMs
