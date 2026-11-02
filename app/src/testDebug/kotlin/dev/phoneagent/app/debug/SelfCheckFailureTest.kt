package dev.phoneagent.app.debug

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.debug.HelperSelfCheck.Request
import dev.phoneagent.app.helper.FakeHelper
import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.privileged.ProbeKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/** The display and launch self-checks when a helper call throws instead of answering. */
@RunWith(AndroidJUnit4::class)
class SelfCheckFailureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Runs [scenario] the way the receiver does; returns what the broadcast finished with, once each. */
    private fun broadcast(
        helper: FakeHelper,
        scenario: String,
    ): String {
        val finished = mutableListOf<String>()
        HelperSelfCheck.report({
            HelperSelfCheck.run(context, HelperState.Connected(helper, FakeHelper.PID), Request(scenario))
        }) {
            finished += it
        }
        return finished.single()
    }

    private fun displayThreads(): Int =
        Thread.getAllStackTraces().keys.count {
            it.name == "debug-agent-display" &&
                it.isAlive
        }

    /** True once no more display threads are alive than [before]; they stop shortly after close. */
    private fun displayThreadsBackTo(before: Int): Boolean {
        val deadline = System.currentTimeMillis() + THREAD_STOP_MS
        while (displayThreads() > before && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        return displayThreads() <= before
    }

    private fun logged(block: () -> Unit): List<String> {
        ShadowLog.clear()
        block()
        return ShadowLog.getLogsForTag("PhoneAgentSelfCheck").map { it.msg }
    }

    @Test
    fun aProbeThatThrowsFailsTheDisplayCheckByNameAndStillReleases() {
        val helper = FakeHelper().apply { onProbe = { throw IllegalStateException("probe broke") } }
        var verdict = ""
        val lines = logged { verdict = broadcast(helper, "display") }
        assertTrue(verdict, verdict.startsWith("FAIL") && verdict.contains("probe"))
        assertTrue(
            "the step names the exception: $lines",
            lines.any {
                it.startsWith("probe: FAILED") &&
                    it.contains("probe broke")
            },
        )
        assertEquals("releaseAll", helper.calls.last())
    }

    @Test
    fun aCreateDisplayThatThrowsFailsTheDisplayCheckByNameAndStillReleases() {
        val helper =
            FakeHelper().apply {
                onProbe = { Bundle().apply { putBoolean(ProbeKeys.OK, true) } }
                onCreateDisplay = { throw IllegalStateException("no display today") }
            }
        val threads = displayThreads()
        var verdict = ""
        val lines = logged { verdict = broadcast(helper, "display") }
        assertTrue(verdict, verdict.startsWith("FAIL") && verdict.contains("createDisplay"))
        assertTrue("the display's thread is stopped", displayThreadsBackTo(threads))
        assertTrue("$lines", lines.any { it.startsWith("createDisplay: FAILED") && it.contains("no display today") })
        assertEquals("releaseAll", helper.calls.last())
    }

    @Test
    fun aCreateDisplayThatThrowsFailsTheLaunchCheckByNameAndStillReleases() {
        val helper = FakeHelper().apply { onCreateDisplay = { throw IllegalStateException("no display today") } }
        val threads = displayThreads()
        val verdict = broadcast(helper, "launch")
        assertTrue(verdict, verdict.startsWith("FAIL") && verdict.contains("createDisplay"))
        assertTrue("the display's thread is stopped", displayThreadsBackTo(threads))
        assertEquals("releaseAll", helper.calls.last())
    }

    @Test
    fun aReleaseAllThatThrowsAfterTheLaunchCheckIsAFailNotACrash() {
        val helper =
            FakeHelper(failRelease = IllegalStateException("release broke")).apply {
                onCreateDisplay = { throw IllegalStateException("no display today") }
            }
        val verdict = broadcast(helper, "launch")
        assertTrue(verdict, verdict.startsWith("FAIL") && verdict.contains("releaseAll"))
    }

    private companion object {
        const val THREAD_STOP_MS = 2_000L
        const val POLL_MS = 20L
    }
}
