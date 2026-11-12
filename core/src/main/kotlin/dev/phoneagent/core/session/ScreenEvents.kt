package dev.phoneagent.core.session

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Device-side facts, recorded so ReplayScreen can serve them back and the trajectory can show
 * them. None is model-visible by itself: the model sees what tools put in their results.
 */

/**
 * `screen/snapshot`: an observation.
 *
 * Re-check after approval: once the user approves a confirmation card and before the call runs,
 * the runtime reads a fresh screen S', records it here under the call's id, and only then
 * resolves and evaluates the call again on it (`PolicyContext.approved`). Replay serves this record
 * back, so the re-check reaches the same verdict. Once that call has its result, whatever it is,
 * S' counts like any other screen recorded under a call for the screen later calls are judged on.
 *
 * @property callId the tool call that observed. A screen the runtime reads on its own is recorded
 *   under the call it reads for: S' of the re-check after the call's approval (above), and the
 *   screen read after the call's handoff ended. `null` only for the read after resume, which
 *   belongs to no call.
 * @property snapshot what was seen (secret values already absent).
 */
@Serializable
@SerialName("screen/snapshot")
public data class ScreenObserved(
    val callId: ToolCallId?,
    val snapshot: ScreenSnapshot,
) : SessionEvent

/**
 * `screen/judged`: whether a screen a tool read may reach the model (policy-rules §6.4). The
 * runtime judges each screen a tool reads as a `phone_observe` of [callId], through the
 * PolicyEngine only, and records this right after the `screen/snapshot`, `screen/action` or
 * `screen/launch` that holds the full screen, before the tool gets it. A withheld screen reaches
 * the tool as a copy flagged `ScreenFlag.WITHHELD`, without content.
 *
 * A recorded screen without this record was not judged: read by the runtime itself (S' of a
 * re-check, the read after a handoff or a resume), or cut off before it was judged. It counts as
 * not shown. Not ignorable: which screens the model saw decides what later results may show (a
 * diff against an earlier screen only when the model saw that one).
 *
 * @property callId the tool call that read the screen.
 * @property snapshot the screen, as recorded just before.
 * @property purpose what the screen was read for.
 * @property outcome the judgment.
 * @property rule why it was withheld: the refusing rule's reason, or [CONFIRM] or [HAND_OFF] when
 *   the PolicyEngine asked for either on the read; `null` exactly when shown.
 * @property hint what the model is told about the withheld screen; only from a refusing rule that
 *   gave one.
 */
@Serializable
@SerialName("screen/judged")
public data class ScreenJudged(
    val callId: ToolCallId,
    val snapshot: SnapshotId,
    val purpose: JudgmentPurpose,
    val outcome: JudgmentOutcome,
    val rule: String? = null,
    val hint: String? = null,
) : SessionEvent {
    init {
        require((outcome == JudgmentOutcome.WITHHELD) == (rule != null)) {
            "screen/judged has a rule exactly when withheld, got $outcome with rule $rule"
        }
        require(rule == null || rule.isNotBlank()) { "screen/judged rule must not be blank" }
        require(hint == null || outcome == JudgmentOutcome.WITHHELD) { "screen/judged hint only when withheld" }
    }

    /** The fixed [rule] values. */
    public companion object {
        /** [rule] of a screen withheld because the PolicyEngine asked for a confirmation on the read. */
        public const val CONFIRM: String = "confirm"

        /** [rule] of a screen withheld because the PolicyEngine asked for a handoff on the read. */
        public const val HAND_OFF: String = "hand_off"
    }
}

/** What a judged screen was read for ([ScreenJudged.purpose]). */
@Serializable
public enum class JudgmentPurpose {
    /**
     * The tool's own read: the screen its result may show, or one on the way there (each screen a
     * wait reads). Only these can count as seen by the model.
     */
    TOOL,

    /**
     * The read before a frame is captured: never shown, since the frame goes with the screen read
     * after it; when withheld, nothing is captured and the tool gets this screen withheld.
     */
    FRAME_PRECHECK,
}

/** The judgment of a screen ([ScreenJudged.outcome]). */
@Serializable
public enum class JudgmentOutcome {
    /** The PolicyEngine allowed reading it: the tool gets it as read. */
    SHOWN,

    /** The PolicyEngine did not allow reading it: the tool gets it without content. */
    WITHHELD,
}

/**
 * `screen/action`: an action sent to the screen port and its outcome.
 *
 * @property callId the tool call.
 * @property on the snapshot it was planned on, or `null`.
 * @property action the action as sent.
 * @property outcome what the port returned, also when it returned after its call was cancelled.
 *   When the action threw or was cancelled, `Status.Failed` naming the exception's class only
 *   ("did not finish" when cut off), `Effect.UNKNOWN`, no read-back: it may have reached the
 *   device.
 */
@Serializable
@SerialName("screen/action")
public data class ScreenActed(
    val callId: ToolCallId,
    val on: SnapshotId?,
    val action: UiAction,
    val outcome: ActionOutcome,
) : SessionEvent

/**
 * `screen/launch`: a launch sent to the screen port and its outcome.
 *
 * @property callId the tool call.
 * @property target what was opened.
 * @property outcome what the port returned, also when it returned after its call was cancelled.
 *   When the launch threw or was cancelled, `Status.Failed` naming the exception's class only
 *   ("did not finish" when cut off), `Effect.UNKNOWN`, no read-back: it may have reached the
 *   device.
 */
@Serializable
@SerialName("screen/launch")
public data class ScreenLaunched(
    val callId: ToolCallId,
    val target: LaunchTarget,
    val outcome: ActionOutcome,
) : SessionEvent

/**
 * `screen/frame`: a captured frame stored as a blob.
 *
 * @property blob the bytes.
 * @property mime one of `Part.IMAGE_MIMES`.
 * @property width pixel width, > 0.
 * @property height pixel height, > 0.
 * @property som whether Set-of-Mark boxes are drawn.
 * @property snapshotId the observation it belongs to, or `null`.
 */
@Serializable
@SerialName("screen/frame")
public data class ScreenFrame(
    val blob: BlobRef,
    val mime: String,
    val width: Int,
    val height: Int,
    val som: Boolean = false,
    val snapshotId: SnapshotId? = null,
) : SessionEvent {
    init {
        require(mime in Part.IMAGE_MIMES) { "screen/frame mime '$mime' not in ${Part.IMAGE_MIMES}" }
        require(width > 0 && height > 0) { "screen/frame size must be positive" }
    }
}
