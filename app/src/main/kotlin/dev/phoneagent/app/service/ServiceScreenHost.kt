/*
 * Adapted from Mika, app/src/main/java/com/wangzan101/mika/cap/phone/a11y/ScreenAccessibility.kt
 * (https://github.com/wangzan101/Mika, commit 9cef3750addd20b2af9e3214a8f5e56301670174),
 * Copyright 2026 wangzan101, licensed under the Apache License, Version 2.0 (see NOTICE): the
 * per-display window lookup, the suspending gesture dispatch and the display of an event.
 * Modified by PhoneAgent contributors: rewritten in English behind the ScreenHost interface,
 * strokes with their own start and duration, events copied into A11yEvent with their window.
 */
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
import android.util.SparseArray
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import androidx.core.util.size
import dev.phoneagent.app.adapters.screen.A11yEvent
import dev.phoneagent.app.adapters.screen.ImeConnection
import dev.phoneagent.app.adapters.screen.ScreenHost
import dev.phoneagent.app.adapters.screen.Screenshot
import dev.phoneagent.app.adapters.screen.Stroke
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [ScreenHost] over one connection of [AgentAccessibilityService]. Lives from `onServiceConnected`
 * to `onUnbind`; after [close] its events stay silent and screenshots fail.
 *
 * @param convert turns a screenshot into a software bitmap and closes its hardware buffer
 *   (`null` when it cannot); replaced in tests.
 */
internal class ServiceScreenHost(
    private val service: AccessibilityService,
    convert: (AccessibilityService.ScreenshotResult) -> Bitmap? = Screenshots::softwareCopy,
) : ScreenHost {
    // Observers that fall behind lose the oldest events; the system callback never blocks.
    private val eventFlow =
        MutableSharedFlow<A11yEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val windowStates = HashMap<Int, ArrayDeque<A11yEvent>>()

    private val shots = Screenshots(service, convert)

    @Volatile private var closed = false

    override val events: Flow<A11yEvent> get() = eventFlow.asSharedFlow()

    /** Republishes [event] for its display; called on the main thread by the service. */
    fun onEvent(event: AccessibilityEvent) {
        val displayId = displayOf(event) { service.windowsOnAllDisplays }
        if (closed || displayId == Display.INVALID_DISPLAY) return
        val copy =
            A11yEvent(
                displayId,
                event.eventType,
                event.packageName?.toString(),
                event.className?.toString(),
                event.windowId,
            )
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

    /** Stops publishing; screenshots in flight end as failed, and later ones fail at once. */
    fun close() {
        closed = true
        shots.close()
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

    override suspend fun screenshot(displayId: Int): Screenshot = shots.take(displayId)

    override suspend fun windowScreenshot(windowId: Int): Screenshot? = shots.ofWindow(windowId)

    override fun imeConnection(): ImeConnection? =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            service.inputMethod?.let(::ServiceImeConnection)
        } else {
            null
        }

    private companion object {
        const val EVENT_BUFFER = 256
        const val WINDOW_STATES_KEPT = 8

        /**
         * The display of [event]: API 33+ events carry it; before that the event's window is looked
         * up in the windows of every display ([windows], a binder call), and events without a window
         * are dropped.
         */
        fun displayOf(
            event: AccessibilityEvent,
            windows: () -> SparseArray<List<AccessibilityWindowInfo>>,
        ): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
            val all = windows()
            val index = (0 until all.size).firstOrNull { i -> all.valueAt(i).any { it.id == event.windowId } }
            return if (event.windowId == -1 || index == null) Display.INVALID_DISPLAY else all.keyAt(index)
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

    /** `true` whenever a connection is bound: `InputConnection.performEditorAction` reports nothing more. */
    override fun performEditorAction(): Boolean {
        val connection = ime.currentInputConnection ?: return false
        val declared = (ime.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val none = declared == EditorInfo.IME_ACTION_NONE || declared == EditorInfo.IME_ACTION_UNSPECIFIED
        connection.performEditorAction(if (none) EditorInfo.IME_ACTION_DONE else declared)
        return true
    }

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean {
        val connection = ime.currentInputConnection ?: return false
        connection.setSelection(start, end)
        return true
    }

    // The editor handles calls on one connection in order, so this sees a setSelection sent before.
    override fun selection(): Pair<Int, Int>? {
        val around = ime.currentInputConnection?.getSurroundingText(0, 0, 0)
        val offset = around?.offset ?: -1
        return if (around == null ||
            offset < 0
        ) {
            null
        } else {
            (offset + around.selectionStart) to (offset + around.selectionEnd)
        }
    }

    // One character after the selection is asked for: none comes back only at the end.
    override fun cursorAtEnd(): Boolean? {
        val around = ime.currentInputConnection?.getSurroundingText(0, 1, 0) ?: return null
        val start = around.selectionStart
        val end = around.selectionEnd
        return if (start < 0 || end < 0) null else start == end && end == around.text.length
    }

    // One character before and after the selection is asked for: none comes back only when it is all.
    override fun selectAll(): Boolean? {
        val connection = ime.currentInputConnection ?: return null
        connection.performContextMenuAction(android.R.id.selectAll)
        return connection.getSurroundingText(1, 1, 0)?.let { around ->
            val start = around.selectionStart
            val end = around.selectionEnd
            if (start < 0 || end < 0) null else start == 0 && end == around.text.length
        }
    }
}
