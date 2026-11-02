package dev.phoneagent.app.adapters.screen

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timings are checked when the port is configured, not when a wait goes wrong. */
class ScreenTimingsTest {
    private fun rejects(
        expected: String,
        timings: () -> ScreenTimings,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java) { timings() }
        assertTrue(error.message, error.message!!.contains(expected))
    }

    @Test
    fun theDefaultsAreValid() {
        ScreenTimings()
    }

    @Test
    fun waitsMustBePositive() {
        rejects("clickUncertainMs") { ScreenTimings(clickUncertainMs = 0) }
        rejects("screenshotWaitMs") { ScreenTimings(screenshotWaitMs = 0) }
        rejects("quietMs") { ScreenTimings(quietMs = -1) }
    }

    @Test
    fun aClickIsOnlyUncertainAfterMoreThanTheQuietWindow() {
        rejects("clickUncertainMs") { ScreenTimings(quietMs = 300, clickUncertainMs = 300) }
    }

    @Test
    fun orderedPairsStayOrdered() {
        rejects("settleCapMs") { ScreenTimings(quietMs = 3_000, settleCapMs = 3_000) }
        rejects("longPressMs") { ScreenTimings(tapMs = 800, longPressMs = 800) }
        rejects("snapshotsKept") { ScreenTimings(snapshotsKept = 0) }
    }
}
