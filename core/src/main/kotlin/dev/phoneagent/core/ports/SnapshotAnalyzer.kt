package dev.phoneagent.core.ports

/**
 * The analysis behind [ScreenSnapshot.fingerprint], [ScreenSnapshot.quality] and
 * [ActionOutcome.diff]. Implemented in core (`core.screen`); a [ScreenPort] implementation gets
 * it by injection and never ships its own algorithm. Every method is pure and deterministic, so
 * the same content always yields the same values.
 */
public interface SnapshotAnalyzer {
    /** A value that is equal exactly when the screen content is the same. */
    public fun fingerprint(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
    ): String

    /** How usable the accessibility tree is for [surface] given [flags]. */
    public fun quality(
        elements: List<UiElement>,
        surface: SurfaceRef,
        flags: Set<ScreenFlag>,
    ): TreeQuality

    /** What changed from [before] to [after]. */
    public fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff
}
