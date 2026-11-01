package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement

/**
 * The screen capability's [SnapshotAnalyzer]: [ScreenFingerprint], [TreeQualityAssessor] and
 * [ScreenDiffer]. The surface and the page flags do not change the quality verdict: it is about
 * the tree alone (a FLAG_SECURE page can still have a good tree).
 *
 * [format] must be the config of the [ScreenFormatter] the model reads screens from: the quality
 * verdict and the diff both follow what a page line shows (absorbed texts included).
 */
public class ScreenAnalyzer(
    format: ScreenFormatConfig = ScreenFormatConfig(),
    quality: TreeQualityConfig = TreeQualityConfig(),
    diff: DiffConfig = DiffConfig(),
) : SnapshotAnalyzer {
    private val quality = TreeQualityAssessor(quality, format)
    private val differ = ScreenDiffer(diff, format)

    override fun fingerprint(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
        ocr: List<OcrLine>?,
    ): String = ScreenFingerprint.of(pkg, activity, elements, ocr)

    override fun quality(
        elements: List<UiElement>,
        surface: SurfaceRef,
        flags: Set<ScreenFlag>,
    ): TreeQuality = quality.assess(elements)

    override fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff = differ.diff(before, after)
}
