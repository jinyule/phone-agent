package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail

/**
 * A snapshot the port showed, with the [detail] it was shown in and the [tree] behind it (every
 * visible node with its ref, shown or not).
 */
internal class Observed(
    val snapshot: ScreenSnapshot,
    val detail: SnapshotDetail,
    val tree: List<TreeEntry>,
)

/**
 * The most recent snapshots of one port, so actions can name the snapshot they were planned on.
 * Adapted from Mika's NodeIds (Apache-2.0, see NOTICE). Modified by PhoneAgent contributors.
 */
internal class SnapshotHistory(
    private val keep: Int,
) {
    private val snapshots = ArrayDeque<Observed>()

    init {
        require(keep >= 1) { "keep must be >= 1, got $keep" }
    }

    /** Remembers [observed], forgetting the oldest beyond [keep]. */
    @Synchronized
    fun put(observed: Observed) {
        snapshots.addFirst(observed)
        while (snapshots.size > keep) snapshots.removeLast()
    }

    /** The snapshot [id], or `null` when it is unknown or was forgotten. */
    @Synchronized
    fun get(id: SnapshotId): Observed? = snapshots.firstOrNull { it.snapshot.id == id }

    /** The latest snapshot, or `null` before the first. */
    @Synchronized
    fun newest(): Observed? = snapshots.firstOrNull()
}

/**
 * Finds the element an action was planned on in a fresh read (phase1 §3.3 "防过期引用", idea from
 * dsh). The fresh read is paired with the planned screen exactly as the next snapshot would carry
 * refs ([RefCarrier.pair], one notion of identity); the element is found when its ref would carry
 * and it is still where the model saw it. Values and states may change (typing, toggling).
 */
internal object RefMatcher {
    /** Result of [match]. */
    sealed interface Match {
        /** The planned element is [element] in the fresh read. */
        class Found(
            val element: ReadElement,
        ) : Match

        /** The element is gone, moved, replaced or ambiguous: the model must observe again. */
        data object Stale : Match

        /** [ref] was not shown in the planned snapshot. */
        data object UnknownRef : Match
    }

    /** Finds [ref] of [planned] in [fresh]. */
    fun match(
        planned: Observed,
        ref: ElementRef,
        fresh: TreeRead,
    ): Match {
        if (planned.snapshot.elements.none { it.ref == ref }) return Match.UnknownRef
        val entry = planned.tree.first { it.ref == ref }
        val index = RefCarrier.pair(planned.tree, fresh.elements).indexOf(ref)
        val element = fresh.elements.getOrNull(index)
        return if (element != null && element.element.bounds == entry.bounds) Match.Found(element) else Match.Stale
    }
}
