package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.ElementRole
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Who an element is, as far as the accessibility tree tells, leaving out where it is and what it
 * currently holds (value, states): its window, role, view id, name, and the [context] that tells
 * it apart from elements alike (phase1 §3.3 "防过期引用").
 *
 * The context is the labels of the element's window outside lists when it is in no list, else of
 * its row ([Identities.of]). Labels are the nodes' own text or description, of nodes out of view
 * too while the tree still holds them, so scrolling a list renumbers neither the bars around it
 * nor a row that is still partly in view. An identity is not [reliable] when its context, or
 * that of a node above it, could not be read in full, or is a row the list's edge cut down to its
 * controls: nothing is paired by it.
 *
 * Used by [RefCarrier] for both carrying refs to the next screen and finding a planned element
 * again before an action ([RefMatcher]); there is no second notion of identity.
 */
internal data class ElementIdentity(
    val window: Int,
    val role: ElementRole,
    val resourceId: String?,
    val name: String?,
    val context: IdentityContext?,
    val reliable: Boolean = true,
)

/** What tells an element apart: the labels ([key]) of its window outside lists, or of its row ([scope]). */
internal data class IdentityContext(
    val scope: Scope,
    val key: LabelKey,
) {
    /** Whose labels [key] holds. */
    enum class Scope {
        /** The element's window, outside lists. */
        WINDOW,

        /** The element's row of a list. */
        ROW,
    }
}

/**
 * Labels in reading order as a digest (SHA-256, its first 128 bits): two contexts have the same
 * key only when they hold the same labels, whatever their number and length, digits included.
 */
internal data class LabelKey(
    val high: Long,
    val low: Long,
) {
    internal companion object {
        /** The key of [labels], in order. */
        fun of(labels: List<String>): LabelKey =
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
        fun build(): LabelKey {
            val bytes = ByteBuffer.wrap(digest.digest())
            return LabelKey(bytes.long, bytes.long)
        }
    }
}

/** Computes the [ElementIdentity] of every node of one read. */
internal object Identities {
    /**
     * What a node is for contexts: [list] whether it is a list, a collection that recycles or
     * lazily loads its children (a scroll view is not one: it holds all of its content);
     * [interactive] whether it takes clicks or checks.
     */
    class Shape(
        val list: Boolean,
        val interactive: Boolean,
    )

    /**
     * What the walk may have lost of a node: [cut], it left out a node below it with no list in
     * between (for a list: one of its children); [clipped], a child of a list that the list's edge
     * may have cut; [besideGap], a child of a list, a list too, right before or after a child of
     * that list that could not be read, so its row may be missing a part (fix list r4 C6).
     */
    class Loss(
        val cut: Boolean = false,
        val clipped: Boolean = false,
        val besideGap: Boolean = false,
    )

    /**
     * What identities are made of, per node of the tree walked, in pre-order, whether the node is
     * visible or not: [base] is the identity without its context (`null` for a node the read
     * leaves out), [label] the node's whole text or description, [parent] the index of the node
     * above, [loss] what the walk may have lost of it.
     */
    class Facts(
        val base: ElementIdentity?,
        val label: String?,
        val shape: Shape,
        val parent: Int?,
        val loss: Loss = Loss(),
    )

    /**
     * The identity of each node of [nodes] (pre-order, parents first), `null` where [Facts.base] is.
     *
     * The context of an element (fix list r3 B1):
     * - in no list: every label of its window outside lists, in reading order, of nodes out of
     *   view too; a list adds its own label only (rule 1). A list itself is in no list.
     * - in a list: its row. A child of the list that is a container (holding more than one label,
     *   a list, or nodes none of which takes clicks) is a row; the other children (a text, a
     *   button, a button holding its text: rows that have no node of their own) form rows in
     *   reading order, each ending after a child that takes clicks or checks (rule 2). A list in
     *   a row adds its own label only.
     *
     * A control's own label is what a node that takes clicks or checks is labelled with when all
     * labels of its subtree are of that one text: it counts once, and is of the control, however
     * many of the control's nodes repeat it (fix list r4 B1; a button "Like" holding a text "Like"
     * is one label, the button's).
     *
     * Not reliable (no ref is carried or matched by it):
     * - the walk left out a node with no list in between, by its budgets or because the node
     *   could not be read: in no list, the whole window; else the row it is in; a list that lost
     *   children to the budgets, its last row if that row is still open; a list that could not
     *   read a child, the rows of the children beside it, nested lists included (rule 3);
     * - a row the list's edge cut whose labels are all its controls' own (rule 4): what told it
     *   apart may be gone. Rows not cut and elements in no list are not held to this;
     * - a node below one of the above in the tree walked, whether the nodes between are kept or not
     *   (fix list fwd B1). A kept node above that is not reliable gets a new ref, and so does all
     *   below it; a node the read leaves out (out of view, or alike to a kept one) gets no ref, and
     *   what it lacks is passed down all the same.
     */
    fun of(nodes: List<Facts>): List<ElementIdentity?> = Contexts(nodes).identities()

    /** The nodes whose labels make one context: a window's root, or the children of a list in one row. */
    private class Owner(
        val scope: IdentityContext.Scope,
    ) {
        val members = ArrayList<Int>()

        /** A row that may have lost members the walk left out. */
        var incomplete = false
    }

