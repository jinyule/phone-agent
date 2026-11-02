package dev.phoneagent.core.tool

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ToolDef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A validated tool call ready to dispatch (phase1 §3.2). Built at the boundary from the model's
 * [Part.ToolCall] after its arguments parsed and passed the schema; calls that do not parse never
 * become a [ToolCall] (they get an [ToolStatus.INVALID_ARGS] result instead).
 *
 * @property id the model's call id.
 * @property taskId always present: stop, approval and cleanup are scoped to the task.
 * @property sessionId the session recording it.
 * @property name tool name.
 * @property args parsed arguments without `reason`.
 * @property reason the model's `reason` argument, in the user's language; shown as "what this step does".
 */
@Serializable
public data class ToolCall(
    val id: ToolCallId,
    val taskId: TaskId,
    val sessionId: SessionId,
    val name: String,
    val args: JsonObject,
    val reason: String,
) {
    init {
        require(ToolDef.NAME_PATTERN.matches(name)) { "Invalid tool name '$name'" }
        require(reason.isNotBlank()) { "Tool call $id: reason must not be blank" }
        require(REASON !in args) { "Tool call $id: `reason` belongs in ToolCall.reason, not args" }
    }

    /** Constants. */
    public companion object {
        /** Name of the argument every tool schema must declare (phase1 §5). */
        public const val REASON: String = "reason"
    }
}

/**
 * Outcome of a tool call. Content is model-visible; [details] and [control] are not.
 *
 * @property status how the call ended.
 * @property content what the model reads: text and images only.
 * @property details structured data for `presentResult` and projections; never sent to the model.
 * @property control a signal to the loop (e.g. end the turn), or `null`; only on [ToolStatus.OK].
 */
@Serializable
public data class ToolResult(
    val status: ToolStatus,
    val content: List<Part>,
    val details: JsonObject? = null,
    val control: ToolControl? = null,
) {
    init {
        require(content.all { it is Part.Text || it is Part.Image }) {
            "ToolResult content may be text and images only"
        }
        require(control == null || status == ToolStatus.OK) { "Only an OK result may carry a control signal" }
    }

    /** The model-facing part answering [id]; every status except [ToolStatus.OK] is an error. */
    public fun toPart(id: ToolCallId): Part.ToolResult = Part.ToolResult(id, content, isError = status != ToolStatus.OK)

    /** Shortcuts. */
    public companion object {
        /** A successful text result. */
        public fun ok(text: String): ToolResult = ToolResult(ToolStatus.OK, listOf(Part.Text(text)))

        /** A failed result of [status] explained by [text]. */
        public fun failure(
            status: ToolStatus,
            text: String,
        ): ToolResult {
            require(status != ToolStatus.OK) { "failure() needs a failure status" }
            return ToolResult(status, listOf(Part.Text(text)))
        }
    }
}

/** How a tool call ended. Statuses after [SKIPPED] are written by the loop, not by tools. */
@Serializable
public enum class ToolStatus {
    /** Done. */
    OK,

    /** The tool ran and reports a failure, or threw. */
    ERROR,

    /** The policy refused it; the content explains to the model. */
    REFUSED,

    /** Arguments did not parse or did not match the schema. */
    INVALID_ARGS,

    /** The tool did not finish within its timeout. */
    TIMEOUT,

    /**
     * The task was stopped (any `StopCause`) while the call had no result yet. The model sees only
     * the text and the error flag, so the text tells the cases apart, decided by the persisted
     * `tool/started`, not by whether the call was dispatched:
     *
     * | at the stop | result | text the model sees |
     * |---|---|---|
     * | no `tool/started` | CANCELLED | it did not run |
     * | `tool/started`, no result | CANCELLED | it may have taken effect: observe, then decide on a retry |
     * | a `tool/result` already | that result | no second result |
     */
    CANCELLED,

    /**
     * Not run, never dispatched (no `tool/call`, no policy verdict): only the first call of a reply
     * is executed (phase1 §5), or the reply was cut off at the output cap and its arguments may be
     * incomplete (`ChatResponse.stopReason`).
     */
    SKIPPED,

    /**
     * Repair (D14): the call needed a result and had none, and it has no `tool/started`, so it did
     * not run; dispatched or not.
     */
    NOT_STARTED,

    /** Repair (D14): the call has a `tool/started` but no result; it may have taken effect. Observe first. */
    OUTCOME_UNKNOWN,
}
