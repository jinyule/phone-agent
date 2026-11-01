/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `createDisplay` /
 * `releaseDisplay` / `displayFlags` / `setImePolicy`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: size and density are validated first, the IME policy is
 * read back, and the known size of every display is kept for the input checks.
 */
package dev.phoneagent.privileged.cap

import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import dev.phoneagent.privileged.DisplayResult

/**
 * The agent's virtual displays (research §2.4 steps 1 and 5). The surface comes from the app's
 * `ImageReader`, so frames never leave the two processes.
 *
 * Displays die with the helper process (`DESTROY_CONTENT_ON_REMOVAL`), so the helper has to stay
 * alive for as long as a task runs on one.
 */
internal class VirtualDisplays(
    bridge: SystemBridge,
    private val sdkInt: Int,
    private val name: String = "phoneagent-agent",
) {
    private val ime = DisplayImePolicy(bridge)
    private val displayManager = bridge.displayManager

    private class Held(
        val display: VirtualDisplay,
        val width: Int,
        val height: Int,
    )

    private val lock = Any()
    private val displays = LinkedHashMap<Int, Held>()

    /** Display ids the helper owns. */
    fun ids(): Set<Int> = synchronized(lock) { displays.keys.toSet() }

    fun owns(displayId: Int): Boolean = sizeOf(displayId) != null

    /** Size of a display the helper owns, or null. */
    fun sizeOf(displayId: Int): Pair<Int, Int>? =
        synchronized(lock) { displays[displayId]?.let { it.width to it.height } }

    /**
     * Creates a display rendering into [surface]; returns its id, or a negative
     * [DisplayResult] value. The IME policy is set right after creation: a keyboard must never
     * appear on the user's screen because the agent focused a text field.
     */
    fun create(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
    ): Int {
        val refusal = if (surface == null) "no surface" else DisplaySpec.problem(width, height, dpi)
        if (refusal != null) {
            Log.w(TAG, "createDisplay refused: $refusal")
            return DisplayResult.REFUSED
        }
        return add(checkNotNull(surface), width, height, dpi)
    }

    private fun add(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
    ): Int =
        try {
            val display = newDisplay(surface, width, height, dpi)
            val id = display.display.displayId
            synchronized(lock) { displays[id] = Held(display, width, height) }
            keepKeyboardLocal(id)
            Log.i(TAG, "display $id: ${width}x$height @ ${dpi}dpi, flags 0x${flags.toString(HEX)}")
            id
        } catch (e: CapabilityUnavailable) {
            Log.e(TAG, "createDisplay failed", e)
            DisplayResult.FAILED
        }

    private fun newDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
    ): VirtualDisplay =
        reflecting("DisplayManager.createVirtualDisplay") {
            displayManager.createVirtualDisplay(name, width, height, dpi, surface, flags)
        } ?: throw CapabilityUnavailable("createVirtualDisplay returned no display (flags refused?)")

    /** Releases one display; tasks on it are destroyed with it. */
    fun release(displayId: Int) {
        val held = synchronized(lock) { displays.remove(displayId) }
        if (held == null) {
            Log.w(TAG, "releaseDisplay($displayId): not ours")
            return
        }
        held.display.release()
        Log.i(TAG, "display $displayId released")
    }

    /** Releases every display; returns how many were released. */
    fun releaseAll(): Int {
        val held = synchronized(lock) { displays.values.toList().also { displays.clear() } }
        held.forEach { it.display.release() }
        return held.size
    }

    private val flags: Int get() = DisplaySpec.flagsFor(sdkInt)

    /** Keeps the keyboard on [displayId]; throws [CapabilityUnavailable] so `probe()` can report it. */
    fun setImePolicy(displayId: Int): Unit = ime.setLocal(displayId)

    /**
     * The same, during creation: a display whose keyboard would open on the user's screen is still
     * usable for everything that does not type, so this only logs.
     */
    private fun keepKeyboardLocal(displayId: Int) {
        try {
            ime.setLocal(displayId)
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "display $displayId keeps the system IME policy", e)
        }
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val HEX = 16
    }
}

/**
 * `IWindowManager.setDisplayImePolicy(displayId, LOCAL)`: the keyboard opens on the agent's
 * display, never on the user's screen. Read back where the getter exists, because a policy that
 * silently stayed at the default would send the keyboard to the main screen.
 */
private class DisplayImePolicy(
    private val bridge: SystemBridge,
) {
    fun setLocal(displayId: Int) {
        val wm = bridge.windowManager
        reflecting("IWindowManager.setDisplayImePolicy") {
            bridge.method(wm, "setDisplayImePolicy", 2).invoke(wm, displayId, DisplaySpec.IME_POLICY_LOCAL)
        }
        val policy = readBack(wm, displayId) ?: return
        if (policy != DisplaySpec.IME_POLICY_LOCAL) {
            throw CapabilityUnavailable("display $displayId reports IME policy $policy, wanted LOCAL")
        }
    }

    private fun readBack(
        wm: Any,
        displayId: Int,
    ): Int? =
        try {
            reflecting("IWindowManager.getDisplayImePolicy") {
                bridge.method(wm, "getDisplayImePolicy", 1).invoke(wm, displayId) as Int
            }
        } catch (e: CapabilityUnavailable) {
            Log.i(TAG, "cannot read back the IME policy of display $displayId", e)
            null
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
