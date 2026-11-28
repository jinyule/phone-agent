package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef

/**
 * One node of a screen the port showed, as [RefCarrier] remembers it: every node the read walked
 * ([TreeRead.nodes]), whatever the detail shown. [ref] is the element's ref, `null` for a node the
 * read left out; [parent] is the index in the tree of the node above.
 */
internal class TreeEntry(
    val ref: ElementRef?,
    val identity: ElementIdentity,
    val bounds: Bounds?,
    val parent: Int?,
)

/**
 * Keeps refs stable from one screen to the next, so the model can keep using `@e12` and a diff
 * that pairs screens by ref (WP06) shows only what changed: a ref that appears in both screens
 * names the same element. [RefMatcher] pairs a planned screen with a fresh read the same way.
 *
 * An element keeps its ref when the port can tell it is the same element: same [ElementIdentity]
 * (window, role, view id, name, its context: every label of its window outside lists, or every
 * label of its row) under the same parent, and that combination occurs exactly once in each
 * screen; among several, only the one at the same place in both screens keeps its ref. Parents
 * are paired first, so an element whose parent got a new ref gets one too. Anything ambiguous,
 * and any element whose identity is not [ElementIdentity.reliable] (read only in part, or a row
 * the list's edge cut down to its controls, it or a node above it), gets a new number above all
 * numbers handed out before, and its old ref is void: a ref never comes back for a different
 * element.
 *
 * The nodes a read leaves out (out of view, too small, alike to a kept node) are paired with the
 * rest by the same rules, as were they kept, by their place in the tree; they take no ref and no
 * number (fix list fwd2 B1). So an element below them keeps its ref only below the very nodes it
 * was below: one that comes in below another node alike, at the same place, gets a new ref. An
 * element keeps a ref only from an element: one that was left out and is kept now gets a new one.
 *
 * It renumbers rather than guess: any changed label outside lists (a clock, a carousel, a banner)
 * gives everything of that window outside lists new refs, the lists and their rows with it; any
 * changed text of a row (a counter, a countdown) gives that row new refs.
 *
 * What this cannot tell apart (phase1 §3.3 "已知缺口"):
 * - a row replaced by one holding the very same labels (rows without text of their own, a pager
 *   of photos described alike);
 * - elements alike within one context (same role, view id and name: two plain "+" buttons of one
 *   row) are told apart only by their place, so two that swap places swap refs;
 * - in a list whose rows have no node of their own, rows are split at the controls, and a
 *   control's part holds the text after the control before it, up to it. Where that text cannot
 *   tell which item it belongs to (only the control's own label, or only generic text such as a
 *   count or a price), the control is told apart by that text and its place only: when the row
 *   before is replaced or removed and another control alike comes to its place, that one takes
 *   the ref. Shapes measured: a control before its text (a checkbox, then its label: it is taken
 *   with the text before it); a text wrapped alone in a layout that takes no clicks (the layout is
 *   a row by itself); the second control of an item and those after it (share after like); and
 *   controls with generic text between them (a quantity stepper);
 * - a row the list's edge cut where the text left still tells nothing apart: rule 4 catches only
 *   rows cut down to their controls (Compose may take clipped nodes out of the tree).
 *
 * Refs live on every visible node, so switching between BRIEF and FULL keeps them.
 */
internal object RefCarrier {
    /**
     * The [refs] of a read, one per element, the [tree] to remember for the next read, and
     * [lastUsed], the highest number handed out so far.
     */
    class Carried(
        val refs: List<ElementRef>,
        val tree: List<TreeEntry>,
        val lastUsed: Int,
    )

    private const val PREFIX = "e"

    /** No node of the previous screen: what [pair] gives a fresh node it cannot pair. */
    const val NONE: Int = -1

    /** A fresh node whose parent was paired with no node of the previous screen: no old node has it as parent. */
    private data class Unpaired(
        val index: Int,
    )

    private data class Loose(
        val identity: ElementIdentity,
        val parent: Any?,
    )

    private data class Exact(
        val loose: Loose,
        val bounds: Bounds?,
    )

    /**
     * Refs for the elements of [fresh] (the nodes of one read, [TreeRead.nodes]) carried from
     * [previous], the tree of the last screen of this port (`null` for the first). [lastUsed] is
     * the highest number handed out before; new refs are numbered in reading order.
     */
    fun carry(
        previous: List<TreeEntry>?,
        fresh: List<ReadNode>,
        lastUsed: Int,
    ): Carried {
        val old = previous.orEmpty()
        val carried = refsOf(old, fresh, pair(old, fresh))
        var next = maxOf(lastUsed, old.maxOfOrNull(::numberOf) ?: 0)
        val refs = carried.map { it ?: ElementRef("$PREFIX${++next}") }
        val tree = fresh.map { TreeEntry(it.element?.let(refs::get), it.identity, it.bounds, it.parent) }
        return Carried(refs, tree, next)
    }

    /**
     * For each element of [fresh], the ref of the element of [previous] it is by [paired] (as
     * [pair] gives it), or `null`: paired with nothing, or with a node that was left out.
     */
    fun refsOf(
        previous: List<TreeEntry>,
        fresh: List<ReadNode>,
        paired: IntArray,
    ): List<ElementRef?> =
        fresh.indices.filter { fresh[it].element != null }.map { previous.getOrNull(paired[it])?.ref }

    /**
     * For each node of [fresh] (parents before children), the index in [previous] of the node it
     * is, or [NONE] when it cannot tell.
     */
    fun pair(
        previous: List<TreeEntry>,
        fresh: List<ReadNode>,
    ): IntArray {
        val trusted = previous.indices.filter { previous[it].identity.reliable }
        val looseOld = trusted.groupBy { Loose(previous[it].identity, previous[it].parent) }
        val exactOld = trusted.groupBy { Exact(Loose(previous[it].identity, previous[it].parent), previous[it].bounds) }
        val paired = IntArray(fresh.size) { NONE }
        val looseOf = { i: Int ->
            Loose(
                fresh[i].identity,
                fresh[i].parent?.let { parent ->
                    paired[parent].takeIf { it != NONE }
                        ?: Unpaired(parent)
                },
            )
        }
        for (level in fresh.indices
            .groupBy { fresh[it].depth }
            .toSortedMap()
            .values) {
            val loose = level.associateWith(looseOf)
            val exact = level.associateWith { Exact(loose.getValue(it), fresh[it].bounds) }
            val looseCount = loose.values.groupingBy { it }.eachCount()
            val exactCount = exact.values.groupingBy { it }.eachCount()
            for (i in level.filter { fresh[it].identity.reliable }) {
                val same =
                    sole(exactOld[exact.getValue(i)], exactCount[exact.getValue(i)])
                        ?: sole(looseOld[loose.getValue(i)], looseCount[loose.getValue(i)])
                paired[i] = same ?: NONE
            }
        }
        return paired
    }

    /** The only old node, when it is also the only fresh one. */
    private fun sole(
        old: List<Int>?,
        freshCount: Int?,
    ): Int? = old?.singleOrNull()?.takeIf { freshCount == 1 }

    private fun numberOf(entry: TreeEntry): Int = entry.ref?.let { it.value.removePrefix(PREFIX).toIntOrNull() } ?: 0
}
