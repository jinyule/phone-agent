package dev.phoneagent.core.screen

import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.tool.ScreenPresenter

/**
 * The screen capability as the app assembles it: the [analyzer] for the ScreenPort (fingerprint,
 * tree quality, diff) and the [presenter] for the tools (the model-facing text), both built from
 * the one [format]. The quality verdict and the diff follow what a page line shows, absorbed
 * texts included, so an analyzer and a presenter with different configs would report changes the
 * model cannot see and miss ones it can; their classes are therefore built only here.
 *
 * @param lexicon decides which actionable elements the presenter marks `⚠` (the policy's lexicon).
 * @param vision whether the main model reads images. This is its only source: the presenter
 *   follows it in its notes and hands it to the tools as [ScreenPresenter.vision].
 */
public class ScreenComponents(
    format: ScreenFormatConfig = ScreenFormatConfig(),
    quality: TreeQualityConfig = TreeQualityConfig(),
    diff: DiffConfig = DiffConfig(),
    lexicon: RiskLexicon,
    vision: Boolean,
) {
    /** Fingerprint, tree quality and diff, reading lines with [format]. */
    public val analyzer: SnapshotAnalyzer = ScreenAnalyzer(format, quality, diff)

    /** Pages, diffs after actions and masking, printed with [format]. */
    public val presenter: ScreenPresenter = ScreenFormatter(format, lexicon, vision)
}
