/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit
 * 9cef3750addd20b2af9e3214a8f5e56301670174,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `createDisplay` /
 * `releaseDisplay` / `setImePolicy`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the flags moved to DisplaySpec, size and density are
 * validated first, the IME policy is read back, the known size of every display is kept for the
 * input checks, and each display is released on its own.
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
    /** `VirtualDisplay.release`; replaceable so a test can make it throw. */
    private val releaseDisplay: (VirtualDisplay) -> Unit = VirtualDisplay::release,
) {
    private val ime = DisplayImePolicy(bridge)
    private val displayManager = bridge.displayManager

    private class Held(
        val display: VirtualDisplay,
        val width: Int,
        val height: Int,
    )

    private val lock = Any()

    /** Verified displays the app may use; guarded by [lock]. */
    private val displays = LinkedHashMap<Int, Held>()

    /** Displays whose release failed: never used again, released again by [releaseAll]; guarded by [lock]. */
    private val pending = LinkedHashMap<Int, VirtualDisplay>()

    /** Display ids the helper owns and the app may use; displays waiting for cleanup are not among them. */
    fun ids(): Set<Int> = synchronized(lock) { displays.keys.toSet() }

    /** Displays the helper still has to release. No capability accepts them; [releaseAll] retries them. */
    val pendingCleanup: Set<Int> get() = synchronized(lock) { pending.keys.toSet() }

    fun owns(displayId: Int): Boolean = sizeOf(displayId) != null

    /** Size of a display the helper owns and the app may use, or null. */
    fun sizeOf(displayId: Int): Pair<Int, Int>? =
        synchronized(lock) { displays[displayId]?.let { it.width to it.height } }

    /** What [open] did. */
    sealed interface Opened {
        /** A display the helper now owns. */
        data class Display(
            val id: Int,
        ) : Opened

        /** Nothing was created: the request (or this API level) is not acceptable. */
        data class Refused(
            val reason: String,
        ) : Opened

        /**
         * No usable display: the platform refused, or the one it made would open its keyboard on
         * the user's screen ([ime] says why the IME policy failed). That one was released again, or,
         * when the release failed, set aside for cleanup ([pendingCleanup]): no capability accepts
         * it, and [releaseAll] releases it. [reason] says which.
         */
        data class Failed(
            val reason: String,
            val ime: String? = null,
        ) : Opened
    }

    /**
     * Creates a display rendering into [surface]; returns its id, or a negative [DisplayResult]
     * value. See [open].
     */
    fun create(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
    ): Int =
        when (val opened = open(surface, width, height, dpi)) {
            is Opened.Display -> {
                opened.id
            }

            is Opened.Refused -> {
                Log.w(TAG, "createDisplay refused: ${opened.reason}")
                DisplayResult.REFUSED
            }

            is Opened.Failed -> {
                Log.e(TAG, "createDisplay failed: ${opened.reason} ${opened.ime.orEmpty()}")
                DisplayResult.FAILED
            }
        }

    /**
     * Creates a display rendering into [surface]. Below API 34 nothing is created: without
     * OWN_FOCUS the display would take the user's focus (phase1-architecture D2). Right after
     * creation the IME policy is set to LOCAL and read back; a display whose keyboard would open on
     * the user's screen is released again and reported as [Opened.Failed].
     */
    fun open(
        surface: Surface?,
        width: Int,
        height: Int,
        dpi: Int,
    ): Opened {
        val refusal =
            when {
                !DisplaySpec.keepsUserFocus(sdkInt) -> "background displays need API 34+, this is API $sdkInt"
                surface == null -> "no surface"
                else -> DisplaySpec.problem(width, height, dpi)
            }
        return if (refusal != null) Opened.Refused(refusal) else add(checkNotNull(surface), width, height, dpi)
    }

    private fun add(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
    ): Opened =
        try {
            keep(newDisplay(surface, width, height, dpi), width, height, dpi)
        } catch (e: CapabilityUnavailable) {
            Log.e(TAG, "createDisplay failed", e)
            Opened.Failed(e.message.orEmpty())
        }

    /** Keeps [display] when its keyboard stays on it; otherwise releases it again. */
    private fun keep(
        display: VirtualDisplay,
        width: Int,
        height: Int,
        dpi: Int,
    ): Opened {
        val id = display.display.displayId
        val imeProblem = ime.problem(id)
        if (imeProblem != null) {
            val outcome =
                if (releaseOrSetAside(listOf(id to display)).isEmpty()) {
                    "display $id was released"
                } else {
                    "display $id could not be released and waits for cleanup by releaseAll, unused"
                }
            return Opened.Failed("$outcome: its keyboard would open on the user's screen", imeProblem)
        }
        synchronized(lock) { displays[id] = Held(display, width, height) }
        Log.i(TAG, "display $id: ${width}x$height @ ${dpi}dpi, flags 0x${flags.toString(HEX)}")
        return Opened.Display(id)
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

    /**
     * Releases one display; tasks on it are destroyed with it. From this call on the display is
     * not usable; one whose release throws is logged and waits for cleanup by [releaseAll].
     */
    fun release(displayId: Int) {
        val display = synchronized(lock) { displays.remove(displayId)?.display ?: pending.remove(displayId) }
        if (display == null) {
            Log.w(TAG, "releaseDisplay($displayId): not ours")
            return
        }
        if (releaseOrSetAside(listOf(displayId to display)).isEmpty()) Log.i(TAG, "display $displayId released")
    }

    /**
     * Releases every display, usable or waiting for cleanup, each on its own (see [releaseEach]);
     * returns how many were released.
     */
    fun releaseAll(): Int {
        val all =
            synchronized(lock) {
                (displays.map { (id, held) -> id to held.display } + pending.toList()).also {
                    displays.clear()
                    pending.clear()
                }
            }
        return all.size - releaseOrSetAside(all).size
    }

    /** Releases [all]; the ones that throw wait in [pending] and are returned. */
    private fun releaseOrSetAside(all: List<Pair<Int, VirtualDisplay>>): List<Pair<Int, VirtualDisplay>> {
        val failed = releaseEach(all, { (id, _) -> "display $id" }) { (_, display) -> releaseDisplay(display) }
        synchronized(lock) { failed.forEach { (id, display) -> pending.putIfAbsent(id, display) } }
        return failed
    }

    private val flags: Int get() = DisplaySpec.flagsFor(sdkInt)

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val HEX = 16
    }
}

