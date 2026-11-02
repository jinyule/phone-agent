package dev.phoneagent.core.agent

import dev.phoneagent.core.model.ModelError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/** The default `requestError` interceptor (model-layer §2). */
class RetryInterceptorTest {
    /** A [Random] whose doubles are always [value]. */
    private class FixedRandom(
        private val value: Double,
    ) : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = value
    }

    private val passed = mutableListOf<RequestFailure>()
    private val end = Chain<RequestFailure, Recovery> { input -> Recovery.Fail.also { passed += input } }

    private suspend fun RetryInterceptor.decide(
        error: ModelError,
        retries: Int = 0,
    ): Recovery = intercept(RequestFailure(error, attempt = retries + 1, retries = retries, unused), end)

    /** The retry policy never reads or writes the log. */
    private val unused =
        object : StepLog {
            override val taskId get() = error("unused")

            override suspend fun records() = error("unused")

            override suspend fun appendRuntimeContext(parts: List<dev.phoneagent.core.model.Part>) = error("unused")

            override suspend fun append(
                event: dev.phoneagent.core.session.SessionEvent,
                surfaceOp: dev.phoneagent.core.session.SurfaceOp,
                sources: List<Long>,
            ) = error("unused")
        }

    private val network = ModelError.Network("reset")

    @Test
    fun `the backoff doubles from the base up to the largest, half of it random`() =
        runTest {
            val low =
                RetryInterceptor(maxRetries = 10, baseDelayMs = 500, maxDelayMs = 3_000, random = FixedRandom(0.0))
            assertEquals(
                listOf(250L, 500L, 1_000L, 1_500L, 1_500L),
                (0..4).map { (low.decide(network, it) as Recovery.Retry).delayMs },
            )
            val high =
                RetryInterceptor(maxRetries = 10, baseDelayMs = 500, maxDelayMs = 3_000, random = FixedRandom(0.5))
            assertEquals(Recovery.Retry(375), high.decide(network))
            assertEquals(Recovery.Retry(2_250), high.decide(network, 9), "the ceiling stays at maxDelayMs")
            assertTrue(passed.isEmpty())
        }

    @Test
    fun `a very long run of retries does not overflow the backoff`() =
        runTest {
            val interceptor = RetryInterceptor(maxRetries = Int.MAX_VALUE, random = FixedRandom(0.0))
            assertEquals(
                Recovery.Retry(RetryInterceptor.DEFAULT_MAX_DELAY_MS / 2),
                interceptor.decide(network, retries = 1_000),
            )
        }

    @Test
    fun `a large legal base saturates at the largest backoff instead of overflowing`() =
        runTest {
            val huge =
                RetryInterceptor(
                    maxRetries = 3,
                    baseDelayMs = Long.MAX_VALUE / 2,
                    maxDelayMs = Long.MAX_VALUE,
                    maxRetryAfterMs = Long.MAX_VALUE,
                    random = FixedRandom(0.0),
                )
            assertEquals(
                listOf(Long.MAX_VALUE / 4, Long.MAX_VALUE / 2, Long.MAX_VALUE / 2),
                (0..2).map { (huge.decide(network, it) as? Recovery.Retry)?.delayMs },
            )
            val top =
                RetryInterceptor(
                    baseDelayMs = Long.MAX_VALUE,
                    maxDelayMs = Long.MAX_VALUE,
                    maxRetryAfterMs = Long.MAX_VALUE,
                )
            assertTrue((top.decide(network, 3) as Recovery.Retry).delayMs >= 0)
        }

    @Test
    fun `the server's retry-after is waited as given`() =
        runTest {
            val interceptor = RetryInterceptor(random = FixedRandom(0.0))
            assertEquals(Recovery.Retry(2_000), interceptor.decide(ModelError.RateLimited(2_000)))
            assertEquals(Recovery.Retry(30_000), interceptor.decide(ModelError.Overloaded(30_000)))
            assertEquals(Recovery.Retry(250), interceptor.decide(ModelError.Overloaded(null)), "none stated: backoff")
            assertEquals(Recovery.Retry(250), interceptor.decide(ModelError.RateLimited(null)))
            assertEquals(Recovery.Retry(0), interceptor.decide(ModelError.RateLimited(0)), "zero is no wait")
        }

    @Test
    fun `a retry-after above the cap fails at once instead of waiting`() =
        runTest {
            val interceptor = RetryInterceptor(maxRetryAfterMs = 60_000)
            assertEquals(Recovery.Retry(60_000), interceptor.decide(ModelError.RateLimited(60_000)), "the cap itself")
            val day = ModelError.RateLimited(86_400_000)
            assertEquals(Recovery.Fail, interceptor.decide(day))
            assertEquals(Recovery.Fail, interceptor.decide(ModelError.Overloaded(60_001)))
            assertEquals(day, passed.first().error, "passed down the chain, not cut short")
            assertEquals(2, passed.size)
        }

    @Test
    fun `the cap is configurable`() =
        runTest {
            val patient = RetryInterceptor(maxRetryAfterMs = 86_400_000)
            assertEquals(Recovery.Retry(86_400_000), patient.decide(ModelError.RateLimited(86_400_000)))
        }

    @Test
    fun `it stops after the last retry and never retries what is not retryable`() =
        runTest {
            val interceptor = RetryInterceptor(maxRetries = 2)
            assertTrue(interceptor.decide(network, retries = 1) is Recovery.Retry)
            assertEquals(Recovery.Fail, interceptor.decide(network, retries = 2))
            assertEquals(Recovery.Fail, interceptor.decide(ModelError.QuotaExceeded("plan"), retries = 0))
            assertEquals(Recovery.Fail, interceptor.decide(ModelError.Server(500, "x"), retries = 2))
            assertEquals(3, passed.size)
            assertEquals(Recovery.Fail, RetryInterceptor(maxRetries = 0).decide(network), "no retries at all")
        }

    @Test
    fun `bad settings fail when the interceptor is built`() {
        assertThrows<IllegalArgumentException> { RetryInterceptor(maxRetries = -1) }
        assertThrows<IllegalArgumentException> { RetryInterceptor(baseDelayMs = 0) }
        assertThrows<IllegalArgumentException> { RetryInterceptor(baseDelayMs = 20_000, maxDelayMs = 10_000) }
        val error = assertThrows<IllegalArgumentException> { RetryInterceptor(maxRetryAfterMs = 9_999) }
        assertEquals("maxRetryAfterMs must be >= maxDelayMs, got 9999", error.message)
        RetryInterceptor(baseDelayMs = 10_000, maxDelayMs = 10_000, maxRetryAfterMs = 10_000)
    }
}
