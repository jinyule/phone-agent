package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/**
 * The most recent snapshots of one port, so actions can name the snapshot they were planned on.
 * Adapted from Mika's NodeIds (Apache-2.0, see NOTICE). Modified by PhoneAgent contributors.
 */
internal class SnapshotHistory(
    private val keep: Int,
) {
    private val snapshots = ArrayDeque<ScreenSnapshot>()

    init {
        require(keep >= 1) { "keep must be >= 1, got $keep" }
    }

    /** Remembers [snapshot], forgetting the oldest beyond [keep]. */
    @Synchronized
    fun put(snapshot: ScreenSnapshot) {
        snapshots.addFirst(snapshot)
        while (snapshots.size > keep) snapshots.removeLast()
    }

    /** The snapshot [id], or `null` when it is unknown or was forgotten. */
    @Synchronized
    fun get(id: SnapshotId): ScreenSnapshot? = snapshots.firstOrNull { it.id == id }

    /** The latest snapshot, or `null` before the first. */
    @Synchronized
    fun newest(): ScreenSnapshot? = snapshots.firstOrNull()
}

/**
 * Finds the element an action was planned on in a fresh read (phase1 §3.3 "防过期引用", idea from
 * dsh). An element is identified by window, role, name and normalized bounds; values and states may
 * change (typing, toggling) without losing it. When several elements share that identity, the k-th
 * one matches the k-th, and only if their count is unchanged.
 */
internal object RefMatcher {
    /** Result of [match]. */
    sealed interface Match {
        /** The planned element is [element] in the fresh read. */
        class Found(
            val element: ReadElement,
        ) : Match

        /** The element is gone, moved or ambiguous: the model must observe again. */
        data object Stale : Match

        /** [ref] never existed in the planned snapshot. */
        data object UnknownRef : Match
    }

    /** Identity of an element across reads. */
    data class Key(
        val window: Int?,
        val role: ElementRole,
        val name: String?,
        val bounds: Bounds,
    )

    /** The identity of [element]. */
    fun keyOf(element: UiElement): Key = Key(element.window, element.role, element.name, element.bounds)

    /** Finds [ref] of [planned] in [fresh]. */
    fun match(
        planned: ScreenSnapshot,
        ref: ElementRef,
        fresh: List<ReadElement>,
    ): Match {
        val element = planned.elements.firstOrNull { it.ref == ref } ?: return Match.UnknownRef
        val key = keyOf(element)
        val siblings = planned.elements.filter { keyOf(it) == key }
        val candidates = fresh.filter { keyOf(it.element) == key }
        // Same identity several times (e.g. identical list rows): the k-th stays the k-th only if
        // the count did not change.
        return if (candidates.size == siblings.size) {
            Match.Found(candidates[siblings.indexOfFirst { it.ref == ref }])
        } else {
            Match.Stale
        }
    }
}
