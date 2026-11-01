/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `injectKey` /
 * `injectTap` / `injectStroke` / `inject` / `injectPair`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: strokes are validated by StrokeSpec against the display's
 * own size, injection only ever targets the helper's displays, and the waiting is done by an
 * injected sleeper so the timing is testable.
 */
package dev.phoneagent.privileged.cap

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Injects touches and keys into the agent's displays (research §2.4 step 4).
 *
 * Nothing is ever injected into a display the helper does not own, so no event can reach the
 * user's screen; keys are additionally limited to [PressKeys].
 */
internal class InputInjector(
    private val bridge: SystemBridge,
    private val displays: VirtualDisplays,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val sleep: (Long) -> Unit = SystemClock::sleep,
) {
    /**
     * One finger from down to up: point `i` is at (`xs[i]`, `ys[i]`) `times[i]` ms after the down.
     * Each event is injected when its moment arrives, so the app sees the speed the caller chose.
     * Returns false when the stroke is refused or the down could not be injected; a missed middle
     * point is tolerated, and the up is always sent so no finger stays down.
     */
    fun stroke(
        displayId: Int,
        xs: IntArray?,
        ys: IntArray?,
        times: LongArray?,
    ): Boolean {
        val size = displays.sizeOf(displayId)
        val refusal =
            when {
                size == null -> "display $displayId is not one of the agent's"
                xs == null || ys == null || times == null -> "xs, ys and times are required"
                else -> StrokeSpec.problem(xs, ys, times, size.first, size.second)
            }
        if (refusal != null) {
            Log.w(TAG, "injectStroke refused: $refusal")
            return false
        }
        return send(displayId, checkNotNull(xs), checkNotNull(ys), checkNotNull(times))
    }

    private fun send(
        displayId: Int,
        xs: IntArray,
        ys: IntArray,
        times: LongArray,
    ): Boolean {
        val down = clock()
        val last = times.size - 1
        if (!point(displayId, xs, ys, times, 0, MotionEvent.ACTION_DOWN, down)) return false
        for (i in 1 until last) point(displayId, xs, ys, times, i, MotionEvent.ACTION_MOVE, down)
        // Retry the up once: a finger left down would block every later action.
        return point(displayId, xs, ys, times, last, MotionEvent.ACTION_UP, down) ||
            point(displayId, xs, ys, times, last, MotionEvent.ACTION_UP, down)
    }

    @Suppress("LongParameterList") // One point of a stroke; splitting it would only hide the arrays.
    private fun point(
        displayId: Int,
        xs: IntArray,
        ys: IntArray,
        times: LongArray,
        index: Int,
        action: Int,
        downAt: Long,
    ): Boolean {
        val wait = downAt + times[index] - clock()
        if (wait > 0) sleep(wait)
        val event = motion(xs[index], ys[index], action, downAt, downAt + times[index])
        return try {
            inject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun motion(
        x: Int,
        y: Int,
        action: Int,
        downAt: Long,
        eventAt: Long,
    ): MotionEvent {
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
        val coordinates =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x.toFloat()
                    this.y = y.toFloat()
                    pressure = 1f
                    size = 1f
                },
            )
        return MotionEvent.obtain(
            downAt,
            eventAt,
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    /** One allow-listed key, down and up, on an agent display. */
    fun key(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val refusal =
            when {
                !displays.owns(displayId) -> "display $displayId is not one of the agent's"
                !PressKeys.isAllowed(keyCode) -> "key $keyCode acts on the whole phone"
                else -> null
            }
        if (refusal != null) {
            Log.w(TAG, "injectKey refused: $refusal")
            return false
        }
        return keyPair(displayId, keyCode)
    }

    private fun keyPair(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val now = clock()
        val down =
            KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                0,
                VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD,
            )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        if (!inject(down, displayId)) return false
        return inject(up, displayId) || inject(up, displayId)
    }

    /**
     * `InputEvent.setDisplayId` then `injectInputEvent(event, ASYNC)`. Throws nothing: a failed
     * injection is reported to the caller, which decides whether to fall back.
     */
    private fun inject(
        event: InputEvent,
        displayId: Int,
    ): Boolean =
        try {
            reflecting("InputEvent.setDisplayId") {
                event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
            }
            val manager = bridge.inputManager
            reflecting("injectInputEvent") {
                bridge.method(manager, "injectInputEvent", 2).invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
            }
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "cannot inject into display $displayId", e)
            false
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"

        /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`. */
        const val INJECT_MODE_ASYNC = 0

        /** `KeyCharacterMap.VIRTUAL_KEYBOARD`. */
        const val VIRTUAL_KEYBOARD = -1
    }
}
