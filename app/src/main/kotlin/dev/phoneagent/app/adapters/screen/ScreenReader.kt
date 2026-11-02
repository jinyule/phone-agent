package dev.phoneagent.app.adapters.screen

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.UiElement

/**
 * One visible node of a [TreeRead] with the live node it came from.
 *
 * @property element the node as the model would see it; [UiElement.ref] and [UiElement.parent]
 *   are provisional (`e<index + 1>` in this read) until [TreeRead.project] gives the port's refs.
 * @property node the node to act on; valid until the window changes.
 * @property pixels the node's bounds in display pixels, clipped to the display.
 * @property windowPackage package of the node's window, or `null` when unknown.
 * @property parent index in [TreeRead.elements] of the nearest node above it, or `null` at the top.
 * @property depth how many nodes of the read are above it.
 * @property brief whether [SnapshotDetail.BRIEF] shows it.
 * @property identity who it is, for carrying and re-matching refs.
 */
internal class ReadElement(
    val element: UiElement,
    val node: AccessibilityNodeInfo,
    val pixels: Rect,
    val windowPackage: String?,
    val parent: Int?,
    val depth: Int,
    val brief: Boolean,
    val identity: ElementIdentity,
) {
    /** The node and its bounds, for the actuator. */
    val target: NodeTarget get() = NodeTarget(node, pixels)
}

/**
 * The window trees of one display at one moment: every visible node, whatever the detail; a
 * snapshot shows a projection of it ([project]).
 *
 * @property elements visible nodes in reading order: top-most window first, then pre-order within a window.
 * @property pkg package of the top-most application window, or `null` when there is none.
 * @property keyboard a soft keyboard window is on the display.
 * @property windowPackages package of each content window by window id (`null` when its root is unreadable).
 * @property windowBounds bounds in pixels of each content window by id, top-most first.
 * @property frontPackage package of the window keys act on: the focused content window, else the
 *   top-most one (`null` when unknown).
 * @property systemBars ids of the status and navigation bar windows, which the read leaves out.
 */
internal class TreeRead(
    val elements: List<ReadElement>,
    val pkg: String?,
    val keyboard: Boolean,
    val windowPackages: Map<Int, String?>,
    val windowBounds: List<Pair<Int, Rect>>,
    val frontPackage: String?,
    val systemBars: Set<Int>,
) {
    /**
     * The elements [detail] shows, with [refs] (one per element of the read) as their refs; each
     * parent is the nearest shown element above.
     */
    fun project(
        refs: List<ElementRef>,
        detail: SnapshotDetail,
    ): List<UiElement> {
        val shown = BooleanArray(elements.size) { detail == SnapshotDetail.FULL || elements[it].brief }
        return elements.indices.filter { shown[it] }.map { i ->
            val above = generateSequence(elements[i].parent) { elements[it].parent }.firstOrNull { shown[it] }
            elements[i].element.copy(ref = refs[i], parent = above?.let(refs::get))
        }
    }

    /** [project] with the provisional refs of this read. */
    fun provisional(detail: SnapshotDetail): List<UiElement> = project(elements.map { it.element.ref }, detail)
}

/**
 * Turns accessibility windows into [UiElement]s (phase1 §3.3). Stateless; every call walks the
 * live trees again and keeps every visible node; refs are given by [RefCarrier].
 *
 * Shown in [SnapshotDetail.BRIEF]: visible nodes with a name or an interaction (clickable,
 * editable, scrollable, checkable, range), and unnamed leaf views covering at least
 * [OPAQUE_LEAF_SHARE] of the display (canvas, video, WebView without accessibility) so tree
 * quality can see them. [SnapshotDetail.FULL] shows every visible node.
 *
 * The status and navigation bars are left out ([isSystemBar]): their clock and icons change on
 * their own and would make every screen look changed. Other system windows (dialogs, heads-up
 * notifications, the notification shade, pickers) are kept.
 *
 * Window filtering, ordering and the depth limit are adapted from Mika's ScreenReader (Apache-2.0,
 * see NOTICE). Modified by PhoneAgent contributors.
 */
internal object ScreenReader {
    /** Deepest node visited; deeper subtrees are cut (runaway custom views). */
    const val MAX_DEPTH: Int = 80

    /** Most nodes visited per read; the rest of the tree is cut. */
    const val MAX_NODES: Int = 4000

    /** Share of the display from which an unnamed leaf view is shown in [SnapshotDetail.BRIEF]. */
    const val OPAQUE_LEAF_SHARE: Float = 0.1f

    /** Thickest a system bar may be, as a share of the display's height (or width, for a side bar). */
    const val BAR_SHARE: Float = 0.1f

    private const val MIN_SIDE_PX = 2

    // TYPE_MAGNIFICATION_OVERLAY (API 33) is inlined; below 33 no window has that type, so it never matches.
    @SuppressLint("InlinedApi")
    private val SKIPPED_WINDOW_TYPES =
        setOf(
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
        )

