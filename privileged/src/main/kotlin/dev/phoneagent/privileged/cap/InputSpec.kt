/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * core/src/main/kotlin/com/wangzan101/mika/core/uistate/PressKeys.kt and
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `injectStroke` checks).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the stroke checks are a pure function returning the reason
 * a stroke is rejected, and they also bound the stroke to the display's own pixels.
 */
package dev.phoneagent.privileged.cap

/**
 * Key codes the helper injects (`android.view.KeyEvent` values; the helper has no public SDK
 * dependency on them). Navigation and editing keys only: volume, power, HOME, recents, assistant,
 * camera and media keys act on the whole phone rather than on the agent's display, so they are
 * refused even if a caller asks for them.
 */
internal object PressKeys {
    /** Allowed key codes by their `KeyEvent.KEYCODE_*` name. */
    val BY_NAME: Map<String, Int> =
        linkedMapOf(
            "KEYCODE_BACK" to 4,
            "KEYCODE_DPAD_UP" to 19,
            "KEYCODE_DPAD_DOWN" to 20,
            "KEYCODE_DPAD_LEFT" to 21,
            "KEYCODE_DPAD_RIGHT" to 22,
            "KEYCODE_DPAD_CENTER" to 23,
            "KEYCODE_TAB" to 61,
            "KEYCODE_SPACE" to 62,
            "KEYCODE_ENTER" to 66,
            "KEYCODE_DEL" to 67,
            "KEYCODE_MENU" to 82,
            "KEYCODE_PAGE_UP" to 92,
            "KEYCODE_PAGE_DOWN" to 93,
            "KEYCODE_ESCAPE" to 111,
            "KEYCODE_FORWARD_DEL" to 112,
            "KEYCODE_MOVE_HOME" to 122,
            "KEYCODE_MOVE_END" to 123,
            "KEYCODE_NUMPAD_ENTER" to 160,
        )

    private val CODES: Set<Int> = BY_NAME.values.toSet()

    fun isAllowed(keyCode: Int): Boolean = keyCode in CODES
}

/** One finger, one stroke: [xs]/[ys] in display pixels, [times] in ms from the touch down. */
internal object StrokeSpec {
    /** Most points one stroke may carry. */
    const val MAX_POINTS = 1_024

    /** Longest a stroke may take; the helper holds the Binder thread until it finishes. */
    const val MAX_MS = 10_000L

    /**
     * Why this stroke is refused, or null when it can be injected. Checks shape (parallel arrays,
     * at least a down and an up), timing (starts at 0, never goes backwards, bounded) and that
     * every point is inside [width] x [height], so a stroke can never land outside its display.
     */
    @Suppress("ReturnCount") // One explicit reason per check reads better than nested conditions.
    fun problem(
        xs: IntArray,
        ys: IntArray,
        times: LongArray,
        width: Int,
        height: Int,
    ): String? {
        val n = times.size
        if (n < 2 || xs.size != n || ys.size != n) {
            return "a stroke needs at least 2 points and xs/ys/times of equal length, " +
                "was ${xs.size}/${ys.size}/$n"
        }
        if (n > MAX_POINTS) return "a stroke may have at most $MAX_POINTS points, was $n"
        if (times[0] != 0L) return "times must start at 0, was ${times[0]}"
        if (times.last() > MAX_MS) return "a stroke may take at most $MAX_MS ms, was ${times.last()}"
        val backwards = (1 until n).firstOrNull { times[it] < times[it - 1] }
        if (backwards != null) return "times must not go backwards, point $backwards is ${times[backwards]}"
        val outside = (0 until n).firstOrNull { xs[it] !in 0 until width || ys[it] !in 0 until height }
        if (outside != null) {
            return "point $outside (${xs[outside]}, ${ys[outside]}) is outside the ${width}x$height display"
        }
        return null
    }
}
