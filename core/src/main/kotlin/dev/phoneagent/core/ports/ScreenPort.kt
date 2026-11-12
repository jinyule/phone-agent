package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.SnapshotId
import kotlinx.serialization.Serializable

/**
 * Reads and drives one surface (phase1 §3.3). The same implementation serves the main display and
 * agent displays, parameterized by [surface].
 *
 * Failure contract: expected per-action failures are values in [ActionOutcome]; a surface that
 * cannot be used at all throws [ScreenUnavailableException]. Methods are cancellable; a cancelled
 * [act] may or may not have reached the device, and the call's result says so for each way it can
 * end (the call has its `tool/started`): a stop gives CANCELLED with a text that it may have taken
 * effect and must be observed first; the call's timeout gives TIMEOUT, likewise; a crash leaves no
 * result, and repair writes OUTCOME_UNKNOWN.
 * Calls are serialized by the caller: the screen is a shared resource (`Tool.concurrencySafe`).
 */
public interface ScreenPort {
    /** The display this port drives. */
    public val surface: SurfaceRef

    /** Observes the surface now, at [SnapshotOptions.detail]; the snapshot's `detail` says so. */
    public suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): ScreenSnapshot

    /**
     * Performs [action]. When [on] is given, element refs are resolved against that snapshot and
     * re-matched against a fresh tree first; a target that no longer matches yields
     * [ActionOutcome.Status.StaleRef] with its [ActionOutcome.StaleReason] without touching the
     * device.
     */
    public suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome

    /** Opens [target] on this surface. */
    public suspend fun launch(target: LaunchTarget): ActionOutcome

    /**
     * Captures a frame scaled so that its longer edge is at most [maxEdge] pixels. Returns `null`
     * when the surface cannot be captured (e.g. FLAG_SECURE).
     *
     * With [som], Set-of-Mark boxes are drawn for the elements of the latest snapshot this port
     * returned from [snapshot], labelled with their refs, so the labels match that snapshot's screen
     * format. Without such a snapshot, or when the boxes cannot be placed, the frame is returned
     * without boxes and `Frame.som` is `false`. The caller records the snapshot's id in
     * `screen/frame.snapshotId`.
     */
    public suspend fun frame(
        maxEdge: Int = DEFAULT_FRAME_EDGE,
        som: Boolean = false,
    ): Frame?

    /**
     * Suspends until the surface changes or [timeoutMs] elapses (event-driven, no polling);
     * returns whether a change was seen.
     */
    public suspend fun awaitChange(timeoutMs: Long): Boolean

    /** Shared defaults. */
    public companion object {
        /** Longest frame edge sent to models (model-layer §3.3: ≤ 1280). */
        public const val DEFAULT_FRAME_EDGE: Int = 1280
    }
}

/**
 * What [ScreenPort.snapshot] collects.
 *
 * @property detail how many elements to keep.
 * @property ocr allow OCR when the tree quality is not [TreeQuality.GOOD].
 */
@Serializable
public data class SnapshotOptions(
    val detail: SnapshotDetail = SnapshotDetail.BRIEF,
    val ocr: Boolean = true,
)

/** Snapshot detail level (`phone_observe detail`). */
@Serializable
public enum class SnapshotDetail {
    /** Elements worth acting on, folded to the screen-format budget. */
    BRIEF,

    /** Every element. */
    FULL,
}
