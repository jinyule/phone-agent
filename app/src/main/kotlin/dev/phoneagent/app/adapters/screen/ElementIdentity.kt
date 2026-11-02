package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.ElementRole
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Who an element is, as far as the accessibility tree tells, leaving out where it is and what it
 * currently holds (value, states): its window, role, view id, name, and the row it sits in.
 *
 * The row tells apart the controls every row has ("Like", "Add to cart", a checkbox): [row] stands
 * for every label of the row, in reading order, however many and however long ([RowKey]). Labels
 * are the nodes' own text or description, also of nodes clipped out of view that the row still
 * holds, so a row keeps its key while it scrolls. What counts as the row is decided by
 * [Identities.of]. An identity whose row, or whose surroundings ([Around]), could not be read in
 * full (the read stopped inside them) is not [reliable]: nothing is paired by it.
 *
 * Used by [RefCarrier] for both carrying refs to the next screen and finding a planned element
 * again before an action ([RefMatcher]); there is no second notion of identity.
 */
internal data class ElementIdentity(
    val window: Int,
    val role: ElementRole,
    val resourceId: String?,
    val name: String?,
    val row: RowKey?,
    val reliable: Boolean = true,
)

/**
 * Labels in reading order as a digest (SHA-256, its first 128 bits): two rows have the same key
 * only when they hold the same labels, whatever their number and length.
 */
