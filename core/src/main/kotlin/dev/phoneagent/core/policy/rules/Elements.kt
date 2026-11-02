package dev.phoneagent.core.policy.rules

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement

/** A piece of visible text with its position: an element's name or an OCR line. */
internal data class Piece(
    val text: String,
    val bounds: Bounds,
)

/** Kinds and text of elements within one snapshot. */
internal object Elements {
    fun byRef(
        screen: ScreenSnapshot,
        ref: ElementRef,
    ): UiElement? = screen.elements.firstOrNull { it.ref == ref }

    fun editable(element: UiElement): Boolean =
        element.role == ElementRole.INPUT || ElementState.EDITABLE in element.states

    /** Takes a click: clickable and not disabled. */
    fun clickable(element: UiElement): Boolean =
        ElementState.CLICKABLE in element.states && ElementState.DISABLED !in element.states

    /** What the element says: its name, else the names in its own content ([content]) joined, else "". */
    fun label(
        element: UiElement,
        screen: ScreenSnapshot,
    ): String = element.name?.takeIf(String::isNotBlank) ?: content(element, screen).joinToString(" ")

    /**
     * The names inside [element] at any depth, in reading order: its descendants, except other
     * controls (and what is inside them) that do not cover its center, since activating [element]
     * does not activate those. Each element is visited once, so a cyclic tree ends.
     */
    fun content(
        element: UiElement,
        screen: ScreenSnapshot,
    ): List<String> {
        val children = screen.elements.groupBy { it.parent }
        val center = Geometry.center(element.bounds)
        val inside = mutableSetOf(element.ref)
        val queue = ArrayDeque(listOf(element.ref))
        while (queue.isNotEmpty()) {
            for (child in children[queue.removeFirst()].orEmpty()) {
                val separate = clickable(child) && !Geometry.contains(child.bounds, center.x, center.y)
                if (!separate && inside.add(child.ref)) queue.addLast(child.ref)
            }
        }
        return screen.elements
            .filter { it.ref in inside && it.ref != element.ref }
            .mapNotNull { it.name?.takeIf(String::isNotBlank) }
    }

    /** Visible text on [screen]: element names and OCR lines. */
    fun pieces(screen: ScreenSnapshot): List<Piece> =
        screen.elements.mapNotNull { e -> e.name?.takeIf(String::isNotBlank)?.let { Piece(it, e.bounds) } } +
            screen.ocr.orEmpty().map { Piece(it.text, it.bounds) }

    /**
     * Texts around [target]: names of the other elements in its window (all elements when the
     * window is unknown) and the OCR lines.
     */
    fun context(
        target: UiElement,
        screen: ScreenSnapshot,
    ): List<String> =
        screen.elements
            .filter { it.ref != target.ref && (target.window == null || it.window == target.window) }
            .mapNotNull { it.name?.takeIf(String::isNotBlank) } + screen.ocr.orEmpty().map { it.text }
}

/** Which element a touch reaches, as the actuator (WP07) delivers taps. */
internal object Tree {
    /** How many ancestors a tap by ref climbs to find the control that takes the click (as the actuator does). */
    private const val MAX_ANCESTORS = 8

    private val BY_AREA: Comparator<UiElement> = compareBy { Geometry.area(it.bounds) }

    /**
     * The control a tap by ref on [element] activates: [element] when it takes the click, else the
     * nearest of [MAX_ANCESTORS] ancestors that does and covers [element]'s center; `null` when none
     * does (the tap is then a gesture at the center).
     */
    fun clickTarget(
        element: UiElement,
        screen: ScreenSnapshot,
    ): UiElement? {
        val center = Geometry.center(element.bounds)
        return (sequenceOf(element) + ancestors(element, screen))
            .take(MAX_ANCESTORS + 1)
            .firstOrNull { Elements.clickable(it) && Geometry.contains(it.bounds, center.x, center.y) }
    }

    /**
     * What a touch at ([x], [y]) may land on, smallest first: the clickable elements there that
     * contain no other clickable element there (the innermost takes the touch); which of several
     * unrelated ones is on top is not known. Without any, the smallest element there.
     */
    fun under(
        screen: ScreenSnapshot,
        x: Int,
        y: Int,
    ): List<UiElement> {
        val hit = screen.elements.filter { Geometry.contains(it.bounds, x, y) }
        val controls = hit.filter(Elements::clickable)
        val innermost = controls.filter { c -> controls.none { it !== c && within(it, c, screen) } }
        return innermost.sortedWith(BY_AREA).ifEmpty { listOfNotNull(hit.minWithOrNull(BY_AREA)) }
    }

    /** Whether some ancestor of [element] takes clicks: it is part of a control. */
    fun inControl(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean = ancestors(element, screen).any(Elements::clickable)

    /** [element] lies inside [ancestor] in the tree. */
    private fun within(
        element: UiElement,
        ancestor: UiElement,
        screen: ScreenSnapshot,
    ): Boolean = ancestors(element, screen).any { it === ancestor }

    /** [element]'s ancestors, nearest first; a cyclic tree ends after as many steps as there are elements. */
    private fun ancestors(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Sequence<UiElement> =
        generateSequence(parentOf(element, screen)) { parentOf(it, screen) }.take(screen.elements.size)

    private fun parentOf(
        element: UiElement,
        screen: ScreenSnapshot,
    ): UiElement? = element.parent?.let { Elements.byRef(screen, it) }
}
