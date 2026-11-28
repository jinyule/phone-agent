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
 * node walked: each visible node with its ref, shown or not, and the nodes the read left out).
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
        val entry = planned.tree.indices.first { planned.tree[it].ref == ref }
        val paired = RefCarrier.pair(planned.tree, fresh.nodes)
        val element =
            fresh.nodes
                .getOrNull(paired.indexOf(entry))
                ?.element
                ?.let(fresh.elements::get)
        return when {
            element == null -> {
                Match.Stale(StaleReasons.of(entry, planned.tree, fresh.nodes, paired))
            }

            element.element.bounds == planned.tree[entry].bounds -> {
                Match.Found(element, RefCarrier.refsOf(planned.tree, fresh.nodes, paired))
            }

            else -> {
                Match.Stale(StaleReason.MOVED)
            }
        }
    }
}

/**
 * Explains why [RefCarrier.pair] paired a planned element with no element of a fresh read; it
 * decides nothing itself. The nodes the reads left out count as kept ones: a node above the
 * element that was paired with nothing gives its own reason.
 */
internal object StaleReasons {
    /**
     * Why [entry] (an index in [tree]) is paired ([paired], as [RefCarrier.pair] gives it) with no element of
     * [fresh].
     */
    fun of(
        entry: Int,
        tree: List<TreeEntry>,
        fresh: List<ReadNode>,
        paired: IntArray,
    ): StaleReason {
        val pairedOld = BooleanArray(tree.size)
        paired.filter { it != RefCarrier.NONE }.forEach { pairedOld[it] = true }
        return of(entry, tree, pairedOld, Read(tree, fresh))
    }

    /**
     * Of the fresh read's nodes, kept or not: their contexts, the windows where any of them
     * outside lists is unreliable, and the nodes alike ([alikeKey]); of the planned [tree], how
     * many nodes have each identity under each parent.
     */
    private class Read(
        tree: List<TreeEntry>,
        fresh: List<ReadNode>,
    ) {
        val contexts = HashSet<Pair<Int, IdentityContext?>>()
        val unreliableWindows = HashSet<Int>()
        val alike: Map<ElementIdentity, List<ReadNode>> = fresh.groupBy { alikeKey(it.identity) }
        val before: Map<Pair<ElementIdentity, Int?>, Int> = tree.groupingBy { it.identity to it.parent }.eachCount()

        init {
            for (node in fresh) {
                val identity = node.identity
                contexts += identity.window to identity.context
                val windowScope = identity.context?.scope == IdentityContext.Scope.WINDOW
                if (windowScope && !identity.reliable) unreliableWindows += identity.window
            }
        }
    }

    /** Alike: the same identity, reliable or not. */
    private fun alikeKey(identity: ElementIdentity): ElementIdentity = identity.copy(reliable = true)

    private fun of(
        entry: Int,
        tree: List<TreeEntry>,
        paired: BooleanArray,
        read: Read,
    ): StaleReason {
        val node = tree[entry]
        val identity = node.identity
        if (!identity.reliable) return StaleReason.UNRELIABLE
        val parent = node.parent?.takeUnless { paired[it] }
        val alike = read.alike[alikeKey(identity)].orEmpty()
        val alikeBefore = read.before[identity to node.parent] ?: 0
        return contextReason(identity, read) ?: when {
            parent != null -> of(parent, tree, paired, read)
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
