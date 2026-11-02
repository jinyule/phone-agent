package dev.phoneagent.core.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.policy.RiskLexicon
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
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
 * After an action ([afterAction]) the model gets only the diff (`+` new, `~` changed, `-` gone
 * for elements; `+` new and `-` gone for OCR lines, each with its center, so a moved line is one
 * of each; see [ScreenDiffer] for what counts) unless the app changed, there is no diff, more than
 * [ScreenFormatConfig.fullPageShare] of the lines changed, or the diff cannot be shown faithfully
 * or within the budget; then it gets the full page with new elements marked `+`. When the later
 * screen was read without OCR after one with OCR lines ([ScreenDiff.ocrUnverified]), either form
 * says that those lines were not checked again.
 *
 * [vision] is whether the main model reads images (the same capability the tools are assembled
 * with, `ToolsConfig.vision`): without it no note suggests a screenshot. Built only by
 * [ScreenComponents], with the [config] its analyzer reads lines with.
 */
public class ScreenFormatter internal constructor(
    private val config: ScreenFormatConfig = ScreenFormatConfig(),
    private val lexicon: RiskLexicon = RiskLexicon { _, _ -> null },
    vision: Boolean = true,
) : ScreenPresenter {
    private val briefLines = ElementLines(config, SnapshotDetail.BRIEF)
    private val fullLines = ElementLines(config, SnapshotDetail.FULL)
    private val headers = PageHeader(vision)

    override fun page(
        snapshot: ScreenSnapshot,
        detail: SnapshotDetail,
    ): String = render(snapshot, detail, added = emptySet(), notes = emptyList())

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
            notes = listOf("（${since}变化较大，下面是完整一屏，+ 是新出现的元素）") + diff.ocrNote(),
        )

    private fun render(
        snapshot: ScreenSnapshot,
        detail: SnapshotDetail,
        added: Set<ElementRef>,
        notes: List<String>,
    ): String {
        val layout = PageLayout(snapshot, config)
        val risky = riskyRefs(snapshot, layout)
        val lines = if (detail == SnapshotDetail.FULL) fullLines else briefLines
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
        val header = headers.lines(snapshot, notes)
        val ocr =
            snapshot.ocr.orEmpty().map { line ->
                val center = line.bounds.center()
                "\"${lines.clean(line.text)}\" (${center.x},${center.y})"
            }
        val budget = if (detail == SnapshotDetail.FULL) config.fullBudget else config.briefBudget
        return PageFolder(budget, detail).fold(header, blocks, ocr)
    }

    /**
     * The diff as `+` / `~` / `-` lines (elements, then OCR lines), or `null` when it cannot be
     * shown faithfully: it names an element [after] does not have, or it would not fit the brief
     * budget. The caller then sends the folded full page instead.
     */
    private fun diffView(
        after: ScreenSnapshot,
        diff: ScreenDiff,
        since: String,
    ): String? {
        val layout = PageLayout(after, config)
        val owners = (diff.added + diff.changed).map { layout.shownAs(it.ref) ?: return null }
        val risky = riskyRefs(after, layout)
        val addedRefs = diff.added.map { it.ref }.toSet()
        val order = after.elements.withIndex().associate { it.value.ref to it.index }
        val shown =
            owners
                .filter { layout.isPrinted(it) || it.isScrollContainer() }
                .distinctBy { it.ref }
                .sortedBy { order.getValue(it.ref) }
        val body =
            shown.map { element ->
                val mark = if (element.ref in addedRefs) "+ " else "~ "
                mark +
                    if (element.isScrollContainer()) {
                        briefLines.list(element, added = false)
                    } else {
                        briefLines.element(element, layout.absorbed[element.ref].orEmpty(), false, element.ref in risky)
                    }
            } + listOfNotNull(gone(diff, shown.map { it.ref }.toSet())) + ocrChanges(diff)
        val head =
            if (body.isEmpty()) {
                listOf("screen ${after.id} · ${since}没有可见变化")
            } else {
                listOf("screen ${after.id} · ${since}的变化（其余不变）")
            }
        val text = (head + headers.notes(after) + diff.ocrNote() + body).joinToString("\n")
        return text.takeIf { it.length <= config.briefBudget }
    }

    /**
     * The `-` line: refs of the earlier snapshot that are gone. A ref is left out only when the body
     * prints its new binding ([listed]); otherwise the model would keep the old meaning of it.
     */
    private fun gone(
        diff: ScreenDiff,
        listed: Set<ElementRef>,
    ): String? {
        val gone = diff.removed.filter { it !in listed }
        return if (gone.isEmpty()) null else gone.joinToString(" ", prefix = "- ") { "@$it" }
    }

    /** OCR lines that appeared (`+`) or went away (`-`), each at its center; a moved line is one of each. */
    private fun ocrChanges(diff: ScreenDiff): List<String> =
        diff.ocrAdded.map { ocrChange("+", it) } + diff.ocrRemoved.map { ocrChange("-", it) }

    private fun ocrChange(
        mark: String,
        line: OcrLine,
    ): String {
        val center = line.bounds.center()
        return "$mark $OCR_WORD \"${briefLines.clean(line.text)}\" (${center.x},${center.y})"
    }

    private fun riskyRefs(
        snapshot: ScreenSnapshot,
        layout: PageLayout,
    ): Set<ElementRef> =
        snapshot.elements
            .filter { it.isActionable() && layout.isPrinted(it) && lexicon.activationRisk(it, snapshot) != null }
            .map { it.ref }
            .toSet()

    /** Share of the page's lines (elements and OCR) that the diff adds, removes or changes. */
    private fun changedShare(
        after: ScreenSnapshot,
        diff: ScreenDiff,
    ): Double {
        val afterLines = PageLayout(after, config).blocks.sumOf { it.lines.size } + after.ocr.orEmpty().size
        val added = diff.added.size + diff.ocrAdded.size
        val removed = diff.removed.size + diff.ocrRemoved.size
        val beforeLines = afterLines - added + removed
        return (added + removed + diff.changed.size).toDouble() / maxOf(beforeLines, afterLines, 1)
    }

    private companion object {
        const val INDENT = "  "
        const val OCR_WORD = "屏上文字"
    }
}

/** The note for OCR lines of the earlier screen that this one did not check again, if any. */
private fun ScreenDiff.ocrNote(): List<String> =
    if (ocrUnverified) listOf("（这一屏没做文字识别，之前识别到的屏上文字和坐标没有重新核实）") else emptyList()
