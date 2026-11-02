package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole

/**
 * Who an element is, as far as the accessibility tree tells, leaving out where it is and what it
 * currently holds (value, states): its window, role, view id, name, and the row it sits in.
 *
 * [row] tells apart the controls every row of a list has ("Like", "Reply", a checkbox): it is the
 * first labels of the innermost list item around the element, the item included (e.g. `[Bob,
 * Like]`). Labels are the nodes' own text or description, so a row's container counts even when
 * it is unnamed and dropped from BRIEF. A list inside the item adds its own label only: its rows
 * scroll, and the item (a page holding a scrolling list) stays the same. `null` outside lists.
 *
 * Used by [RefCarrier] for both carrying refs to the next screen and finding a planned element
 * again before an action ([RefMatcher]); there is no second notion of identity.
 */
internal data class ElementIdentity(
    val window: Int,
    val role: ElementRole,
    val resourceId: String?,
    val name: String?,
    val row: List<String>?,
)

/** Computes the [ElementIdentity] of every node of one read. */
internal object Identities {
    /** Labels of a list item that make up its signature. */
    const val ROW_LABELS: Int = 3

    /** Longest label kept in a signature. */
    const val LABEL_CHARS: Int = 64

    /** Wrappers looked through between a list and its items (ScrollView > LinearLayout > items). */
    private const val MAX_WRAPPERS = 3

    /**
     * What identities are made of, per node in pre-order: [parent] is the index of the node above,
     * [label] the node's own text or description, [list] whether it lists items.
     */
    class Facts(
        val window: Int,
        val role: ElementRole,
        val resourceId: String?,
        val name: String?,
        val label: String?,
        val list: Boolean,
        val bounds: Bounds,
        val parent: Int?,
    )

    /** The identity of each node of [nodes] (pre-order, parents first). */
    fun of(nodes: List<Facts>): List<ElementIdentity> {
        val children = nodes.indices.groupBy { nodes[it].parent }
        val items = HashSet<Int>()
        nodes.indices.filter { nodes[it].list }.forEach { items += itemsOf(nodes, it, children) }
        val end = subtreeEnds(nodes)
        val itemAround = arrayOfNulls<Int>(nodes.size)
        for (i in nodes.indices) {
            itemAround[i] = if (i in items) i else nodes[i].parent?.let { itemAround[it] }
        }
        val signatures = HashMap<Int, List<String>>()
        return nodes.mapIndexed { i, facts ->
            val row = itemAround[i]?.let { item -> signatures.getOrPut(item) { signature(nodes, item, end) } }
            ElementIdentity(facts.window, facts.role, facts.resourceId, facts.name, row)
        }
    }

    /**
     * Items of [list]: its children, looking through a single child that fills the whole list (the
     * content wrapper of a scroll view); a single smaller child is the list's only item.
     */
    private fun itemsOf(
        nodes: List<Facts>,
        list: Int,
        children: Map<Int?, List<Int>>,
    ): List<Int> {
        var level = children[list].orEmpty()
        var wrappers = 0
        while (level.size == 1 && covers(nodes[level.single()].bounds, nodes[list].bounds) &&
            wrappers++ < MAX_WRAPPERS
        ) {
            level = children[level.single()].orEmpty()
        }
        return level
    }

    private fun covers(
        outer: Bounds,
        inner: Bounds,
    ): Boolean =
        outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

    /** For each node, the index after its last descendant (pre-order keeps a subtree contiguous). */
    private fun subtreeEnds(nodes: List<Facts>): IntArray {
        val end = IntArray(nodes.size) { it + 1 }
        for (i in nodes.indices.reversed()) {
            nodes[i].parent?.let { parent -> end[parent] = maxOf(end[parent], end[i]) }
        }
        return end
    }

    /** The first labels of [item]'s subtree; a list's subtree adds the list's own label only. */
    private fun signature(
        nodes: List<Facts>,
        item: Int,
        end: IntArray,
    ): List<String> {
        val labels = ArrayList<String>(ROW_LABELS)
        var i = item
        while (i < end[item] && labels.size < ROW_LABELS) {
            nodes[i].label?.let { labels += it.take(LABEL_CHARS) }
            i = if (nodes[i].list) end[i] else i + 1
        }
        return labels
    }
}
