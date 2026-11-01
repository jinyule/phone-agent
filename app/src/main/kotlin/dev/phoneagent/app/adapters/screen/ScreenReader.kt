package dev.phoneagent.app.adapters.screen

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.UiElement

/**
 * One element of a [TreeRead] with the live node it came from.
 *
 * @property element the model-facing element.
 * @property node the node to act on; valid until the window changes.
 * @property pixels the element's bounds in display pixels, clipped to the display.
 * @property windowPackage package of the element's window, or `null` when unknown.
 */
internal class ReadElement(
    val element: UiElement,
    val node: AccessibilityNodeInfo,
    val pixels: Rect,
    val windowPackage: String?,
) {
    /** The node and its bounds, for the actuator. */
    val target: NodeTarget get() = NodeTarget(node, pixels)
}

/**
 * The window trees of one display at one moment.
 *
 * @property elements elements in reading order: top-most window first, then pre-order within a window.
 * @property pkg package of the top-most application window, or `null` when there is none.
 * @property keyboard a soft keyboard window is on the display.
 * @property windowPackages package of each content window by window id (`null` when its root is unreadable).
 * @property windowBounds bounds in pixels of each content window by id, top-most first.
 */
internal class TreeRead(
    val elements: List<ReadElement>,
    val pkg: String?,
    val keyboard: Boolean,
    val windowPackages: Map<Int, String?>,
    val windowBounds: List<Pair<Int, Rect>>,
)

/**
 * Turns accessibility windows into [UiElement]s (phase1 §3.3). Stateless; every call walks the
 * live trees again. Elements are numbered `e1…` in reading order, so refs are stable within one read.
 *
 * Kept in [SnapshotDetail.BRIEF]: visible nodes with a name or an interaction (clickable,
 * editable, scrollable, checkable, range), and unnamed leaf views covering at least
 * [OPAQUE_LEAF_SHARE] of the display (canvas, video, WebView without accessibility) so tree
 * quality can see them. [SnapshotDetail.FULL] keeps every visible node.
 *
 * Window filtering, ordering and the depth limit are adapted from Mika's ScreenReader (Apache-2.0,
 * see NOTICE). Modified by PhoneAgent contributors.
 */
internal object ScreenReader {
    /** Deepest node visited; deeper subtrees are cut (runaway custom views). */
    const val MAX_DEPTH: Int = 80

    /** Most nodes visited per read; the rest of the tree is cut. */
    const val MAX_NODES: Int = 4000

    /** Share of the display from which an unnamed leaf view is kept in [SnapshotDetail.BRIEF]. */
    const val OPAQUE_LEAF_SHARE: Float = 0.1f

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

    /** Reads [windows] of one display with size [geometry]. */
    fun read(
        windows: List<AccessibilityWindowInfo>,
        geometry: DisplayGeometry,
        detail: SnapshotDetail,
    ): TreeRead {
        val content = contentWindows(windows)
        val walker = Walker(geometry, detail)
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
            elements = walker.out,
            pkg = pkg,
            keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            windowPackages = packages,
            windowBounds = windowBounds,
        )
    }

    /** One read's mutable state: numbering, the node budget and the output. */
    private class Walker(
        private val geometry: DisplayGeometry,
        private val detail: SnapshotDetail,
    ) {
        /** Where a node sits: its window, the nearest kept ancestor, its depth. */
        class Place(
            val windowId: Int,
            val windowPackage: String?,
            val parent: ElementRef?,
            val depth: Int,
        )

        val out = ArrayList<ReadElement>()
        private var visited = 0
        private val seen = HashSet<List<Any?>>()

        fun visit(
            node: AccessibilityNodeInfo,
            place: Place,
        ) {
            if (place.depth > MAX_DEPTH || visited >= MAX_NODES) return
            visited++
            val ref = if (node.isVisibleToUser) emit(node, place) else null
            val childPlace = Place(place.windowId, place.windowPackage, ref ?: place.parent, place.depth + 1)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childPlace) }
            }
        }

        private fun emit(
            node: AccessibilityNodeInfo,
            place: Place,
        ): ElementRef? {
            val pixels =
                geometry.clip(Rect().also(node::getBoundsInScreen))?.takeIf {
                    it.width() >= MIN_SIDE_PX &&
                        it.height() >= MIN_SIDE_PX
                }
            val bounds = pixels?.let(geometry::normalize)
            val role = NodeFacts.roleOf(node)
            val name = NodeText.nameOf(node, role)
            val value = NodeText.valueOf(node, role)
            val keep =
                pixels != null &&
                    bounds != null &&
                    (
                        name != null || NodeFacts.interactive(node) || detail == SnapshotDetail.FULL ||
                            opaqueLeaf(node, pixels)
                    ) &&
                    // Nested views often repeat the same text in the same box; keep the outermost.
                    seen.add(listOf(place.windowId, role, name, value, bounds))
            if (!keep) return null
            val ref = ElementRef("e${out.size + 1}")
            val element =
                UiElement(
                    ref = ref,
                    role = role,
                    name = name,
                    value = value,
                    hint = NodeText.hintOf(node),
                    bounds = bounds,
                    states = NodeFacts.statesOf(node),
                    secret = node.isPassword,
                    parent = place.parent,
                    window = place.windowId,
                    resourceId = node.viewIdResourceName?.substringAfter(":id/")?.ifBlank { null },
                )
            out += ReadElement(element, node, pixels, place.windowPackage)
            return ref
        }

        private fun opaqueLeaf(
            node: AccessibilityNodeInfo,
            pixels: Rect,
        ): Boolean {
            val share = pixels.width().toFloat() * pixels.height() / (geometry.width.toFloat() * geometry.height)
            return node.childCount == 0 && share >= OPAQUE_LEAF_SHARE
        }
    }
}
