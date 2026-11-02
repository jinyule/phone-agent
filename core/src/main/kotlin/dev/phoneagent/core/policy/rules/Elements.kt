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
    ): UiElement? = ScreenIndex.of(screen).byRef(ref)

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
    ): List<String> = ScreenIndex.of(screen).content(element)

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
            .filter { around(it, target) }
            .mapNotNull { it.name?.takeIf(String::isNotBlank) } + screen.ocr.orEmpty().map { it.text }

    /** [element] is another element in [target]'s window (any element when that window is unknown). */
    fun around(
        element: UiElement,
        target: UiElement,
    ): Boolean = element.ref != target.ref && (target.window == null || element.window == target.window)
}

/** Which element a touch reaches, as the actuator (WP07) delivers taps. */
internal object Tree {
    /** How many ancestors a tap by ref climbs to find the control that takes the click (as the actuator does). */
    private const val MAX_ANCESTORS = 8

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
        return (sequenceOf(element) + ScreenIndex.of(screen).ancestors(element))
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
    ): List<UiElement> = ScreenIndex.of(screen).under(x, y)

    /** Whether some ancestor of [element] takes clicks: it is part of a control. */
    fun inControl(
        element: UiElement,
        screen: ScreenSnapshot,
    ): Boolean = ScreenIndex.of(screen).inControl(element)
}
