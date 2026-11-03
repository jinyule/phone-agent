package dev.phoneagent.app.adb

import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.app.helper.HelperState.LaunchReason
import dev.phoneagent.privileged.handoff.HandoffReject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** In virtual time; HelperState.Connected holds a Binder: Robolectric. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HelperAutoLaunchTest {
    private val config =
        LaunchConfig(orphanResumeMs = ORPHAN_MS, handoffTimeoutMs = 1_000, minLaunchIntervalMs = INTERVAL_MS)

    /** For tests of requests: the start-up check comes after everything they look at. */
    private val lateStartup = config.copy(orphanResumeMs = LaunchConfig.MAX_WAIT_MS)

    /** Start times of the launches, in virtual ms. */
    private val starts = mutableListOf<Long>()
    private var launchMs = 0L
    private var inListener = false
    private var launchedInListener = false
    private var failNext: Exception? = null

    private fun TestScope.autoLaunch(
        helper: FakeHelperPort,
        config: LaunchConfig = lateStartup,
    ): HelperAutoLaunch {
        val failures = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + failures)
        val launch: suspend () -> LaunchOutcome = {
            starts += testScheduler.currentTime
            launchedInListener = launchedInListener || inListener
            delay(launchMs)
            failNext?.let {
                failNext = null
                throw it
            }
            LaunchOutcome.NoHandoff(launchMs)
        }
        return HelperAutoLaunch(helper, launch, scope, config) { testScheduler.currentTime }
    }

    private fun FakeHelperPort.needsLaunch() {
        inListener = true
        set(FakeHelperPort.died())
        inListener = false
    }

    @Test
    fun `at process start a helper is launched only after the orphan wait`() =
        runTest {
            autoLaunch(FakeHelperPort(), config).start()
            advanceTimeBy(ORPHAN_MS)
            assertEquals(emptyList<Long>(), starts)
            runCurrent()
            assertEquals(listOf(ORPHAN_MS), starts)
        }

    @Test
    fun `an orphan that hands off within the wait makes the launch moot`() =
        runTest {
            val helper = FakeHelperPort()
            autoLaunch(helper, config).start()
            advanceTimeBy(ORPHAN_MS - 1)
            helper.set(FakeHelperPort.connected())
            advanceTimeBy(10 * INTERVAL_MS)
            assertEquals(emptyList<Long>(), starts)
        }

    @Test
    fun `a helper turned away before start is relaunched at once`() =
        runTest {
            val stale = HelperState.NeedsLaunch(LaunchReason.Stale(HandoffReject.STALE_APK), 99)
            autoLaunch(FakeHelperPort(stale)).start()
            runCurrent()
            assertEquals(listOf(0L), starts)
        }

    @Test
    fun `no helper after the orphan wait is launched again, also after a failed launch`() =
        runTest {
            autoLaunch(FakeHelperPort(FakeHelperPort.died()), config).start()
            advanceTimeBy(10 * INTERVAL_MS)
            assertEquals(listOf(0L, INTERVAL_MS), starts)
        }

    @Test
    fun `NeedsLaunch launches at once, but never inside the listener`() =
        runTest {
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            helper.needsLaunch()
            assertEquals(emptyList<Long>(), starts)
            runCurrent()
            assertEquals(listOf(0L), starts)
            assertFalse(launchedInListener)
        }

    @Test
    fun `requests during a launch make one more run, at least the interval after the first`() =
        runTest {
            launchMs = 3_000
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            helper.needsLaunch()
            advanceTimeBy(1_000)
            helper.needsLaunch()
            helper.needsLaunch()
            advanceTimeBy(10 * INTERVAL_MS)
            assertEquals(listOf(0L, INTERVAL_MS), starts)
        }

    @Test
    fun `a request after a launch ended waits out the interval`() =
        runTest {
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            helper.needsLaunch()
            advanceTimeBy(2_000)
            helper.needsLaunch()
            advanceTimeBy(INTERVAL_MS - 2_000)
            assertEquals(listOf(0L), starts)
            runCurrent()
            assertEquals(listOf(0L, INTERVAL_MS), starts)
        }

    @Test
    fun `a queued run finds the helper connected and does nothing`() =
        runTest {
            launchMs = 3_000
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            helper.needsLaunch()
            advanceTimeBy(1_000)
            helper.needsLaunch()
            helper.set(FakeHelperPort.connected())
            advanceTimeBy(10 * INTERVAL_MS)
            assertEquals(listOf(0L), starts)
        }

    @Test
    fun `a launch that throws does not stop later ones`() =
        runTest {
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            for (failure in listOf(IOException("key store"), IllegalStateException("bug"))) {
                failNext = failure
                helper.needsLaunch()
                advanceTimeBy(INTERVAL_MS)
            }
            helper.needsLaunch()
            advanceTimeBy(INTERVAL_MS)
            assertEquals(listOf(0L, INTERVAL_MS, 2 * INTERVAL_MS), starts)
        }

    @Test
    fun `a launch that throws drops the requests queued behind it`() =
        runTest {
            launchMs = 3_000
            val helper = FakeHelperPort()
            autoLaunch(helper).start()
            failNext = IllegalStateException("bug")
            helper.needsLaunch()
            advanceTimeBy(1_000)
            helper.needsLaunch()
            advanceTimeBy(2 * INTERVAL_MS)
            assertEquals(listOf(0L), starts)
            val next = testScheduler.currentTime
            helper.needsLaunch()
            advanceTimeBy(2 * INTERVAL_MS)
            assertEquals(listOf(0L, next), starts)
        }

    @Test
    fun `closing stops both triggers`() =
        runTest {
            val helper = FakeHelperPort()
            autoLaunch(helper).start().close()
            helper.needsLaunch()
            advanceTimeBy(10 * INTERVAL_MS)
            assertEquals(emptyList<Long>(), starts)
            assertEquals(0, helper.listeners.size)
        }

    @Test
    fun `launch timing is validated`() {
        val max = LaunchConfig.MAX_WAIT_MS
        LaunchConfig(orphanResumeMs = 0, handoffTimeoutMs = 1, minLaunchIntervalMs = 0)
        LaunchConfig(orphanResumeMs = max, handoffTimeoutMs = max, minLaunchIntervalMs = max)
        val invalid: List<() -> LaunchConfig> =
            listOf(
                { LaunchConfig(orphanResumeMs = -1) },
                { LaunchConfig(orphanResumeMs = max + 1) },
                { LaunchConfig(handoffTimeoutMs = 0) },
                { LaunchConfig(handoffTimeoutMs = max + 1) },
                { LaunchConfig(minLaunchIntervalMs = -1) },
                { LaunchConfig(minLaunchIntervalMs = max + 1) },
            )
        for (build in invalid) assertThrows(IllegalArgumentException::class.java) { build() }
    }
}

private const val ORPHAN_MS = 5_000L
private const val INTERVAL_MS = 10_000L
