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

/** Geometry and text of elements within one snapshot. */
internal object Elements {
    /** How deep an unnamed control's text children are searched (the formatter absorbs them too). */
    private const val CHILD_DEPTH = 3

    private val BY_AREA: Comparator<UiElement> = compareBy { Geometry.area(it.bounds) }

    fun byRef(
        screen: ScreenSnapshot,
        ref: ElementRef,
    ): UiElement? = screen.elements.firstOrNull { it.ref == ref }

    fun editable(element: UiElement): Boolean =
        element.role == ElementRole.INPUT || ElementState.EDITABLE in element.states

    /** What the element says: its name, else the names of its text children, else "". */
    fun label(
        element: UiElement,
        screen: ScreenSnapshot,
    ): String = element.name?.takeIf(String::isNotBlank) ?: childText(element.ref, screen, CHILD_DEPTH)

    private fun childText(
        ref: ElementRef,
        screen: ScreenSnapshot,
        depth: Int,
    ): String =
        screen.elements
            .filter { it.parent == ref }
            .joinToString(" ") { child ->
                child.name?.takeIf(String::isNotBlank) ?: if (depth > 1) childText(child.ref, screen, depth - 1) else ""
            }.trim()

    /** The element the user would hit at ([x], [y]): the smallest clickable one there, else the smallest one. */
    fun at(
        screen: ScreenSnapshot,
        x: Int,
        y: Int,
    ): UiElement? {
        val under = screen.elements.filter { Geometry.contains(it.bounds, x, y) }
        return under.filter { ElementState.CLICKABLE in it.states }.minWithOrNull(BY_AREA)
            ?: under.minWithOrNull(BY_AREA)
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