/**
 * `IWindowManager.setDisplayImePolicy(displayId, LOCAL)`: the keyboard opens on the agent's
 * display, never on the user's screen. The policy is always read back, because one that silently
 * stayed at the default would send the keyboard to the main screen: a getter that is missing,
 * fails, or answers something other than LOCAL leaves the policy unverified, and the display is
 * not kept.
 */
private class DisplayImePolicy(
    private val bridge: SystemBridge,
) {
    /** Why the keyboard of [displayId] cannot be kept on it, or null when it is. */
    fun problem(displayId: Int): String? {
        try {
            setLocal(displayId)
        } catch (e: CapabilityUnavailable) {
            Log.w(TAG, "display $displayId cannot keep its keyboard", e)
            return e.message.orEmpty()
        }
        return null
    }

    private fun setLocal(displayId: Int) {
        val wm = bridge.windowManager
        reflecting("IWindowManager.setDisplayImePolicy") {
            bridge.method(wm, "setDisplayImePolicy", 2).invoke(wm, displayId, DisplaySpec.IME_POLICY_LOCAL)
        }
        val policy = readBack(wm, displayId)
        if (policy != DisplaySpec.IME_POLICY_LOCAL) {
            throw CapabilityUnavailable("display $displayId reports IME policy $policy, wanted LOCAL")
        }
    }

    /** The policy as read back; a getter that is missing or fails, or an answer that is no policy, throws. */
    private fun readBack(
        wm: Any,
        displayId: Int,
    ): Int {
        val answer =
            try {
                reflecting("IWindowManager.getDisplayImePolicy") {
                    bridge.method(wm, "getDisplayImePolicy", 1).invoke(wm, displayId)
                }
            } catch (e: CapabilityUnavailable) {
                throw CapabilityUnavailable(
                    "the IME policy of display $displayId cannot be read back, so it is unverified (${e.message})",
                    e,
                )
            }
        return answer as? Int ?: throw CapabilityUnavailable("display $displayId reports IME policy $answer")
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
