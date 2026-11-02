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
 * the runtime reads a fresh screen, records it here with `callId == null`, and only then resolves
 * and evaluates the call again on it (`PolicyContext.approved`). Replay serves this record back,
 * so the re-check reaches the same verdict. The model has not seen this screen, so outside the
 * re-check it never becomes `PolicyContext.screen`.
 *
 * @property callId the tool call that observed, or `null` when the runtime observed on its own
 *   (after resume or handoff, or the re-check after approval).
 * @property snapshot what was seen (secret values already absent).
 */
@Serializable
@SerialName("screen/snapshot")
public data class ScreenObserved(
    val callId: ToolCallId?,
    val snapshot: ScreenSnapshot,
) : SessionEvent

/**
 * `screen/action`: an action sent to the screen port and its outcome.
 *
 * @property callId the tool call.
 * @property on the snapshot it was planned on, or `null`.
 * @property action the action as sent.
 * @property outcome what the port returned.
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
 * @property outcome what the port returned.
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
