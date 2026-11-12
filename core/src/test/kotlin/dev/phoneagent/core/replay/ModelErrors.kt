package dev.phoneagent.core.replay

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.session.AssistantAttempt

/**
 * A recorded failure turned back into the [ModelError] an adapter threw. [ModelError] is sealed,
 * so a test cannot subclass it: the error is rebuilt from its kind by taking the fixed prefix off
 * the recorded message, and must then have exactly the recorded message and server delay.
 */
object ModelErrors {
    /**
     * A fresh error equal in kind, message and `retryAfterMs` to what [attempt] records. Fails with
     * [IllegalArgumentException] when no error of that kind has that message (the message was
     * edited by hand, or the error's wording changed: refresh the fixture).
     */
    fun rebuild(attempt: AssistantAttempt): ModelError {
        val message = attempt.message
        val error = candidate(attempt)
        require(error != null && error.message == message && error.retryAfterMs == attempt.retryAfterMs) {
            "assistant/attempt ${attempt.attempt}: no ${attempt.error} error has the message '$message'" +
                " and retryAfterMs ${attempt.retryAfterMs}"
        }
        return error
    }

    private fun candidate(attempt: AssistantAttempt): ModelError? {
        val message = attempt.message
        return when (val kind = attempt.error) {
            ModelErrorKind.AUTH,
            ModelErrorKind.PLAN_NOT_ELIGIBLE,
            ModelErrorKind.QUOTA_EXCEEDED,
            ModelErrorKind.CONTEXT_OVERFLOW,
            ModelErrorKind.PREFIX_BINDING_MISMATCH,
            ModelErrorKind.NETWORK,
            ModelErrorKind.BAD_RESPONSE,
            -> detailed(DETAILED.getValue(kind), message)

            ModelErrorKind.SIGN_IN_EXPIRED -> ModelError.SignInExpired()

            ModelErrorKind.RATE_LIMITED -> ModelError.RateLimited(attempt.retryAfterMs)

            ModelErrorKind.OVERLOADED -> ModelError.Overloaded(attempt.retryAfterMs)

            ModelErrorKind.INVALID_REQUEST -> invalidRequest(message)

            ModelErrorKind.REFUSAL -> refusal(message)

            ModelErrorKind.SERVER -> server(attempt)
        }
    }

    /**
     * The error [build] makes from the detail after its fixed prefix in [message]; the prefix is
     * read off an error [build] makes, so it is the product's current wording.
     */
    private fun detailed(
        build: (String) -> ModelError,
        message: String,
    ): ModelError? = after(message, build("").message.orEmpty())?.let(build)

    private fun after(
        message: String,
        prefix: String,
    ): String? = message.takeIf { it.startsWith(prefix) }?.substring(prefix.length)

    private fun invalidRequest(message: String): ModelError? {
        val plain = after(message, "invalid request: ")
        val withParam = INVALID_WITH_PARAM.matchEntire(message)?.destructured
        return when {
            plain != null -> ModelError.InvalidRequest(null, plain)
            withParam != null -> withParam.let { (param, detail) -> ModelError.InvalidRequest(param, detail) }
            else -> null
        }
    }

    private fun refusal(message: String): ModelError? =
        when {
            message == "model refused" -> {
                ModelError.Refusal(null)
            }

            message.startsWith("model refused (") && message.endsWith(")") -> {
                ModelError.Refusal(message.substring("model refused (".length, message.length - 1))
            }

            else -> {
                null
            }
        }

    private fun server(attempt: AssistantAttempt): ModelError? {
        val status = attempt.httpStatus ?: return null
        return after(
            attempt.message,
            "server error $status: ",
        )?.let { ModelError.Server(status, it, attempt.retryAfterMs) }
    }

    /** The errors whose message is a fixed prefix followed by the detail they were built with. */
    private val DETAILED: Map<ModelErrorKind, (String) -> ModelError> =
        mapOf(
            ModelErrorKind.AUTH to { ModelError.Auth(it) },
            ModelErrorKind.PLAN_NOT_ELIGIBLE to { ModelError.PlanNotEligible(it) },
            ModelErrorKind.QUOTA_EXCEEDED to { ModelError.QuotaExceeded(it) },
            ModelErrorKind.CONTEXT_OVERFLOW to { ModelError.ContextOverflow(it) },
            ModelErrorKind.PREFIX_BINDING_MISMATCH to { ModelError.PrefixBindingMismatch(it) },
            ModelErrorKind.NETWORK to { ModelError.Network(it) },
            ModelErrorKind.BAD_RESPONSE to { ModelError.BadResponse(it) },
        )

    private val INVALID_WITH_PARAM = Regex("""invalid request \((.*?)\): (.*)""", RegexOption.DOT_MATCHES_ALL)
}
