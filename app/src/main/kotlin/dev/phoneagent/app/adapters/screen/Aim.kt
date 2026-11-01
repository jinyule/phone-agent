package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.Target

/** What an action is aimed at once its target was resolved against a fresh read. */
internal sealed interface Aim {
    /** An element of the fresh read. */
    class AtElement(
        val element: ReadElement,
    ) : Aim

    /** A pixel; [windowPackage] is the package of the top-most window under it, or `null`. */
    class AtPixel(
        val pixel: PixelPoint,
        val windowPackage: String?,
    ) : Aim

    /** No explicit target: the focused field, the main list, the whole display. */
    data object Anywhere : Aim
}

/** Result of [Aims.resolve]. */
internal sealed interface Resolution {
    /** The target is usable. */
    class Aimed(
        val aim: Aim,
    ) : Resolution

    /** The planned element no longer matches: the model must observe again. */
    data object Stale : Resolution

    /** The target is invalid; [reason] is for the model, in English. */
    data class Invalid(
        val reason: String,
    ) : Resolution
}

/** Resolves targets and finds fields and containers in a fresh read. */
internal object Aims {
    private const val MAX_ANCESTORS = 8
    private const val MAX_DESCENDANTS = 300

    /** Resolves [target] planned on snapshot [on] ([planned] when it is still remembered) against [read]. */
    fun resolve(
        target: Target?,
        on: SnapshotId?,
        planned: ScreenSnapshot?,
        read: TreeRead,
        geometry: DisplayGeometry,
    ): Resolution =
        when (target) {
            null -> {
                Resolution.Aimed(Aim.Anywhere)
            }

            is Target.Point -> {
                val pixel = geometry.toPixel(target)
                Resolution.Aimed(Aim.AtPixel(pixel, windowPackageAt(read, pixel)))
            }

            is Target.Ref -> {
                resolveRef(target, on, planned, read)
            }
        }

    private fun resolveRef(
        target: Target.Ref,
        on: SnapshotId?,
        planned: ScreenSnapshot?,
        read: TreeRead,
    ): Resolution =
        when {
            on == null -> {
                Resolution.Invalid("element @${target.ref} needs the screen id it came from")
            }

            planned == null -> {
                Resolution.Stale
            }

            else -> {
                when (val match = RefMatcher.match(planned, target.ref, read.elements)) {
                    is RefMatcher.Match.Found -> Resolution.Aimed(Aim.AtElement(match.element))
                    RefMatcher.Match.Stale -> Resolution.Stale
                    RefMatcher.Match.UnknownRef -> Resolution.Invalid("screen $on has no element @${target.ref}")
                }
            }
        }

    /** Package of the top-most window containing [pixel]. */
    fun windowPackageAt(
        read: TreeRead,
        pixel: PixelPoint,
    ): String? =
        read.windowBounds
            .firstOrNull { (_, bounds) -> bounds.contains(pixel.x.toInt(), pixel.y.toInt()) }
            ?.let { (id, _) -> read.windowPackages[id] }

    /** [node] itself when editable, else its first editable descendant. */
    fun editableIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_DESCENDANTS) {
            val next = queue.removeFirst()
            if (next.isEditable) return next
            for (i in 0 until next.childCount) next.getChild(i)?.let(queue::add)
        }
        return null
    }

    /** The smallest text field of [read] containing [pixel]. */
    fun editableAt(
        read: TreeRead,
        pixel: PixelPoint,
    ): ReadElement? =
        read.elements
            .filter { it.element.role == ElementRole.INPUT && it.pixels.contains(pixel.x.toInt(), pixel.y.toInt()) }
            .minByOrNull { it.pixels.width().toLong() * it.pixels.height() }

    /** The editable node holding input focus in [windows], looking inside focused containers (WebViews). */
    fun focusedEditable(windows: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? =
        ScreenReader
            .contentWindows(windows)
            .asSequence()
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstNotNullOfOrNull { focused ->
                if (focused.isEditable) focused else editableIn(focused)?.takeIf { it.isFocused }
            }

    /** The nearest scrollable node at or above [element], with its bounds. */
    fun scrollableAround(
        element: ReadElement,
        geometry: DisplayGeometry,
    ): NodeTarget? {
        val node =
            generateSequence(element.node) { it.parent }
                .take(MAX_ANCESTORS + 1)
                .firstOrNull { it.isScrollable }
        return node?.let { found ->
            geometry.clip(Rect().also(found::getBoundsInScreen))?.let { NodeTarget(found, it) }
        }
    }

    /** The smallest scrollable element containing [pixel]. */
    fun scrollableAt(
        read: TreeRead,
        pixel: PixelPoint,
    ): ReadElement? =
        read.elements
            .filter { it.node.isScrollable && it.pixels.contains(pixel.x.toInt(), pixel.y.toInt()) }
            .minByOrNull { it.pixels.width().toLong() * it.pixels.height() }

    /** The largest scrollable element outside [ownPackage]'s windows: the screen's main list. */
    fun mainScrollable(
        read: TreeRead,
        ownPackage: String,
    ): ReadElement? =
        read.elements
            .filter { it.node.isScrollable && it.windowPackage != ownPackage }
            .maxByOrNull { it.pixels.width().toLong() * it.pixels.height() }
}
