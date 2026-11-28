package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.robolectric.Shadows.shadowOf
import java.util.IdentityHashMap

/** Display used by the fixtures: 1000×2000 px, so pixel ↔ normalized maths stays readable. */
internal val GEOMETRY = DisplayGeometry(1000, 2000)

internal const val APP = "com.example.app"
internal const val OWN = "dev.phoneagent"

/**
 * Actions performed on a fixture node and on every copy of it. Robolectric hands out copies from
 * `getChild`, `getParent` and `AccessibilityWindowInfo.getRoot`; copies share the action listener,
 * so the log sees actions on all of them.
 */
internal class NodeLog {
    val actions = ArrayList<Pair<Int, Bundle?>>()

    /** Decides each action's result; accepts everything by default. */
    var answer: (action: Int, args: Bundle?) -> Boolean = { _, _ -> true }

    val ids: List<Int> get() = actions.map { it.first }
}

private val logs = IdentityHashMap<AccessibilityNodeInfo, NodeLog>()

/** The action log of a node made by [node]. */
internal val AccessibilityNodeInfo.log: NodeLog get() = logs.getValue(this)

/** A visible, enabled node of [className] at [bounds] (pixels) in [pkg], configured by [setup]. */
internal fun node(
    className: String,
    bounds: Rect,
    pkg: String = APP,
    setup: AccessibilityNodeInfo.() -> Unit = {},
): AccessibilityNodeInfo {
    val node = AccessibilityNodeInfo()
    val log = NodeLog()
    logs[node] = log
    shadowOf(node).setOnPerformActionListener { action, args ->
        log.actions += action to args
        log.answer(action, args)
    }
    node.className = className
    node.packageName = pkg
    node.setBoundsInScreen(bounds)
    node.isVisibleToUser = true
    node.isEnabled = true
    node.setup()
    return node
}

/** Adds [children] under this node (Robolectric keeps parent links). */
internal fun AccessibilityNodeInfo.with(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
    children.forEach { shadowOf(this).addChild(it) }
    return this
}

/** A full-display window of [type] with [root], id [id] and z-order [layer] on [displayId]. */
internal fun window(
    root: AccessibilityNodeInfo?,
    id: Int = 1,
    layer: Int = 0,
    type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    displayId: Int = 0,
): AccessibilityWindowInfo {
    val window = AccessibilityWindowInfo()
    val shadow = shadowOf(window)
    shadow.setRoot(root)
    shadow.setId(id)
    shadow.setLayer(layer)
    shadow.setType(type)
    shadow.setBoundsInScreen(GEOMETRY.pixels)
    shadow.setDisplayId(displayId)
    return window
}

/** Records what the screen port asks of the accessibility service. */
internal class FakeHost(
    var windows: Map<Int, List<AccessibilityWindowInfo>> = emptyMap(),
    var sizes: Map<Int, Size> = mapOf(0 to Size(GEOMETRY.width, GEOMETRY.height)),
) : ScreenHost {
    val flow = MutableSharedFlow<A11yEvent>(extraBufferCapacity = 64)
    val gestures = ArrayList<Pair<Int, List<Stroke>>>()
    val globalActions = ArrayList<Int>()
    var acceptGestures = true
    var acceptGlobal = true
    var shot: Screenshot = Screenshot.Failed(0)

    /** How a screenshot is answered; by default at once with [shot]. */
    var takeShot: suspend () -> Screenshot = { shot }

    /** Windows captured alone, in order. */
    val windowShots = ArrayList<Int>()

    /** How a window screenshot is answered; by default as before API 34 (no such call). */
    var takeWindowShot: suspend (Int) -> Screenshot? = { null }
    var windowStates: List<A11yEvent> = emptyList()
    var ime: ImeConnection? = null

    /** Called after every gesture, e.g. to change the tree as the app would. */
    var onGesture: () -> Unit = {}

    override val events: Flow<A11yEvent> get() = flow

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = windows[displayId].orEmpty()

    override fun displaySize(displayId: Int): Size? = sizes[displayId]

    override fun recentWindowStates(displayId: Int): List<A11yEvent> = windowStates

    override suspend fun dispatchStrokes(
        displayId: Int,
        strokes: List<Stroke>,
    ): Boolean {
        gestures += displayId to strokes
        onGesture()
        return acceptGestures
    }

    override fun performGlobalAction(action: Int): Boolean {
        globalActions += action
        return acceptGlobal
    }

    override suspend fun screenshot(displayId: Int): Screenshot = takeShot()

    override suspend fun windowScreenshot(windowId: Int): Screenshot? {
        windowShots += windowId
        return takeWindowShot(windowId)
    }

    override fun imeConnection(): ImeConnection? = ime
}