internal data class RowKey(
    val high: Long,
    val low: Long,
) {
    internal companion object {
        /** The key of [labels], in order. */
        fun of(labels: List<String>): RowKey =
            labels.fold(Builder()) { builder, label -> builder.add(digestOf(label)) }.build()

        /** The digest of one label, which [Builder.add] takes. */
        fun digestOf(label: String): ByteArray = sha256().digest(label.toByteArray(Charsets.UTF_8))

        private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    /** Builds a key from label digests in order. */
    class Builder {
        private val digest = sha256()

        /** Adds the next label, as [digestOf] gives it. */
        fun add(labelDigest: ByteArray): Builder = apply { digest.update(labelDigest) }

        /** The key of the labels added. */
        fun build(): RowKey {
            val bytes = ByteBuffer.wrap(digest.digest())
            return RowKey(bytes.long, bytes.long)
        }
    }
}

/**
 * The [labels] around an element that is in no row, as a bag, digits left out. The element keeps
 * its ref only while one bag holds the other: labels may come or go around it (a banner, an error
 * message) and numbers may change (a player's time, a clock), but no label may be replaced
 * (another card in its place).
 */
internal class Around(
    labels: List<String>,
) {
    private val counts: Map<String, Int> = labels.groupingBy { it.filterNot(Char::isDigit) }.eachCount()

    /** Whether one of this and [other] holds the other. */
    fun fits(other: Around): Boolean = holds(this, other) || holds(other, this)

    private companion object {
        fun holds(
            big: Around,
            small: Around,
        ): Boolean = small.counts.all { (label, count) -> (big.counts[label] ?: 0) >= count }
    }
}

/** Computes the [ElementIdentity] of every node of one read. */
internal object Identities {
    /** How a list holds its items. */
    enum class ListKind {
        /** An adapter view or a node with collection info: each child is an item. */
        COLLECTION,

        /** A scroll view: its only child wraps the content, whose children are the items. */
        WRAPPED,

        /** Any other scrolling node: each child is an item. */
        PLAIN,
    }

    /**
     * What a node is for rows: [list] how it holds items (`null` when it is no list), [item]
     * whether it carries collection item info, [interactive] whether it takes clicks or checks.
     */
    class Shape(
        val list: ListKind?,
        val item: Boolean,
        val interactive: Boolean,
    )

    /**
     * What identities are made of, per node of the tree walked, in pre-order, whether the node is
     * visible or not: [base] is the identity without its row (`null` for a node the read leaves
     * out), [label] the node's whole text or description, [parent] the index of the node above,
     * [cut] whether the walk stopped inside its subtree (node or depth budget) before reaching a
     * list.
     */
    class Facts(
        val base: ElementIdentity?,
        val label: String?,
        val shape: Shape,
        val parent: Int?,
        val cut: Boolean,
    )

    /** The identity of one node and, for an element in no row, the labels [around] it. */
    class Who(
        val identity: ElementIdentity,
        val around: Around?,
    )

    /**
     * The identity of each node of [nodes] (pre-order, parents first), `null` where [Facts.base] is.
     *
     * The row of an element is:
     * - inside a list item: the innermost item (a list in the item adds its own label only, not
     *   its items, which scroll; the item stays the same);
     * - a clickable or checkable node outside lists holding labels below it: itself;
     * - none otherwise. A list, a scroll view's content wrapper or a node holding a list is the
     *   screen's structure; another container is told apart by its own identity; an element with
     *   no label below it but its own (a control, a text) is told apart by the labels [Who.around]
     *   it, those of the nearest node above holding other labels (a card, a dialog).
     */
    fun of(nodes: List<Facts>): List<Who?> = Rows(nodes).identities()

    /** One computation of [of]. */
    private class Rows(
        private val nodes: List<Facts>,
    ) {
        private val children = nodes.indices.groupBy { nodes[it].parent }
        private val items = HashSet<Int>()
        private val wrappers = HashSet<Int>()
        private val end = IntArray(nodes.size) { it + 1 }

        // Labels in each subtree (a list counting its own only) and whether the subtree holds a list.
        private val labels = IntArray(nodes.size)
        private val holdsList = BooleanArray(nodes.size)
        private val digests = HashMap<Int, ByteArray>()
        private val keys = HashMap<Int, RowKey>()
        private val arounds = HashMap<Int, Around>()

        init {
            nodes.indices.filter { nodes[it].shape.list != null }.forEach(::findItems)
            for (i in nodes.indices.reversed()) {
                labels[i] += own(i)
                nodes[i].parent?.let { parent ->
                    end[parent] = maxOf(end[parent], end[i])
                    val list = nodes[i].shape.list != null
                    labels[parent] += if (list) own(i) else labels[i]
                    holdsList[parent] = holdsList[parent] || list || holdsList[i]
                }
            }
        }

        fun identities(): List<Who?> {
            val itemAround = arrayOfNulls<Int>(nodes.size)
            return nodes.indices.map { i ->
                itemAround[i] = if (i in items) i else nodes[i].parent?.let { itemAround[it] }
                nodes[i].base?.let { base ->
                    val row = itemAround[i] ?: ownRow(i)
                    val holder = if (row == null) holderOf(i) else null
                    val identity =
                        base.copy(row = row?.let(::keyOf), reliable = listOfNotNull(row, holder).none { nodes[it].cut })
                    Who(identity, holder?.let(::aroundOf))
                }
            }
        }

        private fun own(i: Int): Int = if (nodes[i].label != null) 1 else 0

        /** Records the items of [list] and the wrapper looked through to reach them. */
        private fun findItems(list: Int) {
            val kids = children[list].orEmpty()
            val wrapper = kids.singleOrNull()?.takeIf { !children[it].isNullOrEmpty() }
            val collection = nodes[list].shape.list == ListKind.COLLECTION || kids.any { nodes[it].shape.item }
            if (!collection && nodes[list].shape.list == ListKind.WRAPPED && wrapper != null) {
                wrappers += wrapper
                items += children.getValue(wrapper)
            } else {
                items += kids
            }
        }

        /** Whether [i] is part of the screen's structure: a list, a wrapper, or holding a list. */
        private fun structural(i: Int): Boolean = nodes[i].shape.list != null || i in wrappers || holdsList[i]

        /** [i] itself, when it is a clickable container outside lists holding other labels. */
        private fun ownRow(i: Int): Int? =
            i.takeIf { !structural(i) && nodes[i].shape.interactive && labels[i] > own(i) }

        /** For a control or text outside rows, the nearest node above it holding labels outside it. */
        private fun holderOf(i: Int): Int? =
            if (structural(i) || labels[i] > own(i)) {
                null
            } else {
                generateSequence(nodes[i].parent) { nodes[it].parent }.firstOrNull { labels[it] > labels[i] }
            }

        /** The key of [owner]'s labels; a list below it adds its own label only. */
        private fun keyOf(owner: Int): RowKey =
            keys.getOrPut(owner) {
                val key = RowKey.Builder()
                labelsOf(owner).forEach { i -> key.add(digestOf(i)) }
                key.build()
            }

        private fun aroundOf(holder: Int): Around =
            arounds.getOrPut(holder) { Around(labelsOf(holder).map { checkNotNull(nodes[it].label) }) }

        /** Indices of the labelled nodes of [owner]'s subtree in reading order, skipping into lists. */
        private fun labelsOf(owner: Int): List<Int> {
            val found = ArrayList<Int>()
            var i = owner
            while (i < end[owner]) {
                if (nodes[i].label != null) found += i
                i = if (nodes[i].shape.list != null) end[i] else i + 1
            }
            return found
        }

        private fun digestOf(i: Int): ByteArray = digests.getOrPut(i) { RowKey.digestOf(checkNotNull(nodes[i].label)) }
    }
}
