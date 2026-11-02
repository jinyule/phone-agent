package dev.phoneagent.app.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import dev.phoneagent.privileged.ArrivalKeys
import dev.phoneagent.privileged.GrantResult
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.ProbeKeys
import dev.phoneagent.privileged.StartResult
import java.io.File

/**
 * Debug-only walk through the display and input capabilities on a real device, triggered with
 *
 * ```
 * adb shell am broadcast -n dev.phoneagent/dev.phoneagent.app.debug.HelperSelfCheckReceiver \
 *     --es scenario display
 * ```
 *
 * It probes, builds a 1080x2340 display, starts the system settings on it, waits for a frame and
 * writes it as a PNG, injects a tap and BACK, refuses HOME, hands the task to the main screen and
 * takes it back, mutes and unmutes, grants the one allowed permission and releases everything.
 */
internal class DisplaySelfCheck(
    private val context: Context,
    private val core: IPrivilegedCore,
) {
    private val steps = SelfCheckSteps()

    /**
     * A helper call that throws fails its step by name; the later sections and `releaseAll` still
     * run. Only a helper that refuses to probe is left alone, since it built nothing.
     */
    fun run(): List<String> {
        val probed = steps.attempt("probe") { probe() }
        if (probed == false) return steps.lines()
        try {
            if (probed == true) withDisplay()
        } finally {
            steps.attempt("releaseAll") { releaseAll() }
        }
        return steps.lines()
    }

    private fun withDisplay() {
        steps.attempt("createDisplay") { DebugAgentDisplay(core, WIDTH, HEIGHT, DPI) }?.use { display ->
            if (steps.check("createDisplay", display.displayId >= 0, "id ${display.displayId}")) {
                onDisplay(display)
            }
        }
    }

    /**
     * Records every probe step. False when the helper refuses to probe at all (a helper whose
     * capabilities are unavailable answers NOT_IMPLEMENTED with the reason): nothing else can
     * work, so the check stops there. Any other exception propagates to [run], which names it.
     */
    private fun probe(): Boolean {
        val result =
            try {
                core.probe()
            } catch (e: UnsupportedOperationException) {
                steps.fail("probe", e.message.orEmpty())
                return false
            }
        val names = result.getStringArray(ProbeKeys.STEPS).orEmpty()
        val outcomes = result.getStringArray(ProbeKeys.RESULTS).orEmpty()
        names.zip(outcomes).forEach { (name, outcome) ->
            steps.check("probe.$name", outcome == ProbeKeys.RESULT_OK, outcome)
        }
        steps.check("probe.ok", result.getBoolean(ProbeKeys.OK))
        return true
    }

    private fun onDisplay(display: DebugAgentDisplay) {
        val id = display.displayId
        val task = steps.attempt("startSettings") { startSettings(id) }
        steps.attempt("frame") { frame(display) }
        steps.attempt("input") { input(id) }
        if (task != null) steps.attempt("handover") { handover(task, id) }
        steps.attempt("audioAndGrants") { audioAndGrants() }
    }

    /** Starts the settings app on [displayId] and returns its task id. */
    private fun startSettings(displayId: Int): Int? {
        val intent = Intent(Settings.ACTION_SETTINGS)
        val started = core.startActivity(displayId, intent, USER, false)
        if (!steps.check("startActivity", started == StartResult.OK, "StartResult $started")) return null
        val task = pollUntil(TASK_TIMEOUT_MS) { core.topTask(displayId).takeIf { it > 0 } }
        steps.check("topTask", task != null, "task $task on display $displayId")
        steps.check(
            "topTask(main) is untouched",
            core.topTask(MAIN_DISPLAY) != task,
            "main screen top task ${core.topTask(MAIN_DISPLAY)}",
        )
        return task
    }

    private fun frame(display: DebugAgentDisplay) {
        val target = File(context.getExternalFilesDir(null), "agent-display.png")
        val written = display.writeFrame(target, FRAME_TIMEOUT_MS)
        steps.check("frame", written != null && written.length() > 0, "$target (${target.length()} bytes)")
    }

    private fun input(displayId: Int) {
        val middleX = intArrayOf(WIDTH / 2, WIDTH / 2)
        val middleY = intArrayOf(HEIGHT / 2, HEIGHT / 2)
        steps.check("injectStroke", core.injectStroke(displayId, middleX, middleY, longArrayOf(0, TAP_MS)))
        steps.check("injectKey(BACK)", core.injectKey(displayId, KeyEvent.KEYCODE_BACK))
        steps.check(
            "injectKey(HOME) refused",
            !core.injectKey(displayId, KeyEvent.KEYCODE_HOME),
            "whole-phone keys are not injectable",
        )
        steps.check(
            "injectStroke(main) refused",
            !core.injectStroke(MAIN_DISPLAY, middleX, middleY, longArrayOf(0, TAP_MS)),
            "the user's screen is never injected into",
        )
        val outside = intArrayOf(WIDTH, WIDTH)
        val offDisplay = core.injectStroke(displayId, outside, middleY, longArrayOf(0, TAP_MS))
        steps.check("injectStroke(outside) refused", !offDisplay)
    }

    /** Hands [task] to the user and takes it back, as §7.3 describes. */
    private fun handover(
        task: Int,
        displayId: Int,
    ) {
        val watched = core.watchMainScreen(WATCH_MS)
        steps.note("watchMainScreen: main top task $watched")
        steps.check("moveTask(to main)", core.moveTask(task, MAIN_DISPLAY))
        val onMain = pollUntil(TASK_TIMEOUT_MS) { core.topTask(MAIN_DISPLAY).takeIf { it == task } }
        steps.check("task is on the main screen", onMain == task, "top task ${core.topTask(MAIN_DISPLAY)}")
        steps.note("mainArrivals: ${describe(core.mainArrivals(0))}")
        steps.check("moveTask(back)", core.moveTask(task, displayId))
        val back = pollUntil(TASK_TIMEOUT_MS) { core.topTask(displayId).takeIf { it == task } }
        steps.check("task is back on the agent display", back == task, "top task ${core.topTask(displayId)}")
    }

    private fun audioAndGrants() {
        steps.check("setAudioMuted(true)", core.setAudioMuted(SETTINGS_PACKAGE, true))
        steps.check("setAudioMuted(false)", core.setAudioMuted(SETTINGS_PACKAGE, false))
        val granted = core.grantSelf(WRITE_SECURE_SETTINGS)
        steps.check("grantSelf(WRITE_SECURE_SETTINGS)", granted == GrantResult.GRANTED, "GrantResult $granted")
        val refused = core.grantSelf("android.permission.READ_SMS")
        steps.check("grantSelf(READ_SMS) refused", refused == GrantResult.NOT_ALLOWED, "GrantResult $refused")
    }

    private fun releaseAll() {
        core.releaseAll()
        steps.check("releaseAll", core.topTask(MAIN_DISPLAY) >= -1)
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 2_340
        const val DPI = 440
        const val MAIN_DISPLAY = 0
        const val USER = 0
        const val TAP_MS = 40L
        const val WATCH_MS = 3_000
        const val TASK_TIMEOUT_MS = 8_000L
        const val FRAME_TIMEOUT_MS = 8_000L
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        /** The arrivals bundle as one line. */
        fun describe(arrivals: Bundle): String {
            val tasks = arrivals.getIntArray(ArrivalKeys.TASK)?.toList().orEmpty()
            val packages = arrivals.getStringArray(ArrivalKeys.PACKAGE)?.toList().orEmpty()
            val from = arrivals.getIntArray(ArrivalKeys.FROM)?.toList().orEmpty()
            val listening = arrivals.getBoolean(ArrivalKeys.LISTENING)
            val entries =
                tasks.mapIndexed { index, task ->
                    "$task:${packages.getOrNull(index)} from ${from.getOrNull(index)}"
                }
            return "listening=$listening ${entries.ifEmpty { listOf("(none)") }}"
        }
    }
}
