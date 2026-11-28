package dev.phoneagent.core.replay

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.session.AssistantAttempt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ModelErrorsTest {
    /** One error of each kind, with the details an adapter could give. */
    private fun sample(kind: ModelErrorKind): ModelError =
        when (kind) {
            ModelErrorKind.AUTH -> ModelError.Auth("invalid x-api-key")
            ModelErrorKind.SIGN_IN_EXPIRED -> ModelError.SignInExpired()
            ModelErrorKind.PLAN_NOT_ELIGIBLE -> ModelError.PlanNotEligible("missing scope")
            ModelErrorKind.QUOTA_EXCEEDED -> ModelError.QuotaExceeded("monthly limit")
            ModelErrorKind.RATE_LIMITED -> ModelError.RateLimited(1_500)
            ModelErrorKind.OVERLOADED -> ModelError.Overloaded(null)
            ModelErrorKind.CONTEXT_OVERFLOW -> ModelError.ContextOverflow("212000 > 200000")
            ModelErrorKind.INVALID_REQUEST -> ModelError.InvalidRequest("messages.3): x", "bad (role): order")
            ModelErrorKind.REFUSAL -> ModelError.Refusal("cyber")
            ModelErrorKind.PREFIX_BINDING_MISMATCH -> ModelError.PrefixBindingMismatch("signature")
            ModelErrorKind.NETWORK -> ModelError.Network("stream cut")
            ModelErrorKind.SERVER -> ModelError.Server(503, "unavailable")
            ModelErrorKind.BAD_RESPONSE -> ModelError.BadResponse("no content")
        }

    /** What the loop records for [error] (`Requests.failed` in the agent package). */
    private fun recorded(error: ModelError): AssistantAttempt =
        AssistantAttempt(
            2,
            error.kind,
            "${error.message}",
            (error as? ModelError.Server)?.status,
            (error as? ModelError.RateLimited)?.retryAfterMs ?: (error as? ModelError.Overloaded)?.retryAfterMs,
        )

    @ParameterizedTest
    @EnumSource(ModelErrorKind::class)
    fun `every kind is rebuilt with its recorded message`(kind: ModelErrorKind) {
        val original = sample(kind)
        val rebuilt = ModelErrors.rebuild(recorded(original))
        assertEquals(
            listOf(original.javaClass, original.message, original.retryAfterMs),
            listOf(rebuilt.javaClass, rebuilt.message, rebuilt.retryAfterMs),
        )
    }

    @Test
    fun `an invalid request without a parameter is rebuilt`() {
        val original = ModelError.InvalidRequest(null, "messages: bad")
        assertEquals(original.message, ModelErrors.rebuild(recorded(original)).message)
    }

    @Test
    fun `a refusal without a category is rebuilt`() {
        assertEquals("model refused", ModelErrors.rebuild(recorded(ModelError.Refusal(null))).message)
    }

    @Test
    fun `each rebuild is a fresh exception`() {
        val attempt = recorded(ModelError.Network("reset"))
        assertEquals(false, ModelErrors.rebuild(attempt) === ModelErrors.rebuild(attempt))
    }

    @Test
    fun `a message no error of that kind writes fails`() {
        assertThrows<IllegalArgumentException> {
            ModelErrors.rebuild(AssistantAttempt(1, ModelErrorKind.NETWORK, "connection reset"))
        }
    }

    @Test
    fun `a fixed message that was edited fails`() {
        assertThrows<IllegalArgumentException> {
            ModelErrors.rebuild(AssistantAttempt(1, ModelErrorKind.SIGN_IN_EXPIRED, "sign in expired"))
        }
    }

    @Test
    fun `a server error without its status fails`() {
        assertThrows<IllegalArgumentException> {
            ModelErrors.rebuild(AssistantAttempt(1, ModelErrorKind.SERVER, "server error 503: unavailable"))
        }
    }

    @Test
    fun `a delay the error cannot carry fails`() {
        assertThrows<IllegalArgumentException> {
            ModelErrors.rebuild(AssistantAttempt(1, ModelErrorKind.NETWORK, "network: reset", retryAfterMs = 500))
        }
    }

    @Test
    fun `a rate limit whose message names another delay fails`() {
        assertThrows<IllegalArgumentException> {
            ModelErrors.rebuild(
                AssistantAttempt(1, ModelErrorKind.RATE_LIMITED, "rate limited (retry after 9 ms)", retryAfterMs = 500),
            )
        }
    }
}
