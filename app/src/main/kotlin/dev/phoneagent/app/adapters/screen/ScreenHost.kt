package dev.phoneagent.app.adapters.screen

import android.graphics.Bitmap
import android.util.Size
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What [AndroidScreen] needs from the accessibility service. The service implements it; tests use a
 * fake. One instance lives as long as one service connection: after a reconnect the old instance's
 * [events] stay silent and every call should go through the new instance.
 *
 * Threading: [windowsOn], [displaySize] and the node calls made on the returned trees are blocking
 * binder calls; callers run them off the main thread.
 */
internal interface ScreenHost {
    /** Windows currently on [displayId] in no particular order; empty when the display has none or does not exist. */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo>

    /** Size of [displayId] in pixels in its current rotation, or `null` when the display does not exist. */
    fun displaySize(displayId: Int): Size?

    /** Accessibility events of this connection, already copied out of the recycled system objects. */
    val events: Flow<A11yEvent>

    /** The most recent window-state changes on [displayId], newest first (activity tracking). */
    fun recentWindowStates(displayId: Int): List<A11yEvent>

    /**
     * Dispatches one gesture made of [strokes] on [displayId]. Returns `true` when the system
     * reports it completed, `false` when it was rejected or cancelled.
     */
    suspend fun dispatchStrokes(
        displayId: Int,
        strokes: List<Stroke>,
    ): Boolean

    /** `AccessibilityService.performGlobalAction`; acts on the focused display. */
    fun performGlobalAction(action: Int): Boolean

    /**
     * Captures [displayId]. Ends with [Screenshot.Failed] when the host closes meanwhile; throws
     * `ScreenUnavailableException` when the connection is gone. The caller bounds the wait; a
     * screenshot nobody waits for any more is freed.
     */
    suspend fun screenshot(displayId: Int): Screenshot

    /**
     * Captures window [windowId] alone, the only reliable FLAG_SECURE signal: a window holding
     * secure content ends with [Screenshot.Secure]. `null` before API 34, which has no such call.
     * Ends and throws as [screenshot] does.
     */
    suspend fun windowScreenshot(windowId: Int): Screenshot?

    /** The service's own input connection to the focused editor (API 33+), or `null` when there is none. */
    fun imeConnection(): ImeConnection?
}

/**
 * An accessibility event reduced to the fields the screen port uses.
 *
 * @property displayId display the event happened on.
 * @property type `AccessibilityEvent.TYPE_*`.
 * @property packageName source package, or `null`.
 * @property className source class (an activity for window-state changes), or `null`.
 * @property windowId window the event came from, or -1 when it has none.
 */
internal data class A11yEvent(
    val displayId: Int,
    val type: Int,
    val packageName: String?,
    val className: String?,
    val windowId: Int = NO_WINDOW,
) {
    internal companion object {
        /** [windowId] of an event without a window (`AccessibilityWindowInfo.UNDEFINED_WINDOW_ID`). */
        const val NO_WINDOW: Int = -1
    }
}

/**
 * One finger of a gesture in display pixels.
 *
 * @property points path the finger follows; one point is a press in place.
 * @property startMs offset from the gesture start.
 * @property durationMs how long the finger is down, > 0.
 */
internal class Stroke(
    val points: List<PixelPoint>,
    val startMs: Long,
    val durationMs: Long,
) {
    init {
        require(points.isNotEmpty()) { "A stroke needs at least one point" }
        require(startMs >= 0 && durationMs > 0) { "Invalid stroke timing start=$startMs duration=$durationMs" }
    }
}

/** A point in display pixels. */
internal data class PixelPoint(
    val x: Float,
    val y: Float,
)

/** Result of [ScreenHost.screenshot]. */
internal sealed interface Screenshot {
    /** A software bitmap the caller owns and must recycle. */
    class Captured(
        val bitmap: Bitmap,
    ) : Screenshot

    /** A FLAG_SECURE window is on the display. */
    data object Secure : Screenshot

    /** The system refused; [code] is `AccessibilityService.ERROR_TAKE_SCREENSHOT_*`. */
    data class Failed(
        val code: Int,
    ) : Screenshot
}

/** Frees what this screenshot holds (the bitmap of [Screenshot.Captured]). */
internal fun Screenshot.release() {
    if (this is Screenshot.Captured) bitmap.recycle()
}

/**
 * The screenshot [take] gives within [waitMs], or `null` when none came in time (or [take] gave
 * none). A screenshot taken but not handed over, because the time ran out or the caller was
 * cancelled just as it came, is nobody's: it is freed here with [release], once. One that is
 * handed over is the caller's.
 */
internal suspend fun screenshotWithin(
    waitMs: Long,
    release: (Screenshot) -> Unit = Screenshot::release,
    take: suspend () -> Screenshot?,
): Screenshot? {
    var taken: Screenshot? = null
    var handedOver = false
    try {
        val shot = withTimeoutOrNull(waitMs) { take().also { taken = it } }
        handedOver = shot != null
        return shot
    } finally {
        if (!handedOver) taken?.let(release)
    }
}

/** The accessibility service's input connection to the focused editor. */
internal interface ImeConnection {
    /** Package of the editor the connection is bound to, or `null` when unknown. */
    val editorPackage: String?

    /** Commits [text], replacing the editor's content first when [replace]. Returns whether it was sent. */
    fun commit(
        text: String,
        replace: Boolean,
    ): Boolean

    /**
     * Sends the editor's IME action (search, send, done…). Returns whether it was sent, not whether
     * the editor acted on it: the input connection does not tell, so a "done" ENTER is judged only
     * by its effect on the screen.
     */
    fun performEditorAction(): Boolean

    /** Asks the editor to select [start]..[end] (a cursor when equal). Returns whether it was sent. */
    fun setSelection(
        start: Int,
        end: Int,
    ): Boolean

    /** The editor's selection as absolute offsets (start to end), or `null` when it cannot be read. */
    fun selection(): Pair<Int, Int>?

    /**
     * Whether the editor's cursor is collapsed at the end of its text (nothing follows it), or
     * `null` when that cannot be read. Works for password fields, whose text accessibility hides;
     * the text looked at is not kept.
     */
    fun cursorAtEnd(): Boolean?

    /**
     * Selects all of the editor's text and reports whether the editor confirms that nothing is left
     * before or after the selection, from the start of its text on, or `null` when that cannot be
     * read (the offset of what the editor tells unknown included). Works for password fields; the
     * text looked at is not kept.
     */
    fun selectAll(): Boolean?
}
