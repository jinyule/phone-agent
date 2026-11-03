/*
 * Adapted from Mika, app/src/main/java/com/wangzan101/mika/cap/phone/a11y/ScreenReader.kt
 * (https://github.com/wangzan101/Mika, commit 9cef3750addd20b2af9e3214a8f5e56301670174),
 * Copyright 2026 wangzan101, licensed under the Apache License, Version 2.0 (see NOTICE): the
 * skipped window types, contentWindows, topPackage and the depth limit.
 * Modified by PhoneAgent contributors: rewritten in English, merged into PhoneAgent's own classes.
 */
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
 * Where a node sits in a read: its window, the index of the nearest kept node above, its depth.
 *
 * @property windowId accessibility id of the node's window.
 * @property windowPackage package of the node's window, or `null` when unknown.
 * @property parent index in [TreeRead.elements] of the nearest node above it, or `null` at the top.
 * @property depth how many nodes of the read are above it.
 */
internal class NodePlace(
    val windowId: Int,
    val windowPackage: String?,
    val parent: Int?,
    val depth: Int,
)

/**
 * One visible node of a [TreeRead] with the live node it came from.
 *
 * @property element the node as the model would see it; [UiElement.ref] and [UiElement.parent]
 *   are provisional (`e<index + 1>` in this read) until [TreeRead.project] gives the port's refs.
 * @property target the node to act on (valid until the window changes) and its bounds in display
 *   pixels, clipped to the display.
 * @property place where it sits in the read.
 * @property brief whether [SnapshotDetail.BRIEF] shows it.
 * @property identity who it is, for carrying and re-matching refs.
 */
internal class ReadElement(
    val element: UiElement,
    val target: NodeTarget,
    val place: NodePlace,
    val brief: Boolean,
    val identity: ElementIdentity,
) {
    /** The node to act on. */
    val node: AccessibilityNodeInfo get() = target.node

    /** Its bounds in display pixels. */
    val pixels: Rect get() = target.pixels

    /** Package of its window, or `null` when unknown. */
    val windowPackage: String? get() = place.windowPackage

    /** Index of the nearest node above it, or `null` at the top. */
    val parent: Int? get() = place.parent

    /** How many nodes of the read are above it. */
    val depth: Int get() = place.depth
}

/**
 * What a read found about the windows of the display.
 *
 * @property packages package of each content window by window id (`null` when its root is
 *   unreadable), the status and navigation bars included: the own-UI guard needs every window.
 * @property bounds bounds in pixels of each content window by id, top-most first, bars included.
 * @property top id of the top-most application window with a readable package, or `null`.
 * @property front package of the window keys act on: the focused content window, else the
 *   top-most one (`null` when unknown).
 * @property bars ids of the status and navigation bar windows, which the read leaves out.
 */
internal class WindowFacts(
    val packages: Map<Int, String?>,
    val bounds: List<Pair<Int, Rect>>,
    val top: Int?,
    val front: String?,
    val bars: Set<Int>,
)

/**
 * The window trees of one display at one moment: every visible node, whatever the detail; a
 * snapshot shows a projection of it ([project]).
 *
 * @property elements visible nodes in reading order: top-most window first, then pre-order within a window.
 * @property pkg package of the top-most application window, or `null` when there is none.
 * @property keyboard a soft keyboard window is on the display.
 * @property windows what the read found about the windows.
 */