    /** Package of the top-most application window on the display, or `null` when there is none. */
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? =
        contentWindows(windows)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /** Content windows (no keyboard, no overlays), top-most first. */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_WINDOW_TYPES }.sortedByDescending { it.layer }

    /**
     * Whether [window] is a status or navigation bar: a system window spanning the display along
     * one edge, at most [BAR_SHARE] thick.
     */
    fun isSystemBar(
        window: AccessibilityWindowInfo,
        geometry: DisplayGeometry,
    ): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
        val r = Rect().also(window::getBoundsInScreen)
        val (width, height) = geometry.width to geometry.height
        val acrossTopOrBottom =
            r.left <= 0 && r.right >= width && (r.top <= 0 || r.bottom >= height) && r.height() <= height * BAR_SHARE
        val alongASide =
            r.top <= 0 && r.bottom >= height && (r.left <= 0 || r.right >= width) && r.width() <= width * BAR_SHARE
        return acrossTopOrBottom || alongASide
    }

    /** Reads [windows] of one display with size [geometry]. */
    fun read(
        windows: List<AccessibilityWindowInfo>,
        geometry: DisplayGeometry,
    ): TreeRead {
        val (bars, content) = contentWindows(windows).partition { isSystemBar(it, geometry) }
        val walker = Walker(geometry)
        val packages = LinkedHashMap<Int, String?>()
        val windowBounds = ArrayList<Pair<Int, Rect>>()
        var pkg: String? = null
        for (window in content) {
            val root = window.root
            val windowPackage = root?.packageName?.toString()
            packages[window.id] = windowPackage
            windowBounds += window.id to Rect().also(window::getBoundsInScreen)
            if (pkg == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) pkg = windowPackage
            if (root != null) walker.visit(root, Walker.Place(window.id, windowPackage, parent = null, depth = 0))
        }
        return TreeRead(
            elements = walker.finish(),
            pkg = pkg,
            keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            windowPackages = packages,
            windowBounds = windowBounds,
            frontPackage = (content.firstOrNull { it.isFocused } ?: content.firstOrNull())?.let { packages[it.id] },
            systemBars = bars.map { it.id }.toSet(),
        )
    }

    /** One read's mutable state: the node budget and what was kept so far. */
    private class Walker(
        private val geometry: DisplayGeometry,
    ) {
        /** Where a node sits: its window, the index of the nearest kept node above, its depth in the read. */
        class Place(
            val windowId: Int,
            val windowPackage: String?,
            val parent: Int?,
            val depth: Int,
        )

        /** A kept node before identities are known. */
        private class Kept(
            val element: UiElement,
            val node: AccessibilityNodeInfo,
            val pixels: Rect,
            val place: Place,
            val brief: Boolean,
            val facts: Identities.Facts,
        )

        private val kept = ArrayList<Kept>()
        private var visited = 0
        private var treeDepth = 0
        private val seen = HashSet<List<Any?>>()

        fun visit(
            node: AccessibilityNodeInfo,
            place: Place,
        ) {
            if (treeDepth > MAX_DEPTH || visited >= MAX_NODES) return
            visited++
            val index = if (node.isVisibleToUser) keep(node, place) else null
            val childPlace =
                if (index == null) place else Place(place.windowId, place.windowPackage, index, place.depth + 1)
            treeDepth++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childPlace) }
            }
            treeDepth--
        }

        /** The kept nodes with their identities. */
        fun finish(): List<ReadElement> {
            val identities = Identities.of(kept.map { it.facts })
            return kept.mapIndexed { i, k ->
                ReadElement(
                    element = k.element,
                    node = k.node,
                    pixels = k.pixels,
                    windowPackage = k.place.windowPackage,
                    parent = k.place.parent,
                    depth = k.place.depth,
                    brief = k.brief,
                    identity = identities[i],
                )
            }
        }

        private fun keep(
            node: AccessibilityNodeInfo,
            place: Place,
        ): Int? {
            val pixels =
                geometry.clip(Rect().also(node::getBoundsInScreen))?.takeIf {
                    it.width() >= MIN_SIDE_PX &&
                        it.height() >= MIN_SIDE_PX
                }
            val bounds = pixels?.let(geometry::normalize)
            val role = NodeFacts.roleOf(node)
            val name = NodeText.nameOf(node, role)
            val value = NodeText.valueOf(node, role)
            val interactive = NodeFacts.interactive(node)
            val unique =
                pixels != null &&
                    bounds != null &&
                    // Nested views often repeat the same text in the same box; keep the outermost.
                    seen.add(listOf(place.windowId, role, name, value, interactive, bounds))
            if (!unique) return null
            val index = kept.size
            val element =
                UiElement(
                    ref = provisional(index),
                    role = role,
                    name = name,
                    value = value,
                    hint = NodeText.hintOf(node),
                    bounds = bounds,
                    states = NodeFacts.statesOf(node),
                    secret = node.isPassword,
                    parent = place.parent?.let(::provisional),
                    window = place.windowId,
                    resourceId = node.viewIdResourceName?.substringAfter(":id/")?.ifBlank { null },
                )
            val facts =
                Identities.Facts(
                    window = place.windowId,
                    role = role,
                    resourceId = element.resourceId,
                    name = name,
                    label = NodeText.ownLabel(node),
                    list = role == ElementRole.LIST,
                    bounds = bounds,
                    parent = place.parent,
                )
            val brief = name != null || interactive || opaqueLeaf(node, pixels)
            kept += Kept(element, node, pixels, place, brief, facts)
            return index
        }

        private fun provisional(index: Int) = ElementRef("e${index + 1}")

        private fun opaqueLeaf(
            node: AccessibilityNodeInfo,
            pixels: Rect,
        ): Boolean {
            val share = pixels.width().toFloat() * pixels.height() / (geometry.width.toFloat() * geometry.height)
            return node.childCount == 0 && share >= OPAQUE_LEAF_SHARE
        }
    }
}
