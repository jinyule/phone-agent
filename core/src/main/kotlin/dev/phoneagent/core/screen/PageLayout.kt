package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * The structure of one snapshot as the screen format shows it (phase1 §4):
 *
 * - **Absorption**: a plain text leaf (not actionable, not scrollable, not secret) whose nearest
 *   actionable ancestor is reached without crossing a scrollable container is merged into that
 *   ancestor's label and gets no line of its own.
 * - **Blocks**: every printed element outside scrollable containers is a block; a top-level
 *   scrollable container is one block holding its header and its whole subtree, children one
 *   indent level deeper per container.
 * - **Zones**: a block is in the top, middle or bottom zone by the vertical center of its first
 *   element ([ScreenFormatConfig.topZoneEnd], [ScreenFormatConfig.bottomZoneStart]).
 *
 * Elements whose parent is missing from the snapshot, or whose parent chain loops, are treated
 * as top-level, so every element is considered exactly once.
 */
internal class PageLayout(
    snapshot: ScreenSnapshot,
    private val config: ScreenFormatConfig,
) {
    private val elements = snapshot.elements
    private val byRef = elements.associateBy { it.ref }
    private val children: Map<ElementRef?, List<UiElement>> = elements.groupBy { effectiveParent(it) }

    /** Owner of each absorbed element. */
    private val ownerOf: Map<ElementRef, ElementRef> =
        elements.mapNotNull { element -> ownerOrNull(element)?.let { element.ref to it } }.toMap()

    /** Texts merged into each owner's label, in reading order. */
    val absorbed: Map<ElementRef, List<String>> =
        elements
            .filter { it.ref in ownerOf }
            .groupBy({ ownerOf.getValue(it.ref) }, { it.name?.takeIf(String::isNotBlank) ?: it.value.orEmpty() })

    /** Blocks in reading order. */
    val blocks: List<Block> = buildBlocks()

    /** The element whose line shows [ref]: its owner when absorbed, else itself; `null` when unknown. */
    fun shownAs(ref: ElementRef): UiElement? = byRef[ownerOf[ref] ?: ref]

    /** Whether [element] gets a line (it has text, can be acted on, is secret, or absorbed texts). */
    fun isPrinted(element: UiElement): Boolean =
        element.ref !in ownerOf &&
            (element.hasText() || element.isActionable() || element.secret || element.ref in absorbed)

    private fun buildBlocks(): List<Block> {
        val visited = HashSet<ElementRef>()
        val blocks = mutableListOf<Block>()
        val roots = children[null].orEmpty()
        roots.forEach { visitTopLevel(it, visited, blocks) }
        // Elements in a parent loop are unreachable from the roots; show them as top-level too.
        elements.filter { it.ref !in visited }.forEach { visitTopLevel(it, visited, blocks) }
        return blocks
    }

    private fun visitTopLevel(
        element: UiElement,
        visited: MutableSet<ElementRef>,
        blocks: MutableList<Block>,
    ) {
        if (!visited.add(element.ref)) return
        when {
            element.isScrollContainer() -> {
                val lines = mutableListOf(PageLine(element, depth = 0, list = element.ref, header = true))
                children[element.ref].orEmpty().forEach { visitInList(it, 1, element.ref, visited, lines) }
                blocks += Block(zoneOf(element), element.ref, lines)
            }

            else -> {
                if (isPrinted(element)) {
                    blocks +=
                        Block(zoneOf(element), null, listOf(PageLine(element, depth = 0, list = null, header = false)))
                }
                children[element.ref].orEmpty().forEach { visitTopLevel(it, visited, blocks) }
            }
        }
    }

    private fun visitInList(
        element: UiElement,
        depth: Int,
        list: ElementRef,
        visited: MutableSet<ElementRef>,
        lines: MutableList<PageLine>,
    ) {
        if (!visited.add(element.ref)) return
        val nested = element.isScrollContainer()
        if (nested) {
            lines += PageLine(element, depth, element.ref, header = true)
        } else if (isPrinted(element)) {
            lines += PageLine(element, depth, list, header = false)
        }
        val childDepth = if (nested) depth + 1 else depth
        val childList = if (nested) element.ref else list
        children[element.ref].orEmpty().forEach { visitInList(it, childDepth, childList, visited, lines) }
    }

    private fun zoneOf(element: UiElement): Zone {
        val y = element.bounds.center().y
        return when {
            y < config.topZoneEnd -> Zone.TOP
            y > config.bottomZoneStart -> Zone.BOTTOM
            else -> Zone.MIDDLE
        }
    }

    /** The parent when it is in the snapshot and not part of a loop; else `null` (top level). */
    private fun effectiveParent(element: UiElement): ElementRef? {
        val parent = element.parent?.takeIf { it in byRef } ?: return null
        return if (ancestorsOf(element).contains(element.ref)) null else parent
    }

    /** Ancestors from the parent upwards, stopping at the top or at the first repeated ref. */
    private fun ancestorsOf(element: UiElement): List<ElementRef> {
        val seen = LinkedHashSet<ElementRef>()
        var next = element.parent
        while (next != null && next in byRef && seen.add(next)) next = byRef.getValue(next).parent
        return seen.toList()
    }

    private fun ownerOrNull(element: UiElement): ElementRef? {
        val plain =
            !element.isActionable() &&
                !element.isScrollContainer() &&
                !element.secret &&
                (!element.name.isNullOrBlank() || !element.value.isNullOrBlank()) &&
                elements.none { it.parent == element.ref }
        if (!plain || effectiveParent(element) == null) return null
        val stop = ancestorsOf(element).map(byRef::getValue).firstOrNull { it.isScrollContainer() || it.isActionable() }
        return stop?.takeIf { it.isActionable() && !it.isScrollContainer() }?.ref
    }
}

/** Vertical zone of a block. */
internal enum class Zone(
    /** Zone header in the screen format. */
    val title: String,
) {
    TOP("[顶部]"),
    MIDDLE("[中部]"),
    BOTTOM("[底部]"),
}

/**
 * A unit of the page that is placed in one zone.
 *
 * @property zone where it goes.
 * @property list the top-level scrollable container it is, or `null` for a single element.
 * @property lines its lines in order.
 */
internal data class Block(
    val zone: Zone,
    val list: ElementRef?,
    val lines: List<PageLine>,
)

/**
 * One element's place on the page.
 *
 * @property element the element.
 * @property depth indent level (one per enclosing scrollable container).
 * @property list innermost scrollable container containing it (itself for a [header]), or `null`.
 * @property header whether this is a scrollable container's header line.
 */
internal data class PageLine(
    val element: UiElement,
    val depth: Int,
    val list: ElementRef?,
    val header: Boolean,
)
