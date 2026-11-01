package dev.phoneagent.app.debug

import android.content.Intent
import android.provider.Settings
import dev.phoneagent.privileged.IPrivilegedCore

/**
 * Debug-only check of what the helper refuses to start, run after a script put the settings app on
 * the main screen:
 *
 * ```
 * adb shell am start -a android.settings.SETTINGS --display 0
 * adb shell am broadcast -n dev.phoneagent/…HelperSelfCheckReceiver \
 *     --es scenario launch --ei expect 1
 * ```
 *
 * `expect` is the `StartResult` the caller expects, so the device run asserts the behaviour rather
 * than just printing it: 1 for an app the user has visible, 4 for a cold start downgraded because
 * the app has tasks elsewhere.
 */
internal class LaunchGateSelfCheck(
    private val core: IPrivilegedCore,
    private val expected: Int,
    private val cold: Boolean,
) {
    private val steps = SelfCheckSteps()

    fun run(): List<String> {
        val display = DebugAgentDisplay(core, WIDTH, HEIGHT, DPI)
        try {
            if (steps.check("createDisplay", display.displayId >= 0, "id ${display.displayId}")) {
                start(display.displayId)
            }
        } finally {
            display.close()
            core.releaseAll()
        }
        return steps.lines()
    }

    private fun start(displayId: Int) {
        steps.note("main screen top task ${core.topTask(MAIN_DISPLAY)}")
        val result = core.startActivity(displayId, Intent(Settings.ACTION_SETTINGS), USER, cold)
        steps.check("startActivity(cold=$cold)", result == expected, "StartResult $result, expected $expected")
        val onAgentDisplay = core.topTask(displayId)
        val started = expected == STARTED || expected == COLD_IGNORED
        steps.check(
            if (started) "started on the agent display" else "nothing started on the agent display",
            (onAgentDisplay > 0) == started,
            "top task $onAgentDisplay",
        )
    }

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 2_340
        const val DPI = 440
        const val MAIN_DISPLAY = 0
        const val USER = 0
        const val STARTED = 0
        const val COLD_IGNORED = 4
    }
}
