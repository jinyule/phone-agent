package dev.phoneagent.app.adapters.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.SystemKey
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Taps, scrolls, swipes and keys on one display, each with its fallback chain (phase1 §3.3):
 * node action first, then a display-targeted accessibility gesture. Injection through the
 * privileged helper is the third level from M2 ([InputInjector]).
 *
 * @param ownAt whether a pixel lies on this app's own UI; no gesture or injected stroke starts there.
 */
internal class Actuator(
    private val host: ScreenHost,
    private val displayId: Int,
    private val geometry: DisplayGeometry,
    private val timings: ScreenTimings,
    private val injector: InputInjector?,
    private val ownAt: (PixelPoint) -> Boolean,
) {
    /** Result of one actuation. */
    sealed interface Result {
        /**
         * Performed; [via] names the level that did it (for logs and tests). [settled] when the
         * display was already seen to change and settle.
         */
        data class Done(
            val via: Via,
            val settled: Boolean = false,
        ) : Result

        /**
         * Not done as requested: it may have partly happened, or whether it happened cannot be
         * told; [reason] says what is known, for the model, in English.
         */
        data class NotDone(
            val reason: String,
        ) : Result

        /** Not performed: it would have touched this app's own UI. */
        data object Refused : Result
    }

    /** Which fallback level performed an action. */
    enum class Via { NODE, GESTURE, INJECTED }

    /**
     * Taps (or long-presses) [element]: the action of its nearest clickable ancestor, else a gesture
     * at its center. Apps may answer the action `false` although it ran, so a `false` is followed by
     * a gesture only when [probe] sees no change and the answer came quickly; a `false` that took
     * [ScreenTimings.clickUncertainMs] or longer (the platform gave up waiting) is not retried.
     */
    suspend fun tap(
        element: NodeTarget,
        long: Boolean,
        probe: ClickProbe,
    ): Result {
        val center = PixelPoint(element.pixels.exactCenterX(), element.pixels.exactCenterY())
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val ancestor = NodeActions.clickableAncestor(element.node, center, long) ?: return tapAt(center, long)
        val asked = probe.nowMs()
        val clicked = ancestor.performAction(action)
        val answeredInMs = probe.nowMs() - asked
        return when {
            clicked -> {
                Result.Done(Via.NODE)
            }

            probe.changed() -> {
                Result.Done(Via.NODE, settled = true)
            }

            answeredInMs >= timings.clickUncertainMs -> {
                Result.NotDone(
                    "the app took $answeredInMs ms to answer the click, so it may have happened; " +
                        "it was not repeated, look at the screen before trying again",
                )
            }

            else -> {
                tapAt(center, long)
            }
        }
    }

    /** Taps (or long-presses) the pixel [point] with a gesture. */
    suspend fun tapAt(
        point: PixelPoint,
        long: Boolean,
    ): Result {
        val hold = if (long) timings.longPressMs else timings.tapMs
        return gesture(Stroke(listOf(clamp(point)), startMs = 0, durationMs = hold), "tap")
    }

    /** Swipes from [from] to [to] in [durationMs]. */
    suspend fun swipe(
        from: PixelPoint,
        to: PixelPoint,
        durationMs: Long,
    ): Result = gesture(Stroke(listOf(clamp(from), clamp(to)), startMs = 0, durationMs = durationMs), "swipe")

    /**
     * Scrolls [container] (or the screen when `null`) so that content in [direction] comes into
     * view: the container's directional scroll action, else a swipe inside it.
     */
    suspend fun scroll(
        container: NodeTarget?,
        direction: Direction,
    ): Result {
        val node = container?.node
        val scrolled = node?.let { n -> NodeActions.scrollActionFor(n, direction)?.let(n::performAction) } == true
        val area = if (scrolled) null else scrollArea(container?.pixels)
        return when {
            scrolled -> {
                Result.Done(Via.NODE)
            }

            area == null -> {
                Result.NotDone("no room to swipe for a scroll")
            }

            else -> {
                val (from, to) = NodeActions.scrollSwipe(area, direction)
                swipe(from, to, timings.scrollSwipeMs)
            }
        }
    }

    /**
     * Presses [key]. BACK and HOME are global actions on the main display and need the privileged
     * helper elsewhere; ENTER is the IME action of [focusedField].
     */
    suspend fun key(
        key: SystemKey,
        focusedField: AccessibilityNodeInfo?,
    ): Result =
        when (key) {
            SystemKey.BACK -> global(key, AccessibilityService.GLOBAL_ACTION_BACK)
            SystemKey.HOME -> global(key, AccessibilityService.GLOBAL_ACTION_HOME)
            SystemKey.ENTER -> enter(focusedField)
        }

    /** Sends the IME action (search, send, done…) of [field]. */
    fun enter(field: AccessibilityNodeInfo?): Result =
        when {
            field == null -> Result.NotDone("no focused text field to press enter in")
            NodeActions.imeEnter(field) || imeConnectionEnter(field) -> Result.Done(Via.NODE)
            else -> Result.NotDone("the focused field has no keyboard action; tap the send or search button instead")
        }

    private fun imeConnectionEnter(field: AccessibilityNodeInfo): Boolean {
        val ime = host.imeConnection() ?: return false
        val bound = field.isFocused && ime.editorPackage == field.packageName?.toString()
        return bound && ime.performEditorAction()
    }

    private suspend fun global(
        key: SystemKey,
        action: Int,
    ): Result =
        if (displayId == Display.DEFAULT_DISPLAY) {
            val pressed = host.performGlobalAction(action)
            if (pressed) Result.Done(Via.NODE) else Result.NotDone("the system refused the ${key.name} key")
        } else {
            val injected = injector?.key(displayId, key) == true
            if (injected) {
                Result.Done(Via.INJECTED)
            } else {
                Result.NotDone("${key.name} on a background display needs the privileged helper")
            }
        }

    private suspend fun gesture(
        stroke: Stroke,
        what: String,
    ): Result {
        if (ownAt(stroke.points.first())) return Result.Refused
        val dispatched =
            withTimeoutOrNull(stroke.startMs + stroke.durationMs + timings.gestureSlackMs) {
                host.dispatchStrokes(displayId, listOf(stroke))
            } == true
        return when {
            dispatched -> Result.Done(Via.GESTURE)
            injector?.stroke(displayId, stroke) == true -> Result.Done(Via.INJECTED)
            else -> Result.NotDone("the $what gesture was not accepted by the system")
        }
    }

    private fun clamp(point: PixelPoint): PixelPoint =
        PixelPoint(point.x.coerceIn(0f, geometry.width - 1f), point.y.coerceIn(0f, geometry.height - 1f))

    /** Area a scroll swipe may use: [container] (or the display) minus the status and navigation bar strips. */
    private fun scrollArea(container: Rect?): Rect? {
        val safe = NodeActions.safeArea(geometry)
        val area = Rect(container ?: geometry.pixels)
        val fits =
            area.intersect(safe) && area.width() >= NodeActions.MIN_SWIPE_PX &&
                area.height() >= NodeActions.MIN_SWIPE_PX
        return area.takeIf { fits }
    }
}

