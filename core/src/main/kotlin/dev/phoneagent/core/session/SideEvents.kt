package dev.phoneagent.core.session

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `side/request`: one attempt of a model call outside the main conversation (phase1 §3.1:
 * describing a screen, grounding a target, light judgments, summaries), with its own short
 * context. Recorded whole, so the request is rebuilt exactly from this record ("model-visible ⟺
 * recorded"); the main view never reads it, replay does. Each retry is a new `side/request`.
 * Sources: the main-conversation record it serves (e.g. the `tool/call` or `step/start`).
 *
 * @property role which model role served it.
 * @property provider provider profile used.
 * @property model wire model name.
 * @property protocol adapter protocol.
 * @property request the request as sent, wire snapshot included.
 */
@Serializable
@SerialName("side/request")
public data class SideRequest(
    val role: ModelRole,
    val provider: ProviderId,
    val model: ModelId,
    val protocol: ModelProtocol,
    val request: ChatRequest,
) : SessionEvent

/**
 * `side/response`: how the `side/request` it answers ended. Its sources are exactly that
 * request's seq; each side request has at most one response (none when it was cancelled).
 *
 * @property outcome the reply or the failure.
 */
@Serializable
@SerialName("side/response")
public data class SideResponse(
    val outcome: SideOutcome,
) : SessionEvent

/** Result of a side request. */
@Serializable
public sealed interface SideOutcome {
    /** The model replied. */
    @Serializable
    @SerialName("completed")
    public data class Completed(
        /** The reply, as the adapter returned it. */
        val response: ChatResponse,
    ) : SideOutcome

    /**
     * The call failed with a `ModelError`.
     *
     * @property error failure class.
     * @property message the error's diagnostic message.
     * @property httpStatus HTTP status when there was one, else `null`.
     * @property retryAfterMs server-requested delay (>= 0), or `null`.
     */
    @Serializable
    @SerialName("failed")
    public data class Failed(
        val error: ModelErrorKind,
        val message: String,
        val httpStatus: Int? = null,
        val retryAfterMs: Long? = null,
    ) : SideOutcome {
        init {
            require(retryAfterMs == null || retryAfterMs >= 0) { "side/response: retryAfterMs must be >= 0" }
        }
    }
}
