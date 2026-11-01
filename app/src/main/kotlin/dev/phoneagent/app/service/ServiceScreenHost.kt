package dev.phoneagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Size
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import dev.phoneagent.app.adapters.screen.A11yEvent
import dev.phoneagent.app.adapters.screen.ImeConnection
import dev.phoneagent.app.adapters.screen.ScreenHost
import dev.phoneagent.app.adapters.screen.Screenshot
import dev.phoneagent.app.adapters.screen.Stroke
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * [ScreenHost] over one connection of [AgentAccessibilityService]. Lives from `onServiceConnected`
 * to `onUnbind`; after [close] its events stay silent and screenshots fail.
 */
internal class ServiceScreenHost(
    private val service: AccessibilityService,
) : ScreenHost {
    // Observers that fall behind lose the oldest events; the system callback never blocks.
    private val eventFlow =
        MutableSharedFlow<A11yEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val windowStates = HashMap<Int, ArrayDeque<A11yEvent>>()
    private val shotExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var closed = false

    override val events: Flow<A11yEvent> get() = eventFlow.asSharedFlow()

    /** Republishes [event] for its display; called on the main thread by the service. */
    fun onEvent(event: AccessibilityEvent) {
        val displayId = displayOf(event)
        if (closed || displayId == Display.INVALID_DISPLAY) return
        val copy = A11yEvent(displayId, event.eventType, event.packageName?.toString(), event.className?.toString())
        if (copy.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && copy.packageName != null &&
            copy.className != null
        ) {
            synchronized(windowStates) {
                val recent = windowStates.getOrPut(displayId) { ArrayDeque() }
                recent.addFirst(copy)
                while (recent.size > WINDOW_STATES_KEPT) recent.removeLast()
            }
        }
        eventFlow.tryEmit(copy)
    }

    /** Stops publishing and releases the screenshot thread. */
    fun close() {
        closed = true
        shotExecutor.shutdown()
    }

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        service.windowsOnAllDisplays.get(displayId).orEmpty()

    override fun displaySize(displayId: Int): Size? {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val size = Point()
        // Logical size in the current rotation, the coordinate space of node bounds; the
        // replacement (WindowMetrics) needs a window or display context per display.
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Size(size.x, size.y)
    }

    override fun recentWindowStates(displayId: Int): List<A11yEvent> =
        synchronized(windowStates) { windowStates[displayId]?.toList().orEmpty() }

    override suspend fun dispatchStrokes(
        displayId: Int,
        strokes: List<Stroke>,
    ): Boolean {
        val builder = GestureDescription.Builder().setDisplayId(displayId)
        for (stroke in strokes) {
            val path = Path()
            path.moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { path.lineTo(it.x, it.y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startMs, stroke.durationMs))
        }
        val gesture = builder.build()
        return suspendCancellableCoroutine { cont ->
            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
    }

    override fun performGlobalAction(action: Int): Boolean = service.performGlobalAction(action)

    override suspend fun screenshot(displayId: Int): Screenshot {
        val first = screenshotOnce(displayId)
        val tooSoon =
            first is Screenshot.Failed && first.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        if (!tooSoon) return first
        // The system allows one screenshot per interval (1 s on AOSP); wait it out once.
        delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce(displayId)
    }

    private suspend fun screenshotOnce(displayId: Int): Screenshot {
        if (closed) return Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        return suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                displayId,
                shotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bitmap = softwareCopy(result)
                        val shot =
                            bitmap?.let(Screenshot::Captured)
                                ?: Screenshot.Failed(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                        cont.resume(shot) { _, _, _ -> bitmap?.recycle() }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(failureOf(errorCode))
                    }
                },
            )
        }
    }

    override fun imeConnection(): ImeConnection? =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            service.inputMethod?.let(::ServiceImeConnection)
        } else {
            null
        }

    /**
     * The display of [event]: API 33+ events carry it; before that the event's window is looked up
     * on every display, and events without a window are dropped.
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val all = service.windowsOnAllDisplays
        val index = (0 until all.size()).firstOrNull { i -> all.valueAt(i).any { it.id == event.windowId } }
        return if (event.windowId == -1 || index == null) Display.INVALID_DISPLAY else all.keyAt(index)
    }

    private companion object {
        const val EVENT_BUFFER = 256
        const val WINDOW_STATES_KEPT = 8
        const val SCREENSHOT_INTERVAL_MS = 1_000L

        fun softwareCopy(result: AccessibilityService.ScreenshotResult): Bitmap? =
            result.hardwareBuffer.use { buffer ->
                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.let { hardware ->
                    try {
                        hardware.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardware.recycle()
                    }
                }
            }

        fun failureOf(code: Int): Screenshot =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            ) {
                Screenshot.Secure
            } else {
                Screenshot.Failed(code)
            }
    }
}

/** The service's own input connection (`flagInputMethodEditor`, API 33+), bound to the focused editor. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ServiceImeConnection(
    private val ime: InputMethod,
) : ImeConnection {
    override val editorPackage: String? get() = ime.currentInputEditorInfo?.packageName

    override fun commit(
        text: String,
        replace: Boolean,
    ): Boolean {
        val connection = ime.currentInputConnection ?: return false
        if (replace) connection.performContextMenuAction(android.R.id.selectAll)
        connection.commitText(text, 1, null)
        return true
    }

    override fun performEditorAction(): Boolean {
        val connection = ime.currentInputConnection ?: return false
        val declared = (ime.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val none = declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED
        connection.performEditorAction(if (none) EditorInfo.IME_ACTION_DONE else declared)
        return true
    }
}