/** What [Actuator.tap] asks when a node click answered `false`. */
internal interface ClickProbe {
    /** Milliseconds on a monotonic clock. */
    fun nowMs(): Long

    /**
     * Waits a short while for a change the click made (not just anything on the display); once
     * one came, also for the display to settle. Whether one came.
     */
    suspend fun changed(): Boolean
}

/** A node to act on and its bounds in display pixels, clipped to the display. */
internal class NodeTarget(
    val node: AccessibilityNodeInfo,
    val pixels: Rect,
)

/** Node-level decisions of [Actuator], free of service state. */
internal object NodeActions {
    /** Smallest side of an area a scroll swipe is drawn in. */
    const val MIN_SWIPE_PX: Int = 40

    private const val PERCENT = 100
    private const val EDGE_TOP = 5
    private const val EDGE_BOTTOM = 6
    private const val SWIPE_SPAN = 30
    private const val MAX_ANCESTORS = 8

    /** The node or nearest ancestor that takes the click and covers [center] (where a finger would land). */
    fun clickableAncestor(
        node: AccessibilityNodeInfo,
        center: PixelPoint,
        long: Boolean,
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        return generateSequence(node) { it.parent }
            .take(MAX_ANCESTORS + 1)
            .firstOrNull { candidate ->
                candidate.getBoundsInScreen(rect)
                val takes = if (long) candidate.isLongClickable else candidate.isClickable
                takes && candidate.isEnabled && rect.contains(center.x.toInt(), center.y.toInt())
            }
    }

