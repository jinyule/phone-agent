package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef

/**
 * One element of a screen the port showed, as [RefCarrier] remembers it: every visible node of
 * the read, whatever the detail shown. [around] is [ReadElement.around].
 */
internal class TreeEntry(
    val ref: ElementRef,
    val identity: ElementIdentity,
    val bounds: Bounds,
    val parent: ElementRef?,
    val around: Around? = null,
)

/**
 * Keeps refs stable from one screen to the next, so the model can keep using `@e12` and a diff
 * that pairs screens by ref (WP06) shows only what changed: a ref that appears in both screens
 * names the same element.
 *
 * An element keeps its ref when the port can tell it is the same element: same [ElementIdentity]
 * (window, role, view id, name, and every label of its row) under the same parent, and that
 * combination occurs exactly once in each screen; among several, only the one at the same place in
 * both screens keeps its ref. An element in no row keeps its ref only while the labels [around]
 * [Around] it fit. Parents are paired first, so an element whose parent got a new ref gets one
 * too. Anything ambiguous, and any element whose identity is not [ElementIdentity.reliable] (the
 * read stopped inside its row or its surroundings), gets a new number above all numbers handed out
 * before, and its old ref is void: a ref never comes back for a different element.
 *
 * What this cannot tell: a row replaced by one holding the very same labels (rows without text of
 * their own, a pager of photos described alike); an element in no row whose surroundings changed
 * only in numbers ("3/10" to "4/10"); a container outside lists that is not clickable keeps its
 * ref when what it holds is replaced (its content does not). And it renumbers rather than guess:
 * any change of a row's text (a counter, a relative time, a countdown) gives the whole row new
 * refs, as does any replaced label around an element in no row.
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
                TreeEntry(refs[i], read.identity, read.element.bounds, read.parent?.let(refs::get), read.around)
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
                paired[i] = same?.takeIf { fits(it.around, fresh[i].around) }?.ref
            }
        }
        return paired.toList()
    }

    /** Whether the labels around an element still fit; an element in a row has none to compare. */
    private fun fits(
        old: Around?,
        fresh: Around?,
    ): Boolean = old == null || fresh == null || old.fits(fresh)

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
