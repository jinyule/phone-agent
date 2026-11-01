package dev.phoneagent.core.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.tool.ScreenPresenter

/**
 * The model-facing screen format v0 (phase1 §4). Pure: the same snapshot gives the same text, so
 * replay reproduces tool results byte for byte (the text is recorded in `tool/result` anyway).
 *
 * A page is a header, optional state and tree-quality notes, then the `[顶部]` / `[中部]` / `[底部]`
 * zones (see [PageLayout]) and the OCR text the tree lacks. Elements print as `@eN` lines (see
 * [ElementLines]); [lexicon] decides which actionable elements get `⚠`. Over the budget
 * ([ScreenFormatConfig.briefBudget] or [ScreenFormatConfig.fullBudget]) the middle zone is cut
 * and a line says how many items are hidden and where to scroll; the header and the top and
 * bottom zones are always kept.
 *
 * After an action ([afterAction]) the model gets only the diff (`+` new, `~` changed, `-` gone)
 * unless the app changed, there is no diff, or more than [ScreenFormatConfig.fullPageShare] of the
 * elements changed; then it gets the full page with new elements marked `+`.
 */
public class ScreenFormatter(
    private val config: ScreenFormatConfig = ScreenFormatConfig(),
    private val lexicon: RiskLexicon = RiskLexicon { _, _ -> null },
) : ScreenPresenter {
    private val lines = ElementLines(config)

    override fun page(
        snapshot: ScreenSnapshot,
        detail: SnapshotDetail,
    ): String = render(snapshot, detail, added = emptySet(), note = null)

    override fun afterAction(
        outcome: ActionOutcome,
        plannedOn: SnapshotId?,
    ): String {
        val after = outcome.after ?: return "（动作后没能读到屏幕，用 phone_observe 查看当前画面）"
        val diff = outcome.diff
        val since = plannedOn?.let { "与 $it 相比" } ?: "与上一屏相比"
        return when {
            diff == null || outcome.effect == ActionOutcome.Effect.NEW_APP -> page(after, SnapshotDetail.BRIEF)
            changedShare(after, diff) > config.fullPageShare -> fullAfterBigChange(after, diff, since)
            else -> diffView(after, diff, since) ?: fullAfterBigChange(after, diff, since)
        }
    }

    override fun mask(text: String): String = SensitiveNumbers.mask(text)

    private fun fullAfterBigChange(
        after: ScreenSnapshot,
        diff: ScreenDiff,
        since: String,
    ): String =
        render(
            after,
            SnapshotDetail.BRIEF,
            added = diff.added.map { it.ref }.toSet(),
            note = "（${since}变化较大，下面是完整一屏，+ 是新出现的元素）",
        )

    private fun render(
        snapshot: ScreenSnapshot,
        detail: SnapshotDetail,
        added: Set<ElementRef>,
        note: String?,
    ): String {
        val layout = PageLayout(snapshot, config)
        val risky = riskyRefs(snapshot, layout)
        val blocks =
            layout.blocks.map { block ->
                block to
                    block.lines.map { line ->
                        val element = line.element
                        val text =
                            if (line.header) {
                                lines.list(element, element.ref in added)
                            } else {
                                val absorbed = layout.absorbed[element.ref].orEmpty()
                                lines.element(element, absorbed, element.ref in added, element.ref in risky)
                            }
                        INDENT.repeat(line.depth) + text
                    }
            }
        val header = PageHeader.lines(snapshot, note)
        val ocr =
            snapshot.ocr.orEmpty().map { line ->
                val center = line.bounds.center()
                "\"${lines.clean(line.text)}\" (${center.x},${center.y})"
            }
        val budget = if (detail == SnapshotDetail.FULL) config.fullBudget else config.briefBudget
        return PageFolder(budget, detail).fold(header, blocks, ocr)
    }

    /**
     * The diff as `+` / `~` / `-` lines, or `null` when it would not fit the brief budget (the
     * caller then sends the folded full page instead).
     */
    private fun diffView(
        after: ScreenSnapshot,
        diff: ScreenDiff,
        since: String,
    ): String? {
        val layout = PageLayout(after, config)
        val risky = riskyRefs(after, layout)
        val addedRefs = diff.added.map { it.ref }.toSet()
        val order = after.elements.withIndex().associate { it.value.ref to it.index }
        val shown =
            (diff.added + diff.changed)
                .mapNotNull { layout.shownAs(it.ref) }
                .filter { layout.isPrinted(it) || it.isScrollContainer() }
                .distinctBy { it.ref }
                .sortedBy { order.getValue(it.ref) }
        val body =
            shown.map { element ->
                val mark = if (element.ref in addedRefs) "+ " else "~ "
                mark +
                    if (element.isScrollContainer()) {
                        lines.list(element, added = false)
                    } else {
                        lines.element(element, layout.absorbed[element.ref].orEmpty(), false, element.ref in risky)
                    }
            } + listOfNotNull(gone(after, diff))
        val head =
            if (body.isEmpty()) {
                listOf("screen ${after.id} · ${since}没有可见变化")
            } else {
                listOf("screen ${after.id} · ${since}的变化（其余不变）")
            }
        val text = (head + PageHeader.notes(after) + body).joinToString("\n")
        return text.takeIf { it.length <= config.briefBudget }
    }

    /**
     * The `-` line: refs of the earlier snapshot that disappeared. A ref the new snapshot reuses for
     * another element is left out; that element is listed as `+` or `~` under its ref.
     */
    private fun gone(
        after: ScreenSnapshot,
        diff: ScreenDiff,
    ): String? {
        val reused = after.elements.map { it.ref }.toSet()
        val gone = diff.removed.filter { it !in reused }
        return if (gone.isEmpty()) null else gone.joinToString(" ", prefix = "- ") { "@$it" }
    }

    private fun riskyRefs(
        snapshot: ScreenSnapshot,
        layout: PageLayout,
    ): Set<ElementRef> =
        snapshot.elements
            .filter { it.isActionable() && layout.isPrinted(it) && lexicon.activationRisk(it, snapshot) != null }
            .map { it.ref }
            .toSet()

    private fun changedShare(
        after: ScreenSnapshot,
        diff: ScreenDiff,
    ): Double {
        val before = after.elements.size - diff.added.size + diff.removed.size
        val total = maxOf(before, after.elements.size, 1)
        return (diff.added.size + diff.removed.size + diff.changed.size).toDouble() / total
    }

    private companion object {
        const val INDENT = "  "
    }
}
