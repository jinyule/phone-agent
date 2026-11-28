package dev.phoneagent.app.adapters.screen

/**
 * Tunable waits and gesture durations of [AndroidScreen], validated on construction.
 *
 * @property quietMs event silence that counts as settled (phase1 §3.3: 300 ms).
 * @property settleCapMs longest wait for the display to settle after an action (phase1 §3.3: 3 s).
 * @property launchWaitMs longest wait for a launched app to reach the front.
 * @property readbackCapMs longest wait for a text field to report what was typed before the next
 *   input fallback is tried.
 * @property pasteWaitMs longest wait for a pasted field to change before the clipboard is restored.
 * @property tapMs how long a tap gesture holds.
 * @property longPressMs how long a long-press gesture holds.
 * @property scrollSwipeMs duration of the swipe that replaces a refused scroll action.
 * @property gestureSlackMs extra time after a gesture's duration before an unanswered dispatch counts as failed.
 * @property clickUncertainMs a node click answered `false` after at least this long is taken as the
 *   platform's timeout: the press is reported as one that may have run, not as one the app
 *   refused (neither is repeated with a gesture); below the platform's 5 s interaction timeout.
 * @property screenshotWaitMs longest wait for a screenshot (the system's retry interval included);
 *   then the frame is reported as unavailable.
 * @property snapshotsKept how many recent snapshots act(on = …) can still resolve refs against.
 */
internal data class ScreenTimings(
    val quietMs: Long = 300,
    val settleCapMs: Long = 3_000,
    val launchWaitMs: Long = 5_000,
    val readbackCapMs: Long = 1_000,
    val pasteWaitMs: Long = 1_500,
    val tapMs: Long = 60,
    val longPressMs: Long = 800,
    val scrollSwipeMs: Long = 350,
    val gestureSlackMs: Long = 2_000,
    val clickUncertainMs: Long = 4_000,
    val screenshotWaitMs: Long = 10_000,
    val snapshotsKept: Int = 8,
) {
    init {
        val positive =
            mapOf(
                "quietMs" to quietMs,
                "settleCapMs" to settleCapMs,
                "launchWaitMs" to launchWaitMs,
                "readbackCapMs" to readbackCapMs,
                "pasteWaitMs" to pasteWaitMs,
                "tapMs" to tapMs,
                "longPressMs" to longPressMs,
                "scrollSwipeMs" to scrollSwipeMs,
                "gestureSlackMs" to gestureSlackMs,
                "clickUncertainMs" to clickUncertainMs,
                "screenshotWaitMs" to screenshotWaitMs,
            ).filterValues { it <= 0 }
        require(positive.isEmpty()) { "ScreenTimings must be positive: $positive" }
        require(quietMs < settleCapMs) { "quietMs ($quietMs) must be shorter than settleCapMs ($settleCapMs)" }
        require(tapMs < longPressMs) { "tapMs ($tapMs) must be shorter than longPressMs ($longPressMs)" }
        require(quietMs < clickUncertainMs) {
            "quietMs ($quietMs) must be shorter than clickUncertainMs ($clickUncertainMs)"
        }
        require(snapshotsKept >= 1) { "snapshotsKept must be >= 1, got $snapshotsKept" }
    }
}
