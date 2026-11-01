package dev.phoneagent.app.adapters.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.UiElement

/**
 * Keeps refs stable from one screen to the next, so the model can keep using `@e12` and the diff of
 * two screens shows only what changed (WP06 counts a changed ref as a change).
 *
 * An element of the fresh read takes the ref of an element of the previous screen with the same
 * window, role, name and view id: first among those with the same bounds too (so identical rows
 * that did not move keep theirs), then in reading order (so moved and scrolled elements keep
 * theirs). Every other element gets a new number above all numbers handed out before, so a ref
 * never comes back for a different element after it disappeared. Parent refs follow.
 */
internal object RefCarrier {
    /** The renumbered [elements]; [lastUsed] is the highest number handed out so far. */
    class Carried(
        val elements: List<UiElement>,
        val lastUsed: Int,
    )

    private const val PREFIX = "e"

    private data class Loose(
        val window: Int?,
        val role: ElementRole,
        val name: String?,
        val resourceId: String?,
    )

    private data class Exact(
        val loose: Loose,
        val bounds: Bounds,
    )

    /**
     * Renumbers [fresh] (numbered `e1…` by the reader) against [previous], the elements of the
     * last screen of this port (`null` for the first). [lastUsed] is the highest number handed out
     * before.
     */
    fun carry(
        previous: List<UiElement>?,
        fresh: List<UiElement>,
        lastUsed: Int,
    ): Carried {
        val old = previous.orEmpty()
        val taken = HashSet<ElementRef>()
        val assigned = arrayOfNulls<ElementRef>(fresh.size)
        matchPass(old, fresh, assigned, taken) { Exact(looseOf(it), it.bounds) }
        matchPass(old, fresh, assigned, taken, ::looseOf)
        var next = maxOf(lastUsed, old.maxOfOrNull(::numberOf) ?: 0)
        val final = fresh.indices.map { i -> assigned[i] ?: ElementRef("$PREFIX${++next}") }
        val byProvisional = fresh.indices.associate { fresh[it].ref to final[it] }
        val elements =
            fresh.mapIndexed { i, element ->
                element.copy(ref = final[i], parent = element.parent?.let(byProvisional::get))
            }
        return Carried(elements, next)
    }

    /** Gives each unassigned fresh element the first untaken old ref with the same [key]. */
    private fun <K> matchPass(
        old: List<UiElement>,
        fresh: List<UiElement>,
        assigned: Array<ElementRef?>,
        taken: MutableSet<ElementRef>,
        key: (UiElement) -> K,
    ) {
        val candidates = old.filter { it.ref !in taken }.groupBy(key) { it.ref }.mapValues { ArrayDeque(it.value) }
        fresh.forEachIndexed { i, element ->
            if (assigned[i] == null) {
                candidates[key(element)]?.removeFirstOrNull()?.let { ref ->
                    assigned[i] = ref
                    taken += ref
                }
            }
        }
    }

    private fun looseOf(element: UiElement) = Loose(element.window, element.role, element.name, element.resourceId)

    private fun numberOf(element: UiElement): Int =
        element.ref.value
            .removePrefix(PREFIX)
            .toIntOrNull() ?: 0
}
