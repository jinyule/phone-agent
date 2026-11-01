package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement

/**
 * Computes [dev.phoneagent.core.ports.ScreenSnapshot.quality], which decides whether a frame is
 * attached for the model (phase1 D6). Pure.
 *
 * - [TreeQuality.EMPTY]: no element carries any text (name, value or hint).
 * - [TreeQuality.SPARSE]: some text, but the tree visibly misses parts of the screen: too few named
 *   elements, most controls unnamed, or a large unnamed area (canvas, map, WebView without
 *   accessibility) with few named elements inside.
 * - [TreeQuality.GOOD]: otherwise.
 *
 * An element without text of its own is still named when it absorbs child texts the way the screen
 * format does ([PageLayout]): a row with a name and a time under it reads as a named row. So
 * [format] must be the formatter's config.
 *
 * Judged on the tree alone, before OCR: the verdict decides whether OCR runs and a frame is attached.
 */
public class TreeQualityAssessor(
    private val config: TreeQualityConfig = TreeQualityConfig(),
    private val format: ScreenFormatConfig = ScreenFormatConfig(),
) {
    /** Quality of a tree made of [elements]. */
    public fun assess(elements: List<UiElement>): TreeQuality {
        val named = elements.filter { it.hasText() }
        return when {
            named.isEmpty() -> TreeQuality.EMPTY
            looksIncomplete(elements, named) -> TreeQuality.SPARSE
            else -> TreeQuality.GOOD
        }
    }

    private fun looksIncomplete(
        elements: List<UiElement>,
        named: List<UiElement>,
    ): Boolean {
        val layout = PageLayout(elements, format)
        val isNamed = { element: UiElement -> element.hasText() || layout.absorbed.containsKey(element.ref) }
        val controls = elements.filter { it.isActionable() }
        val unnamedControls = controls.count { !isNamed(it) }
        val mostlyUnnamed =
            controls.size >= config.minControlsForShare &&
                unnamedControls >= controls.size * config.unnamedControlShare
        return named.size < config.minNamedElements ||
            mostlyUnnamed ||
            elements.any { !isNamed(it) && isBlindArea(it, named) }
    }

    private fun isBlindArea(
        element: UiElement,
        named: List<UiElement>,
    ): Boolean {
        val unnamedSurface = element.role in SURFACE_ROLES
        return unnamedSurface &&
            element.bounds.areaShare() >= config.blindAreaShare &&
            named.count { it !== element && element.bounds.containsCenterOf(it.bounds) } < config.namedInsideBlindArea
    }
}

/**
 * Thresholds of [TreeQualityAssessor]; validated on construction.
 *
 * @property minNamedElements fewer named elements than this is [TreeQuality.SPARSE].
 * @property minControlsForShare [unnamedControlShare] applies only with at least this many controls.
 * @property unnamedControlShare this share (0–1] of controls without text is [TreeQuality.SPARSE].
 * @property blindAreaShare an unnamed image/other/container covering this share (0–1] of the screen is blind…
 * @property namedInsideBlindArea …unless at least this many named elements are centered inside it.
 */
public data class TreeQualityConfig(
    val minNamedElements: Int = 3,
    val minControlsForShare: Int = 4,
    val unnamedControlShare: Double = TWO_THIRDS,
    val blindAreaShare: Double = 0.2,
    val namedInsideBlindArea: Int = 3,
) {
    init {
        require(minNamedElements >= 1) { "minNamedElements must be >= 1, got $minNamedElements" }
        require(minControlsForShare >= 1) { "minControlsForShare must be >= 1, got $minControlsForShare" }
        require(unnamedControlShare > 0.0 && unnamedControlShare <= 1.0) { "unnamedControlShare must be in (0, 1]" }
        require(blindAreaShare > 0.0 && blindAreaShare <= 1.0) { "blindAreaShare must be in (0, 1]" }
        require(namedInsideBlindArea >= 1) { "namedInsideBlindArea must be >= 1, got $namedInsideBlindArea" }
    }

    private companion object {
        const val TWO_THIRDS = 2.0 / 3.0
    }
}

private fun Bounds.containsCenterOf(other: Bounds): Boolean {
    val center = other.center()
    return center.x in left..right && center.y in top..bottom
}

private val SURFACE_ROLES = setOf(ElementRole.IMAGE, ElementRole.OTHER, ElementRole.CONTAINER)