internal class TreeRead(
    val elements: List<ReadElement>,
    val pkg: String?,
    val keyboard: Boolean,
    val windows: WindowFacts,
) {
    /** Package of each content window by window id, bars included. */
    val windowPackages: Map<Int, String?> get() = windows.packages

    /** Bounds in pixels of each content window by id, top-most first, bars included. */
    val windowBounds: List<Pair<Int, Rect>> get() = windows.bounds

    /** Id of the window [pkg] comes from, or `null` when there is none. */
    val topWindow: Int? get() = windows.top

    /** Package of the window keys act on (`null` when unknown). */
    val frontPackage: String? get() = windows.front

    /** Ids of the status and navigation bar windows, left out of the read. */
    val systemBars: Set<Int> get() = windows.bars

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
 * notifications, the notification shade, pickers) are kept, and so is every window of this app,
 * whatever its shape. Which window lies where is recorded for the bars too ([TreeRead.windowBounds]).
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
    fun topPackage(windows: List<AccessibilityWindowInfo>): String? = topApp(windows)?.second

    /** The top-most application window whose package can be read, with that package. */
    fun topApp(windows: List<AccessibilityWindowInfo>): Pair<AccessibilityWindowInfo, String>? =
        contentWindows(windows)
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { window ->
                window.root
                    ?.packageName
                    ?.toString()
                    ?.let { window to it }
            }

    /** Content windows (no keyboard, no overlays), top-most first. */
    fun contentWindows(windows: List<AccessibilityWindowInfo>): List<AccessibilityWindowInfo> =
        windows.filter { it.type !in SKIPPED_WINDOW_TYPES }.sortedByDescending { it.layer }

    /**
     * Whether [window] is a status or navigation bar: a system window spanning the display along
     * one edge, at most [BAR_SHARE] thick, that is not [ownPackage]'s (a floating control of this
     * app may have that shape).
     */
    fun isSystemBar(
        window: AccessibilityWindowInfo,
        geometry: DisplayGeometry,
        ownPackage: String,
    ): Boolean {
        val system = window.type == AccessibilityWindowInfo.TYPE_SYSTEM
        if (!system || window.root?.packageName?.toString() == ownPackage) return false
        val r = Rect().also(window::getBoundsInScreen)
        val (width, height) = geometry.width to geometry.height
        val acrossTopOrBottom =
            r.left <= 0 && r.right >= width && (r.top <= 0 || r.bottom >= height) && r.height() <= height * BAR_SHARE
        val alongASide =
            r.top <= 0 && r.bottom >= height && (r.left <= 0 || r.right >= width) && r.width() <= width * BAR_SHARE
        return acrossTopOrBottom || alongASide
    }

    /** Reads [windows] of one display with size [geometry]; [ownPackage] is this app's. */
    fun read(
        windows: List<AccessibilityWindowInfo>,
        geometry: DisplayGeometry,
        ownPackage: String,
    ): TreeRead {
        val all = contentWindows(windows)
        val bars = all.filter { isSystemBar(it, geometry, ownPackage) }.map { it.id }.toSet()
        val walker = Walker(geometry)
        val packages = LinkedHashMap<Int, String?>()
        val windowBounds = ArrayList<Pair<Int, Rect>>()
        for (window in all) {
            packages[window.id] = window.root?.packageName?.toString()
            windowBounds += window.id to Rect().also(window::getBoundsInScreen)
        }
        val content = all.filter { it.id !in bars }
        var pkg: String? = null
        var topWindow: Int? = null
        for (window in content) {
            val root = window.root
            val windowPackage = packages[window.id]
            if (pkg == null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && windowPackage != null) {
                pkg = windowPackage
                topWindow = window.id
            }
            if (root != null) {
                walker.visit(root, NodePlace(window.id, windowPackage, parent = null, depth = 0), above = null)
            }
        }
        return TreeRead(
            elements = walker.finish(),
            pkg = pkg,
            keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            windows =
                WindowFacts(
                    packages = packages,
                    bounds = windowBounds,
                    top = topWindow,
                    front = (content.firstOrNull { it.isFocused } ?: content.firstOrNull())?.let { packages[it.id] },
                    bars = bars,
                ),
        )
    }

    /** One read's mutable state: the node budget, what was walked and what was kept so far. */
    private class Walker(
        private val geometry: DisplayGeometry,
    ) {
        /** A kept node before identities are known; [base] is its identity without a context. */
        private class Kept(
            val element: UiElement,
            val target: NodeTarget,
            val place: NodePlace,
            val brief: Boolean,
            val base: ElementIdentity,
        )

        /**
         * A walked node, kept or not (children out of view still tell rows apart); [kept] indexes
         * [Walker.kept]; [clipped] as [Identities.Loss.clipped]; [viewport] for a list.
         */
        private class Walked(
            val label: String?,
            val shape: Identities.Shape,
            val parent: Int?,
            val kept: Int?,
            val clipped: Boolean,
            val viewport: Viewport?,
        ) {
            var cut = false

            /** For a child of a list, as [Identities.Loss.besideGap]. */
            var besideGap = false

            /** For a list, how many of its children were walked so far. */
            var children = 0

            /** For a list, the index in [walked] of its child walked last. */
            var last: Int? = null

            /** For a list, the child after [last] could not be read: the next one walked is beside it. */
            var gap = false
        }

        private val kept = ArrayList<Kept>()
        private val walked = ArrayList<Walked>()

        // Indices in [walked] of the nodes being visited, outermost first.
        private val path = ArrayList<Int>()
        private var visited = 0
        private var treeDepth = 0
        private val seen = HashSet<List<Any?>>()

        /** Visits [node] at [place]; [above] is the index in [walked] of the node above it. */
        fun visit(
            node: AccessibilityNodeInfo,
            place: NodePlace,
            above: Int?,
        ) {
            if (treeDepth > MAX_DEPTH || visited >= MAX_NODES) {
                markCut()
                return
            }
            visited++
            val role = NodeFacts.roleOf(node)
            val index = if (node.isVisibleToUser) keep(node, role, place) else null
            val me = walked.size
            val shape = NodeFacts.shapeOf(node, role)
            val list = above?.let(walked::get)?.takeIf { it.shape.list }
            val clipped =
                list?.viewport?.clips(Rect().also(node::getBoundsInScreen), first = list.children == 0) == true
            val viewport = if (shape.list) NodeFacts.viewportOf(node) else null
            walked += Walked(NodeText.identityLabel(node), shape, above, index, clipped, viewport)
            list?.let {
                it.children++
                walked[me].besideGap = it.gap
                it.gap = false
                it.last = me
            }
            val childPlace =
                if (index == null) place else NodePlace(place.windowId, place.windowPackage, index, place.depth + 1)
            path += me
            treeDepth++
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child == null) markUnread() else visit(child, childPlace, me)
            }
            treeDepth--
            path.removeAt(path.lastIndex)
        }

        /**
         * A node was not walked: the nodes above it are incomplete up to the nearest list, which
         * keeps the rest of its rows; a list whose own child was not walked lost rows.
         */
        private fun markCut() {
            for (i in path.indices.reversed()) {
                val node = walked[path[i]]
                val list = node.shape.list
                if (!list || i == path.lastIndex) node.cut = true
                if (list) return
            }
        }

        /**
         * A child of the node being visited could not be read (`getChild` answered `null`): as
         * [markCut] for a node that is not a list; a list keeps its rows but for those of its
         * children walked right before and right after the one it lost, which it may belong with:
         * those children are [Walked.besideGap], whether they are lists or not.
         */
        private fun markUnread() {
            val node = walked[path.last()]
            if (!node.shape.list) return markCut()
            node.last?.let { walked[it].besideGap = true }
            node.gap = true
        }

        /** The kept nodes with their identities. */
        fun finish(): List<ReadElement> {
            val identities =
                Identities.of(
                    walked.map {
                        val base = it.kept?.let { k -> kept[k].base }
                        Identities.Facts(
                            base,
                            it.label,
                            it.shape,
                            it.parent,
                            Identities.Loss(it.cut, it.clipped, it.besideGap),
                        )
                    },
                )
            val ofKept = arrayOfNulls<ElementIdentity>(kept.size)
            walked.forEachIndexed { i, node -> node.kept?.let { ofKept[it] = identities[i] } }
            return kept.mapIndexed {
                i,
                k,
                ->
                ReadElement(k.element, k.target, k.place, k.brief, checkNotNull(ofKept[i]))
            }
        }

        private fun keep(
            node: AccessibilityNodeInfo,
            role: ElementRole,
            place: NodePlace,
        ): Int? {
            val pixels =
                geometry.clip(Rect().also(node::getBoundsInScreen))?.takeIf {
                    it.width() >= MIN_SIDE_PX &&
                        it.height() >= MIN_SIDE_PX
                }
            val bounds = pixels?.let(geometry::normalize)
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
            val base =
                ElementIdentity(
                    place.windowId,
                    role,
                    element.resourceId,
                    NodeText.identityNameOf(node, role),
                    context = null,
                )
            val brief = name != null || interactive || opaqueLeaf(node, pixels)
            kept += Kept(element, NodeTarget(node, pixels), place, brief, base)
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