    /** `ACTION_IME_ENTER` on [field] when it offers it. */
    fun imeEnter(field: AccessibilityNodeInfo): Boolean {
        val id = AccessibilityAction.ACTION_IME_ENTER.id
        return field.actionList.any { it.id == id } && field.performAction(id)
    }

    /** The scroll action of [node] that reveals content in [direction], or `null` when it offers none. */
    fun scrollActionFor(
        node: AccessibilityNodeInfo,
        direction: Direction,
    ): Int? {
        val offered = node.actionList.map { it.id }.toSet()
        val directional =
            when (direction) {
                Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP.id
                Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN.id
                Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT.id
                Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
            }
        val horizontal = isHorizontal(node)
        val generic =
            when (direction) {
                Direction.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD.takeIf { !horizontal }
                Direction.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD.takeIf { !horizontal }
                Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD.takeIf { horizontal }
                Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD.takeIf { horizontal }
            }
        return directional.takeIf { it in offered } ?: generic?.takeIf { it in offered }
    }

    /** Whether [node] scrolls sideways (horizontal scroll views, pagers, one-row grids). */
    fun isHorizontal(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        val info = node.collectionInfo
        return cls.contains("Horizontal") ||
            cls.endsWith("ViewPager") ||
            cls.endsWith("ViewPager2") ||
            (info != null && info.rowCount <= 1 && info.columnCount > 1)
    }

    /** The display minus the strips where the status and navigation bars catch swipes. */
    fun safeArea(geometry: DisplayGeometry): Rect =
        Rect(
            0,
            geometry.height * EDGE_TOP / PERCENT,
            geometry.width,
            geometry.height * (PERCENT - EDGE_BOTTOM) / PERCENT,
        )

    /** Finger path for revealing content in [direction]: the finger moves the opposite way. */
    fun scrollSwipe(
        area: Rect,
        direction: Direction,
    ): Pair<PixelPoint, PixelPoint> {
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val dx = area.width() * SWIPE_SPAN / PERCENT
        val dy = area.height() * SWIPE_SPAN / PERCENT
        return when (direction) {
            Direction.DOWN -> PixelPoint(cx, cy + dy) to PixelPoint(cx, cy - dy)
            Direction.UP -> PixelPoint(cx, cy - dy) to PixelPoint(cx, cy + dy)
            Direction.RIGHT -> PixelPoint(cx + dx, cy) to PixelPoint(cx - dx, cy)
            Direction.LEFT -> PixelPoint(cx - dx, cy) to PixelPoint(cx + dx, cy)
        }
    }
}

/**
 * Input injected by the privileged helper (phase1 §3.5 `injectStroke` / `injectKey`), the last
 * fallback and the only way to press BACK/HOME on a background display. Provided from M2.
 */
internal interface InputInjector {
    /** Injects [stroke] on [displayId]; returns whether it was injected. */
    suspend fun stroke(
        displayId: Int,
        stroke: Stroke,
    ): Boolean

    /** Injects [key] on [displayId]; returns whether it was injected. */
    suspend fun key(
        displayId: Int,
        key: SystemKey,
    ): Boolean
}