    /** One computation of [of]. */
    private class Contexts(
        private val nodes: List<Facts>,
    ) {
        private val children = nodes.indices.groupBy { nodes[it].parent }
        private val end = IntArray(nodes.size) { it + 1 }

        // What tells each subtree apart (a list below counting its own label only): the labels, a
        // control's own label once however often its nodes repeat it; the one text its labels have,
        // and whether they have more than one.
        private val labels = IntArray(nodes.size)
        private val oneText = arrayOfNulls<String>(nodes.size)
        private val texts = BooleanArray(nodes.size)

        // Whether each subtree holds a list, and whether a node in it takes clicks or checks.
        private val holdsList = BooleanArray(nodes.size)
        private val clickable = BooleanArray(nodes.size)

        // Labels of a control's own: inside a control whose labels are all of one text, within the row.
        private val ofControl = BooleanArray(nodes.size)
        private val rowOf = arrayOfNulls<Owner>(nodes.size)
        private val keyed = HashMap<Owner, IdentityContext>()
        private val reliable = HashMap<Owner, Boolean>()
        private val digests = HashMap<Int, ByteArray>()

        init {
            for (i in nodes.indices.reversed()) {
                val label = nodes[i].label
                val own = if (label != null) 1 else 0
                label?.let { addText(i, it) }
                labels[i] = if (isControl(i)) 1 else labels[i] + own
                clickable[i] = clickable[i] || nodes[i].shape.interactive
                nodes[i].parent?.let { parent ->
                    val list = nodes[i].shape.list
                    end[parent] = maxOf(end[parent], end[i])
                    labels[parent] += if (list) own else labels[i]
                    if (list) label?.let { addText(parent, it) } else addTexts(parent, i)
                    holdsList[parent] = holdsList[parent] || list || holdsList[i]
                    clickable[parent] = clickable[parent] || clickable[i]
                }
            }
            for (i in nodes.indices) {
                val parent = nodes[i].parent
                val inControl = parent != null && !nodes[parent].shape.list && ofControl[parent]
                ofControl[i] = inControl || isControl(i)
            }
            nodes.indices.filter { nodes[it].shape.list }.forEach(::findRows)
        }

        /**
         * Whether [i] is a control told apart by its own label only: it takes clicks or checks, and
         * the labels of its subtree are all of one text (a button "Like" holding a text "Like").
         */
        private fun isControl(i: Int): Boolean = nodes[i].shape.interactive && oneText[i] != null && !texts[i]

        private fun addText(
            i: Int,
            text: String,
        ) {
            val one = oneText[i]
            if (one == null) {
                oneText[i] = text
            } else if (one != text) {
                texts[i] = true
            }
        }

        private fun addTexts(
            parent: Int,
            child: Int,
        ) {
            oneText[child]?.let { addText(parent, it) }
            texts[parent] = texts[parent] || texts[child]
        }

        fun identities(): List<ElementIdentity?> {
            val owner = arrayOfNulls<Owner>(nodes.size)
            // Per node walked, kept or not: its context and those of all nodes above it are reliable.
            val whole = BooleanArray(nodes.size)
            return nodes.indices.map { i ->
                val parent = nodes[i].parent
                val mine =
                    when {
                        parent == null -> Owner(IdentityContext.Scope.WINDOW).also { it.members += i }
                        nodes[parent].shape.list -> checkNotNull(rowOf[i])
                        else -> checkNotNull(owner[parent])
                    }
                owner[i] = mine
                whole[i] = reliableOf(mine) && (parent == null || whole[parent])
                nodes[i].base?.copy(context = contextOf(mine), reliable = whole[i])
            }
        }

        /** Splits the children of [list] into rows. */
        private fun findRows(list: Int) {
            var open: Owner? = null
            for (child in children[list].orEmpty()) {
                // A container: more than one label (a control's own once), a list in it, or nodes none
                // of which takes clicks.
                val container =
                    labels[child] > 1 || holdsList[child] || !(children[child].isNullOrEmpty() || clickable[child])
                if (nodes[child].shape.list || container) {
                    open = null
                    rowOf[child] = Owner(IdentityContext.Scope.ROW).also { it.members += child }
                } else {
                    val row = open ?: Owner(IdentityContext.Scope.ROW)
                    row.members += child
                    rowOf[child] = row
                    open = row.takeUnless { clickable[child] }
                }
            }
            if (nodes[list].loss.cut) open?.incomplete = true
        }

        private fun contextOf(owner: Owner): IdentityContext =
            keyed.getOrPut(owner) {
                val key = LabelKey.Builder()
                owner.members.flatMap(::labelsOf).forEach { i -> key.add(digestOf(i)) }
                IdentityContext(owner.scope, key.build())
            }

        private fun reliableOf(owner: Owner): Boolean =
            reliable.getOrPut(owner) {
                val whole = !owner.incomplete && owner.members.none { cutBelow(it) || nodes[it].loss.besideGap }
                val cutToControls =
                    owner.members.any { nodes[it].loss.clipped } &&
                        owner.members.flatMap(::labelsOf).all { ofControl[it] }
                whole && !cutToControls
            }

        /** Whether the walk left out a node of [i]'s own labels; a list's lost children are its rows'. */
        private fun cutBelow(i: Int): Boolean = nodes[i].loss.cut && !nodes[i].shape.list

        /** Indices of the labelled nodes of [owner]'s subtree in reading order, skipping into lists. */
        private fun labelsOf(owner: Int): List<Int> {
            val found = ArrayList<Int>()
            var i = owner
            while (i < end[owner]) {
                if (nodes[i].label != null) found += i
                i = if (nodes[i].shape.list) end[i] else i + 1
            }
            return found
        }

        private fun digestOf(i: Int): ByteArray =
            digests.getOrPut(i) { LabelKey.digestOf(checkNotNull(nodes[i].label)) }
    }
}
