package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef

/**
 * One element of a screen the port showed, as [RefCarrier] remembers it: every visible node of
 * the read, whatever the detail shown.
 */
internal class TreeEntry(
    val ref: ElementRef,
    val identity: ElementIdentity,
    val bounds: Bounds,
    val parent: ElementRef?,
)

/**
 * Keeps refs stable from one screen to the next, so the model can keep using `@e12` and a diff
 * that pairs screens by ref (WP06) shows only what changed: a ref that appears in both screens
 * names the same element. [RefMatcher] pairs a planned screen with a fresh read the same way.
 *
 * An element keeps its ref when the port can tell it is the same element: same [ElementIdentity]
 * (window, role, view id, name, and its context: every label of its window outside lists, or every
 * label of its row) under the same parent, and that combination occurs exactly once in each
 * screen; among several, only the one at the same place in both screens keeps its ref. Parents are
 * paired first, so an element whose parent got a new ref gets one too. Anything ambiguous, and any
 * element whose identity is not [ElementIdentity.reliable] (read only in part, or a row the list's
 * edge cut down to its controls), gets a new number above all numbers handed out before, and its
 * old ref is void: a ref never comes back for a different element.
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
 * - in a list whose rows have no node of their own, a control that comes before its text (a
 *   checkbox, then its label) is taken with the text before it;
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

    /** A fresh node whose parent got no ref from [previous]: no old element can have it as parent. */
    private data class Unpaired(
        val index: Int,
    )

    private data class Loose(
        val identity: ElementIdentity,
        val parent: Any?,
    )

    private data class Exact(
        val loose: Loose,
        val bounds: Bounds,
    )

    /**
     * Refs for [fresh] (one read, parents before children) carried from [previous], the elements
     * of the last screen of this port (`null` for the first). [lastUsed] is the highest number
     * handed out before.
     */
    fun carry(
        previous: List<TreeEntry>?,
        fresh: List<ReadElement>,
        lastUsed: Int,
    ): Carried {
        val old = previous.orEmpty()
        val paired = pair(old, fresh)
        var next = maxOf(lastUsed, old.maxOfOrNull(::numberOf) ?: 0)
        val refs = paired.map { it ?: ElementRef("$PREFIX${++next}") }
        val tree =
            fresh.mapIndexed { i, read ->
                TreeEntry(refs[i], read.identity, read.element.bounds, read.parent?.let(refs::get))
            }
        return Carried(refs, tree, next)
    }

    /** For each element of [fresh], the ref of the element of [previous] it is, or `null` when it cannot tell. */
    fun pair(
        previous: List<TreeEntry>,
        fresh: List<ReadElement>,
    ): List<ElementRef?> {
        val trusted = previous.filter { it.identity.reliable }
        val exactOld = trusted.groupBy { Exact(Loose(it.identity, it.parent), it.bounds) }
        val looseOld = trusted.groupBy { Loose(it.identity, it.parent) }
        val paired = arrayOfNulls<ElementRef>(fresh.size)
        val looseOf = { i: Int -> Loose(fresh[i].identity, fresh[i].parent?.let { paired[it] ?: Unpaired(it) }) }
        for (level in fresh.indices
            .groupBy { fresh[it].depth }
            .toSortedMap()
            .values) {
            val loose = level.associateWith(looseOf)
            val exact = level.associateWith { Exact(loose.getValue(it), fresh[it].element.bounds) }
            val looseCount = loose.values.groupingBy { it }.eachCount()
            val exactCount = exact.values.groupingBy { it }.eachCount()
            for (i in level.filter { fresh[it].identity.reliable }) {
                val same =
                    sole(exactOld[exact.getValue(i)], exactCount[exact.getValue(i)])
                        ?: sole(looseOld[loose.getValue(i)], looseCount[loose.getValue(i)])
                paired[i] = same?.ref
            }
        }
        return paired.toList()
    }

    /** The only old element, when it is also the only fresh one. */
    private fun sole(
        old: List<TreeEntry>?,
        freshCount: Int?,
    ): TreeEntry? = old?.singleOrNull()?.takeIf { freshCount == 1 }

    private fun numberOf(entry: TreeEntry): Int =
        entry.ref.value
            .removePrefix(PREFIX)
            .toIntOrNull() ?: 0
}
