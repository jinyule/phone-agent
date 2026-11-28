package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A value computed from a snapshot, kept for the last snapshot asked for (the same object, not an
 * equal one), so the lexicon, the resolver and the engine reading one snapshot compute it once.
 * [compute] must be a pure function of the snapshot; threads that race compute the same value.
 */
internal class PerScreen<T : Any>(
    private val compute: (ScreenSnapshot) -> T,
) {
    @Volatile
    private var last: Pair<ScreenSnapshot, T>? = null

    fun of(screen: ScreenSnapshot): T {
        val cached = last
        return if (cached != null && cached.first === screen) {
            cached.second
        } else {
            compute(screen).also { last = screen to it }
        }
    }
}

/**
 * The tree of one snapshot, indexed once (policy-rules.md §8): elements by ref, each one's parent
 * and children, whether some ancestor takes clicks or long clicks, how deep the tree is and how
 * much text it holds. Built in time and space in proportion to the snapshot; immutable but for the content of
 * elements, read once each ([content]) and only ever set to the one value it has.
 */
internal class ScreenIndex private constructor(
    screen: ScreenSnapshot,
) {
    private val elements = screen.elements
    private val positions: Map<ElementRef, Int> = elements.withIndex().associate { (i, e) -> e.ref to i }
    private val parents = IntArray(elements.size) { position(elements[it].parent) }
    private val children: Map<ElementRef?, List<Int>> = elements.indices.groupBy { elements[it].parent }
    private val controlled = BooleanArray(elements.size)
    private val held = BooleanArray(elements.size)
    private val contents = AtomicReferenceArray<List<String>>(elements.size)

    /** The longest chain of parents (0 when every element is at the top); [Int.MAX_VALUE] when parents form a cycle. */
    val depth: Int = ancestry()

    /** Characters of all text on the screen: names, values, hints, resource ids and OCR lines. */
    val textLength: Long =
        elements.sumOf { e -> listOfNotNull(e.name, e.value, e.hint, e.resourceId).sumOf { it.length.toLong() } } +
            screen.ocr.orEmpty().sumOf { it.text.length.toLong() }

    /** The editable elements, in reading order. */
    val editables: List<UiElement> = elements.filter(Elements::editable)

    /** Positions of the elements by area, smallest first, in reading order among equals. */
    private val byArea: IntArray = elements.indices.sortedBy { Geometry.area(elements[it].bounds) }.toIntArray()

    fun byRef(ref: ElementRef): UiElement? = positions[ref]?.let(elements::get)

    /** The elements whose parent is [element], in reading order. */
    fun children(element: UiElement): List<UiElement> = children[element.ref].orEmpty().map(elements::get)

    /** Whether some ancestor of [element] takes clicks (meaningful when [depth] is finite). */
    fun inControl(element: UiElement): Boolean {
        val parent = position(element.parent)
        return parent >= 0 && (Elements.clickable(elements[parent]) || controlled[parent])
    }

    /** Whether some ancestor of [element] takes long clicks (meaningful when [depth] is finite). */
    fun inHeldControl(element: UiElement): Boolean {
        val parent = position(element.parent)
        return parent >= 0 && (Elements.longClickable(elements[parent]) || held[parent])
    }

    /** [element]'s ancestors, nearest first, computed as they are asked for; a cycle ends after every element. */
    fun ancestors(element: UiElement): Sequence<UiElement> =
        generateSequence(position(element.parent).takeIf { it >= 0 }) { at -> parents[at].takeIf { it >= 0 } }
            .take(elements.size)
            .map(elements::get)

    /**
     * As [Elements.content]: the names inside [element] at any depth in reading order, leaving out
     * other controls that do not cover its center and what is inside them. Read once for each
     * element of the snapshot; [element] may also be one that is not in it.
     */
    fun content(element: UiElement): List<String> {
        val at = positions[element.ref]
        if (at == null || elements[at] !== element) return contentOf(element)
        return contents.get(at) ?: contentOf(element).also { contents.set(at, it) }
    }

    /** Visits each element below [element] at most once ([Work.content]). */
    private fun contentOf(element: UiElement): List<String> {
        val center = Geometry.center(element.bounds)
        val seen = mutableSetOf(element.ref)
        val inside = mutableListOf<Int>()
        val queue = ArrayDeque(listOf(element.ref))
        while (queue.isNotEmpty()) {
            val below = children[queue.removeFirst()].orEmpty()
            Work.content.add(below.size.toLong())
            for (at in below) {
                val child = elements[at]
                val separate = Elements.clickable(child) && !Geometry.contains(child.bounds, center.x, center.y)
                if (!separate && seen.add(child.ref)) {
                    queue.addLast(child.ref)
                    inside += at
                }
            }
        }
        return inside.sorted().mapNotNull { elements[it].name?.takeIf(String::isNotBlank) }
    }

    /**
     * As [Tree.under]: the elements containing ([x], [y]) that take the touch (clickable, or also
     * long-clickable when [long]) and contain no other one there, smallest first; without any, the
     * smallest element there. Makes [PASSES] passes over the snapshot and marks each element at
     * most once as the ancestor of one there, so it looks at most `(PASSES + 1) · n` times at an
     * element of a snapshot of `n` ([Work.under]).
     */
    fun under(
        x: Int,
        y: Int,
        long: Boolean,
    ): List<UiElement> {
        val hit = BooleanArray(elements.size) { Geometry.contains(elements[it].bounds, x, y) }
        val takes =
            BooleanArray(elements.size) {
                hit[it] && (Elements.clickable(elements[it]) || (long && Elements.longClickable(elements[it])))
            }
        val outer = BooleanArray(elements.size)
        var marked = 0L
        for (control in elements.indices) {
            var at = if (takes[control]) parents[control] else -1
            while (at >= 0 && !outer[at]) {
                outer[at] = true
                marked++
                at = parents[at]
            }
        }
        val innermost = byArea.filter { takes[it] && !outer[it] }
        Work.touches.increment()
        Work.under.add(PASSES * elements.size + marked)
        return innermost.ifEmpty { listOfNotNull(byArea.firstOrNull { hit[it] }) }.map(elements::get)
    }

    private fun position(ref: ElementRef?): Int = ref?.let(positions::get) ?: -1

    /**
     * Fills [controlled] and [held] from the top down and returns the longest chain of parents, or
     * [Int.MAX_VALUE] as soon as a walk up the parents comes back to itself. Each element is on
     * one walk only.
     */
    private fun ancestry(): Int {
        val depths = IntArray(elements.size) { UNKNOWN }
        var deepest = 0
        for (start in elements.indices) {
            val path = mutableListOf<Int>()
            var at = start
            while (at >= 0 && depths[at] == UNKNOWN) {
                depths[at] = ON_PATH
                path += at
                at = parents[at]
            }
            if (at >= 0 && depths[at] == ON_PATH) return Int.MAX_VALUE
            for (node in path.asReversed()) {
                val parent = parents[node]
                depths[node] = if (parent < 0) 0 else depths[parent] + 1
                controlled[node] = parent >= 0 && (Elements.clickable(elements[parent]) || controlled[parent])
                held[node] = parent >= 0 && (Elements.longClickable(elements[parent]) || held[parent])
                deepest = maxOf(deepest, depths[node])
            }
        }
        return deepest
    }

    /** Constants and the shared index. */
    companion object {
        private const val UNKNOWN = -2
        private const val ON_PATH = -1

        /**
         * Passes of [under] over the snapshot: whether each element is there, whether it takes the
         * touch, its ancestors, those left in area order, and the smallest one there when none is left.
         */
        private const val PASSES = 5L
        private val LAST = PerScreen(::ScreenIndex)

        /** The index of [screen], built once for the last snapshot asked for. */
        fun of(screen: ScreenSnapshot): ScreenIndex = LAST.of(screen)
    }
}
