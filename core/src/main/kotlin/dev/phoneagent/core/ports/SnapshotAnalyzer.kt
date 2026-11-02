package dev.phoneagent.core.ports

/**
 * The analysis behind [ScreenSnapshot.fingerprint], [ScreenSnapshot.quality] and
 * [ActionOutcome.diff]. Implemented in core (`core.screen`); a [ScreenPort] implementation gets
 * it by injection and never ships its own algorithm. Every method is pure and deterministic, so
 * the same content always yields the same values.
 */
public interface SnapshotAnalyzer {
    /**
     * A value that is equal exactly when the screen content is the same. [ocr] (`null` when OCR
     * did not run) takes part, so a screen with an empty or sparse tree (games, canvases) still
     * changes when its visible text does, instead of every action looking like no change.
     */
    public fun fingerprint(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
        ocr: List<OcrLine>?,
    ): String

    /** How usable the accessibility tree is for [surface] given [flags]. */
    public fun quality(
        elements: List<UiElement>,
        surface: SurfaceRef,
        flags: Set<ScreenFlag>,
    ): TreeQuality

    /**
     * What changed from [before] to [after]. Both come from the same [ScreenPort], so an equal ref
     * is the same element: elements are paired only by ref. A port that cannot tell an element is
     * the same gives it a new ref, and the diff then has the old ref removed and the new one added.
     */
    public fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff
}
