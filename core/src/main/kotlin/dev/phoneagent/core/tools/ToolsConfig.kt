package dev.phoneagent.core.tools

/**
 * Tunables of the phase-1 tools; validated on construction. Timeouts are each tool's
 * `Tool.timeoutMs` (the single timeout source); time spent waiting for the user is not counted.
 *
 * @property stableQuietMs `phone_wait condition=stable`: the screen counts as settled after this
 *   long without a change.
 * @property waitReadIntervalMs `phone_wait`: appear/disappear read the screen once per this
 *   interval (a change is seen within one interval, a busy screen costs no more reads); after a
 *   change, stable looks again no sooner than this.
 * @property observeTimeoutMs `phone_observe` (tree, OCR and frame).
 * @property appsTimeoutMs `phone_apps`.
 * @property waitTimeoutMs `phone_wait`; at least the longest wait the schema allows plus
 *   [WAIT_MARGIN_MS] for the read at the deadline (tree and OCR), so a condition that holds then is seen.
 * @property actionTimeoutMs `phone_tap`, `phone_scroll`, `phone_swipe`, `phone_key` (act, settle, read back).
 * @property typeTimeoutMs `phone_type` (several input methods, each read back).
 * @property launchTimeoutMs `phone_launch` and `phone_intent` (cold app starts).
 * @property userTimeoutMs `ask_user`, `hand_to_user`, `request_confirmation`, not counting the user's time.
 * @property controlTimeoutMs `end_not_done`.
 */
public data class ToolsConfig(
    val stableQuietMs: Long = 800,
    val waitReadIntervalMs: Long = 250,
    val observeTimeoutMs: Long = 20_000,
    val appsTimeoutMs: Long = 10_000,
    val waitTimeoutMs: Long = PhoneWaitTool.MAX_WAIT_MS + WAIT_MARGIN_MS,
    val actionTimeoutMs: Long = 20_000,
    val typeTimeoutMs: Long = 30_000,
    val launchTimeoutMs: Long = 30_000,
    val userTimeoutMs: Long = 30_000,
    val controlTimeoutMs: Long = 5_000,
) {
    init {
        require(stableQuietMs in MIN_QUIET_MS..PhoneWaitTool.MAX_WAIT_MS) {
            "stableQuietMs must be in $MIN_QUIET_MS..${PhoneWaitTool.MAX_WAIT_MS}, got $stableQuietMs"
        }
        require(waitReadIntervalMs in MIN_READ_INTERVAL_MS..MAX_READ_INTERVAL_MS) {
            "waitReadIntervalMs must be in $MIN_READ_INTERVAL_MS..$MAX_READ_INTERVAL_MS, got $waitReadIntervalMs"
        }
        require(waitTimeoutMs >= PhoneWaitTool.MAX_WAIT_MS + WAIT_MARGIN_MS) {
            "waitTimeoutMs must leave $WAIT_MARGIN_MS ms after the longest wait (${PhoneWaitTool.MAX_WAIT_MS} ms) " +
                "for the last read, got $waitTimeoutMs"
        }
        val timeouts =
            listOf(
                observeTimeoutMs,
                appsTimeoutMs,
                actionTimeoutMs,
                typeTimeoutMs,
                launchTimeoutMs,
                userTimeoutMs,
                controlTimeoutMs,
            )
        require(timeouts.all { it > 0 }) { "Tool timeouts must be > 0, got $timeouts" }
    }

    private companion object {
        const val MIN_QUIET_MS = 100L
        const val MIN_READ_INTERVAL_MS = 50L
        const val MAX_READ_INTERVAL_MS = 2_000L

        /** Room after the longest wait for the read at the deadline. */
        const val WAIT_MARGIN_MS = 15_000L
    }
}
