/*
 * Adapted from Mika, app/src/main/java/com/wangzan101/mika/cap/phone/a11y/NodeIds.kt
 * (https://github.com/wangzan101/Mika, commit 9cef3750addd20b2af9e3214a8f5e56301670174),
 * Copyright 2026 wangzan101, licensed under the Apache License, Version 2.0 (see NOTICE): the
 * bounded registry of recent snapshots (SnapshotHistory).
 * Modified by PhoneAgent contributors: rewritten in English, merged into PhoneAgent's own classes.
 */
package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
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
        /**
         * The planned element is [element] in the fresh read; [carried] holds, for each element of
         * the fresh read, the ref of the planned screen's element it is ([RefCarrier.pair]), or
         * `null` for none.
         */
        class Found(
            val element: ReadElement,
            val carried: List<ElementRef?>,
        ) : Match

        /** The element is gone, moved, replaced or ambiguous ([reason]): the model must observe again. */
        data class Stale(
            val reason: StaleReason,
        ) : Match

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
        val paired = RefCarrier.pair(planned.tree, fresh.elements)
        val element = fresh.elements.getOrNull(paired.indexOf(ref))
        return when {
            element == null -> Match.Stale(StaleReasons.of(entry, planned.tree, fresh.elements, paired.toSet()))
            element.element.bounds == entry.bounds -> Match.Found(element, paired)
            else -> Match.Stale(StaleReason.MOVED)
        }
    }
}

/**
 * Explains why [RefCarrier.pair] paired a planned element with nothing; it decides nothing itself.
 * The nodes of an element's [ElementIdentity.lineage] count as the nodes they would be had the
 * reads kept them, between the element and its parent: in the planned screen, they are looked at
 * after the element and before its parent; in the fresh read, with the nodes kept.
 */
internal object StaleReasons {
    /** Why [entry] of [tree] is not among the [paired] refs of [fresh]. */
    fun of(
        entry: TreeEntry,
        tree: List<TreeEntry>,
        fresh: List<ReadElement>,
        paired: Set<ElementRef?>,
    ): StaleReason = of(entry, tree, fresh, paired, Read(fresh))

    /**
     * Of the fresh read's kept nodes and the nodes in their lineages: their contexts, and the
     * windows where any of them outside lists is unreliable.
     */
    private class Read(
        fresh: List<ReadElement>,
    ) {
        val contexts = HashSet<Pair<Int, IdentityContext?>>()
        val unreliableWindows = HashSet<Int>()

        init {
            for (element in fresh) {
                val identity = element.identity
                for (node in sequenceOf(identity) + identity.lineage?.nodes().orEmpty()) {
                    contexts += node.window to node.context
                    val windowScope = node.context?.scope == IdentityContext.Scope.WINDOW
                    if (windowScope && !node.reliable) unreliableWindows += node.window
                }
            }
        }
    }

    private fun of(
        entry: TreeEntry,
        tree: List<TreeEntry>,
        fresh: List<ReadElement>,
        paired: Set<ElementRef?>,
        read: Read,
    ): StaleReason {
        val identity = entry.identity
        if (!identity.reliable) return StaleReason.UNRELIABLE
        val parent = entry.parent?.takeIf { it !in paired }?.let { ref -> tree.first { it.ref == ref } }
        val bare = identity.copy(reliable = true, lineage = null)
        val alike = fresh.filter { it.identity.copy(reliable = true, lineage = null) == bare }
        val alikeBefore = tree.count { it.identity == identity && it.parent == entry.parent }
        // The element, then the nodes left out above it, nearest first, up to its parent.
        val upward = sequenceOf(identity) + identity.lineage?.nodes().orEmpty()
        return upward.firstNotNullOfOrNull { contextReason(it, read) } ?: when {
            parent != null -> of(parent, tree, fresh, paired, read)
            alike.any { !it.identity.reliable } -> StaleReason.UNRELIABLE
            alike.size > 1 || alikeBefore > 1 -> StaleReason.AMBIGUOUS
            else -> StaleReason.NOT_FOUND
        }
    }

    /** What the context of [identity] says in the fresh [read]: unreliable, changed, or `null` for found. */
    private fun contextReason(
        identity: ElementIdentity,
        read: Read,
    ): StaleReason? {
        val context = identity.context
        return when {
            context?.scope == IdentityContext.Scope.WINDOW && identity.window in read.unreliableWindows -> {
                StaleReason.UNRELIABLE
            }

            (identity.window to context) !in read.contexts -> {
                changed(context)
            }

            else -> {
                null
            }
        }
    }

    private fun changed(context: IdentityContext?): StaleReason =
        if (context?.scope == IdentityContext.Scope.ROW) StaleReason.ROW_CHANGED else StaleReason.WINDOW_CHANGED
}
