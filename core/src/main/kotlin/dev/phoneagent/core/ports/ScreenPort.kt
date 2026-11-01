package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.SnapshotId
import kotlinx.serialization.Serializable

/**
 * Reads and drives one surface (phase1 §3.3). The same implementation serves the main display and
 * agent displays, parameterized by [surface].
 *
 * Failure contract: expected per-action failures are values in [ActionOutcome]; a surface that
 * cannot be used at all throws [ScreenUnavailableException]. Methods are cancellable; a cancelled
 * [act] may or may not have reached the device (the caller records OUTCOME_UNKNOWN).
 * Calls are serialized by the caller: the screen is a shared resource (`Tool.concurrencySafe`).
 */
public interface ScreenPort {
    /** The display this port drives. */
    public val surface: SurfaceRef

    /** Observes the surface now. */
    public suspend fun snapshot(options: SnapshotOptions = SnapshotOptions()): ScreenSnapshot

    /**
     * Performs [action]. When [on] is given, element refs are resolved against that snapshot and
     * re-matched against a fresh tree first; a target that no longer matches yields
     * [ActionOutcome.Status.StaleRef] without touching the device.
     */
    public suspend fun act(
        action: UiAction,
        on: SnapshotId?,
    ): ActionOutcome

    /** Opens [target] on this surface. */
    public suspend fun launch(target: LaunchTarget): ActionOutcome

    /**
     * Captures a frame scaled so that its longer edge is at most [maxEdge] pixels, with Set-of-Mark
     * boxes when [som]. Returns `null` when the surface cannot be captured (e.g. FLAG_SECURE).
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
