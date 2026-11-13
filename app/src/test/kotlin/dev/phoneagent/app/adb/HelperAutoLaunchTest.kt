package dev.phoneagent.app.adb

import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.app.helper.HelperState.LaunchReason
import dev.phoneagent.privileged.handoff.HandoffReject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
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

    /** What the next launches return, in order; then NoHandoff. */
    private val outcomes = ArrayDeque<LaunchOutcome>()

    /**
     * When not null, every launch hands off at once and the helper lives for the next of these, in
     * order (then for good).
     */
    private var lifetimes: ArrayDeque<Long>? = null

    private fun TestScope.autoLaunch(
        helper: FakeHelperPort,
        config: LaunchConfig = lateStartup,
    ): HelperAutoLaunch {
        val failures = CoroutineExceptionHandler { _, _ -> }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + failures)
        val launch: suspend () -> LaunchOutcome = {
            starts += testScheduler.currentTime
            launchedInListener = launchedInListener || inListener
            lifetimes?.let { handOff(helper, it.removeFirstOrNull()) }
            delay(launchMs)
            failNext?.let {
                failNext = null
                throw it
            }
            outcomes.removeFirstOrNull() ?: LaunchOutcome.NoHandoff(launchMs)
        }
        return HelperAutoLaunch(helper, launch, scope, config) { testScheduler.currentTime }
    }

    private fun TestScope.handOff(
        helper: FakeHelperPort,
        lifetime: Long?,
    ) {
        helper.set(FakeHelperPort.connected())
        if (lifetime != null) {
            backgroundScope.launch {
                delay(lifetime)
                helper.needsLaunch()
            }
        }
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

    /**
     * After [refusal] the helper state stays NeedsLaunch, so only the second [AutoLaunchSlot] call
     * (startIfPaired after the user paired again) asks for the next launch.
     */
    private fun TestScope.pairingAgainAfter(refusal: LaunchOutcome) {
        val helper = FakeHelperPort()
        val slot = AutoLaunchSlot()
        var made = 0
        assertTrue(
            slot.startOrRequest {
                made++
                autoLaunch(helper)
            },
        )
        outcomes += refusal
        helper.needsLaunch()
        advanceTimeBy(2_000)
        assertEquals(listOf(0L), starts)
        assertFalse(slot.startOrRequest { error("a second instance") })
        advanceTimeBy(INTERVAL_MS - 2_000)
        assertEquals(listOf(0L), starts)
        runCurrent()
        assertEquals(listOf(0L, INTERVAL_MS), starts)
        advanceTimeBy(5 * INTERVAL_MS)
        assertEquals(listOf(0L, INTERVAL_MS), starts)
        assertEquals(1, made)
    }

    @Test
    fun `pairing again after NotPaired launches once more, the interval after the first`() =
        runTest { pairingAgainAfter(LaunchOutcome.NotPaired(NotPairedReason.IDENTITY_REPLACED)) }

    @Test
    fun `pairing again after Unauthorized launches once more, the interval after the first`() =
        runTest { pairingAgainAfter(LaunchOutcome.Unauthorized) }

    @Test
    fun `without pairing again nothing follows a refused launch`() =
        runTest {
            val helper = FakeHelperPort()
            AutoLaunchSlot().startOrRequest { autoLaunch(helper) }
            outcomes += LaunchOutcome.Unauthorized
            helper.needsLaunch()
            advanceTimeBy(5 * INTERVAL_MS)
            assertEquals(listOf(0L), starts)
        }

    /** Early exits within 30 s; then 10, 20 and 40 s between starts; no launches after 5. */
    private val exiting =
        lateStartup.copy(
            earlyExitWindowMs = 30_000,
            earlyExitBackoffMs = listOf(10_000, 20_000, 40_000),
            maxEarlyExits = 5,
        )

    @Test
    fun `helpers that die soon after their handoff are launched ever later, none at the limit, again once paired`() =
        runTest {
            lifetimes = ArrayDeque(List(5) { 2_000L })
            val helper = FakeHelperPort()
            val slot = AutoLaunchSlot()
            slot.startOrRequest { autoLaunch(helper, exiting) }
            helper.needsLaunch()
            advanceTimeBy(130_000)
            runCurrent()
            // Deaths at 2, 12, 32, 72 and 112 s; the fifth is the limit.
            assertEquals(listOf(0L, 10_000L, 30_000L, 70_000L, 110_000L), starts)
            assertTrue(ShadowLog.getLogs().any { it.msg == "helper launch: TooManyEarlyExits(exits=5)" })
            slot.startOrRequest { error("a second instance") }
            runCurrent()
            assertEquals(listOf(0L, 10_000L, 30_000L, 70_000L, 110_000L, 130_000L), starts)
        }

    @Test
    fun `a helper that outlives the window starts the early-exit count over`() =
        runTest {
            lifetimes = ArrayDeque(listOf(2_000L, 2_000L, 35_000L, 2_000L))
            val helper = FakeHelperPort()
            autoLaunch(helper, exiting).start()
            helper.needsLaunch()
            advanceTimeBy(110_000)
            // Deaths at 2 and 12 s, at 65 s (after the window), then at 67 s: one early exit again.
            assertEquals(listOf(0L, 10_000L, 30_000L, 65_000L, 75_000L), starts)
        }

    @Test
    fun `a stale helper turned away is not an early exit`() =
        runTest {
            lifetimes = ArrayDeque(List(5) { 2_000L })
            val helper = FakeHelperPort()
            autoLaunch(helper, exiting.copy(maxEarlyExits = 1)).start()
            val stale = HelperState.NeedsLaunch(LaunchReason.Stale(HandoffReject.STALE_APK), 99)
            helper.set(FakeHelperPort.connected())
            advanceTimeBy(1_000)
            helper.set(stale)
            runCurrent()
            assertEquals(listOf(1_000L), starts)
            advanceTimeBy(5_000)
            // That helper's death 2 s after its handoff is the first early exit, and the limit.
            assertEquals(listOf(1_000L), starts)
            assertTrue(ShadowLog.getLogs().any { it.msg == "helper launch: TooManyEarlyExits(exits=1)" })
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
        LaunchConfig(
            orphanResumeMs = 0,
            handoffTimeoutMs = 1,
            minLaunchIntervalMs = 0,
            earlyExitWindowMs = 1,
            earlyExitBackoffMs = listOf(0),
            maxEarlyExits = 1,
        )
        LaunchConfig(
            orphanResumeMs = max,
            handoffTimeoutMs = max,
            minLaunchIntervalMs = max,
            earlyExitWindowMs = max,
            earlyExitBackoffMs = listOf(max, max),
            maxEarlyExits = LaunchConfig.MAX_EARLY_EXITS,
        )
        val invalid: List<() -> LaunchConfig> =
            listOf(
                { LaunchConfig(orphanResumeMs = -1) },
                { LaunchConfig(orphanResumeMs = max + 1) },
                { LaunchConfig(handoffTimeoutMs = 0) },
                { LaunchConfig(handoffTimeoutMs = max + 1) },
                { LaunchConfig(minLaunchIntervalMs = -1) },
                { LaunchConfig(minLaunchIntervalMs = max + 1) },
                { LaunchConfig(earlyExitWindowMs = 0) },
                { LaunchConfig(earlyExitWindowMs = max + 1) },
                { LaunchConfig(earlyExitBackoffMs = emptyList()) },
                { LaunchConfig(minLaunchIntervalMs = 10_000, earlyExitBackoffMs = listOf(9_999)) },
                { LaunchConfig(earlyExitBackoffMs = listOf(10_000, max + 1)) },
                { LaunchConfig(earlyExitBackoffMs = listOf(30_000, 20_000)) },
                { LaunchConfig(maxEarlyExits = 0) },
                { LaunchConfig(maxEarlyExits = LaunchConfig.MAX_EARLY_EXITS + 1) },
            )
        for ((index, build) in invalid.withIndex()) {
            assertThrows("case $index", IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun `the interval after early exits follows the backoff, its last entry for all later ones`() {
        val config = LaunchConfig(minLaunchIntervalMs = 5_000, earlyExitBackoffMs = listOf(10_000, 30_000))
        assertEquals(listOf(5_000L, 10_000L, 30_000L, 30_000L), (0..3).map(config::intervalAfter))
    }
}

private const val ORPHAN_MS = 5_000L
private const val INTERVAL_MS = 10_000L
