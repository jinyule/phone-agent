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
        /** The planned element is [element] in the fresh read. */
        class Found(
            val element: ReadElement,
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
            element.element.bounds == entry.bounds -> Match.Found(element)
            else -> Match.Stale(StaleReason.MOVED)
        }
    }
}

/** Explains why [RefCarrier.pair] paired a planned element with nothing; it decides nothing itself. */
internal object StaleReasons {
    /** Why [entry] of [tree] is not among the [paired] refs of [fresh]. */
    fun of(
        entry: TreeEntry,
        tree: List<TreeEntry>,
        fresh: List<ReadElement>,
        paired: Set<ElementRef?>,
    ): StaleReason {
        val identity = entry.identity
        val context = identity.context
        val sameScope =
            fresh.filter {
                it.identity.window == identity.window &&
                    it.identity.context?.scope == context?.scope
            }
        val parent = entry.parent?.takeIf { it !in paired }?.let { ref -> tree.first { it.ref == ref } }
        val alike = fresh.filter { it.identity.copy(reliable = true) == identity.copy(reliable = true) }
        val alikeBefore = tree.count { it.identity == identity && it.parent == entry.parent }
        return when {
            !identity.reliable -> StaleReason.UNRELIABLE

            context?.scope == IdentityContext.Scope.WINDOW &&
                sameScope.any {
                    !it.identity.reliable
                }
            -> StaleReason.UNRELIABLE

            sameScope.none { it.identity.context == context } -> changed(context)

            parent != null -> of(parent, tree, fresh, paired)

            alike.any { !it.identity.reliable } -> StaleReason.UNRELIABLE

            alike.size > 1 || alikeBefore > 1 -> StaleReason.AMBIGUOUS

            else -> StaleReason.NOT_FOUND
        }
    }

    private fun changed(context: IdentityContext?): StaleReason =
        if (context?.scope == IdentityContext.Scope.ROW) StaleReason.ROW_CHANGED else StaleReason.WINDOW_CHANGED
}
