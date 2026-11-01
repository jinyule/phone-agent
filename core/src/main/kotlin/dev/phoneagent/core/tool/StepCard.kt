package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.BlobRef
import kotlinx.serialization.Serializable

/**
 * Trajectory card for one tool call, produced by `Tool.presentCall` / `presentResult`. Data only:
 * the app renders and localizes it ([StepDetail.key] maps to a string resource), so core holds
 * no UI text beyond what the model wrote.
 *
 * @property tool tool name.
 * @property summary the call's `reason` (user language, from the model).
 * @property details labelled values, e.g. target and typed text (secrets already masked).
 * @property image a frame to show, or `null`.
 * @property state progress of the call.
 */
@Serializable
public data class StepCard(
    val tool: String,
    val summary: String,
    val details: List<StepDetail> = emptyList(),
    val image: BlobRef? = null,
    val state: StepState = StepState.RUNNING,
)

/** One labelled value on a [StepCard]. */
@Serializable
public data class StepDetail(
    /** Stable label key, e.g. `target`, `text`, `app`. */
    val key: String,
    /** Display value. */
    val value: String,
)

/** Progress of a call as shown on its card. */
@Serializable
public enum class StepState {
    /** Waiting for policy or the user, or executing. */
    RUNNING,

    /** Finished OK. */
    SUCCEEDED,

    /** Finished with a failure status. */
    FAILED,

    /** Refused by the policy or the user. */
    REFUSED,

    /** Outcome unknown (repair). */
    UNKNOWN,
}
