package dev.phoneagent.privileged.cap

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import dev.phoneagent.privileged.ProbeKeys

/**
 * The capability probe (phase1-architecture §7.1 ⑥): builds a small display, keeps the keyboard on
 * it, reads the task list, injects one harmless tap, registers the main-screen watch, reads the
 * lock state and releases the display again. Every step reports `ok` or the reason it is unavailable, so an unsupported
 * ROM is named during onboarding instead of failing later (research §5.3 point 7).
 *
 * The display renders into the helper's own `ImageReader`, so probing needs nothing from the app
 * and the frames stay in this process.
 */
internal class Prober(
    private val displays: VirtualDisplays,
    private val tasks: TaskTable,
    private val input: InputInjector,
    private val mainScreen: MainScreenWatch,
    private val keyguard: Keyguard,
) {
    /** Runs the probe; see [ProbeKeys] for the shape of the result. */
    fun probe(): Bundle {
        val steps = linkedMapOf<String, String>()
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        var displayId = -1
        try {
            displayId = createDisplay(reader, steps)
            if (displayId >= 0) exercise(displayId, steps)
            steps[ProbeKeys.Step.MAIN_SCREEN] = step { require(mainScreen.start()) { "no task stack listener" } }
            steps[ProbeKeys.Step.LOCK] = lock()
        } finally {
            if (displayId >= 0) steps[ProbeKeys.Step.RELEASE] = release(displayId)
            reader.close()
        }
        Log.i(TAG, "probe: ${steps.entries.joinToString { "${it.key}=${it.value}" }}")
        return bundle(steps)
    }

    /** The probe display's id, or -1; below API 34 the reason is reported without creating one. */
    private fun createDisplay(
        reader: ImageReader,
        steps: MutableMap<String, String>,
    ): Int =
        when (val opened = displays.open(reader.surface, WIDTH, HEIGHT, DPI)) {
            is VirtualDisplays.Opened.Display -> {
                steps[ProbeKeys.Step.DISPLAY] = ProbeKeys.RESULT_OK
                // open() keeps a display only when its IME policy was set and read back as LOCAL.
                steps[ProbeKeys.Step.IME] = ProbeKeys.RESULT_OK
                opened.id
            }

            is VirtualDisplays.Opened.Refused -> {
                steps[ProbeKeys.Step.DISPLAY] = opened.reason
                -1
            }

            is VirtualDisplays.Opened.Failed -> {
                steps[ProbeKeys.Step.DISPLAY] = opened.reason
                opened.ime?.let { steps[ProbeKeys.Step.IME] = it }
                -1
            }
        }

    private fun exercise(
        displayId: Int,
        steps: MutableMap<String, String>,
    ) {
        steps[ProbeKeys.Step.TASKS] = step { require(tasks.rows().isNotEmpty()) { "the task list is empty" } }
        steps[ProbeKeys.Step.INPUT] = step { require(tap(displayId)) { "injectStroke was refused; see logcat" } }
    }

    /**
     * Whether the lock state can be read, the same way `startActivity` reads it; locked or not
     * does not matter. When it cannot, every start answers LOCKED, so the probe must say why.
     */
    private fun lock(): String =
        when (val state = keyguard.read()) {
            is LockState.Known -> ProbeKeys.RESULT_OK
            is LockState.Unknown -> state.reason
        }

    /** Releases the probe display; one that cannot be released waits for `releaseAll` and fails the step. */
    private fun release(displayId: Int): String = step { check(displays.release(displayId)) { NOT_RELEASED } }

    /** One tap in the middle of the probe display: there is nothing on it to activate. */
    private fun tap(displayId: Int): Boolean {
        val x = intArrayOf(WIDTH / 2, WIDTH / 2)
        val y = intArrayOf(HEIGHT / 2, HEIGHT / 2)
        return input.stroke(displayId, x, y, longArrayOf(0, TAP_MS))
    }

    /** Runs one step, turning the failure into the line reported for it. */
    private inline fun step(block: () -> Unit): String =
        try {
            block()
            ProbeKeys.RESULT_OK
        } catch (e: CapabilityUnavailable) {
            reasonFor(e)
        } catch (e: IllegalArgumentException) {
            reasonFor(e)
        } catch (e: IllegalStateException) {
            reasonFor(e)
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val WIDTH = 360
        const val HEIGHT = 720
        const val DPI = 160
        const val TAP_MS = 40L
        const val NOT_RELEASED = "the probe display could not be released; it waits for releaseAll"

        fun bundle(steps: Map<String, String>): Bundle =
            Bundle().apply {
                putStringArray(ProbeKeys.STEPS, steps.keys.toTypedArray())
                putStringArray(ProbeKeys.RESULTS, steps.values.toTypedArray())
                putBoolean(ProbeKeys.OK, steps.values.all { it == ProbeKeys.RESULT_OK })
            }
    }
}
