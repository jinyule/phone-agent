package dev.phoneagent.privileged.cap

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import dev.phoneagent.privileged.ProbeKeys

/**
 * The capability probe (phase1-architecture §7.1 ⑥): builds a small display, keeps the keyboard on
 * it, reads the task list, injects one harmless tap, registers the main-screen watch and releases
 * the display again. Every step reports `ok` or the reason it is unavailable, so an unsupported
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
        } finally {
            if (displayId >= 0) steps[ProbeKeys.Step.RELEASE] = step { displays.release(displayId) }
            reader.close()
        }
        Log.i(TAG, "probe: ${steps.entries.joinToString { "${it.key}=${it.value}" }}")
        return bundle(steps)
    }

    private fun createDisplay(
        reader: ImageReader,
        steps: MutableMap<String, String>,
    ): Int {
        val id = displays.create(reader.surface, WIDTH, HEIGHT, DPI)
        steps[ProbeKeys.Step.DISPLAY] =
            if (id >= 0) ProbeKeys.RESULT_OK else "createDisplay returned $id; see logcat"
        return id
    }

    private fun exercise(
        displayId: Int,
        steps: MutableMap<String, String>,
    ) {
        steps[ProbeKeys.Step.IME] = step { displays.setImePolicy(displayId) }
        steps[ProbeKeys.Step.TASKS] = step { require(tasks.rows().isNotEmpty()) { "the task list is empty" } }
        steps[ProbeKeys.Step.INPUT] = step { require(tap(displayId)) { "injectStroke was refused; see logcat" } }
    }

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
            describe(e)
        } catch (e: IllegalArgumentException) {
            describe(e)
        } catch (e: IllegalStateException) {
            describe(e)
        }

    private companion object {
        const val TAG = "PhoneAgentHelper"
        const val WIDTH = 360
        const val HEIGHT = 720
        const val DPI = 160
        const val TAP_MS = 40L

        fun describe(error: Throwable): String {
            val cause = error.cause
            val detail = if (cause == null) "" else ": ${cause.javaClass.simpleName}"
            return "${error.message.orEmpty().ifEmpty { error.javaClass.simpleName }}$detail"
        }

        fun bundle(steps: Map<String, String>): Bundle =
            Bundle().apply {
                putStringArray(ProbeKeys.STEPS, steps.keys.toTypedArray())
                putStringArray(ProbeKeys.RESULTS, steps.values.toTypedArray())
                putBoolean(ProbeKeys.OK, steps.values.all { it == ProbeKeys.RESULT_OK })
            }
    }
}
