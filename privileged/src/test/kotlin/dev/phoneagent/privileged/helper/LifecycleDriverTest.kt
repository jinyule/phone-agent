package dev.phoneagent.privileged.helper

import android.os.Handler
import android.os.Looper
import dev.phoneagent.privileged.helper.HelperLifecycle.Event
import dev.phoneagent.privileged.helper.HelperLifecycle.Outcome
import dev.phoneagent.privileged.helper.HelperLifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The lifecycle driven on a real Handler with Robolectric's paused looper: time only moves in [advance]. */
@RunWith(RobolectricTestRunner::class)
class LifecycleDriverTest {
    private class FakeHost : LifecycleDriver.Host {
        var running = false
        var genuine = true
        var genuineChecks = 0
        val delivered = mutableListOf<String>()
        val log = mutableListOf<String>()
        var exitCode: Int? = null

        override fun deliver(nonce: String): Outcome {
            delivered += nonce
            return Outcome.Accepted(delivered.size, "${delivered.size}".repeat(64))
        }

        override fun appRunning(): Boolean = running

        override fun appGenuine(): Boolean {
            genuineChecks++
            return genuine
        }

        override fun releaseAll() {
            log += "releaseAll"
        }

        override fun exit(
            code: Int,
            why: String,
        ) {
            log += "exit $code"
            exitCode = code
        }
    }

    private val launch = "a".repeat(64)
    private val host = FakeHost()

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** Started, admitted as session 1, then the app died while still genuine (the uninstall race). */
    private fun orphaned(
        graceMs: Long,
        checkMs: Long,
    ): LifecycleDriver {
        val driver = LifecycleDriver(HelperLifecycle(launch, graceMs, checkMs), Handler(Looper.getMainLooper()), host)
        driver.post(Event.Start)
        advance(0)
        driver.postAppDied(1)
        advance(0)
        assertEquals(State.Orphaned(1, "1".repeat(64)), driver.state)
        return driver
    }

    @Test
    fun anAppUninstalledDuringTheGraceMakesTheHelperExitWhenTheGraceExpires() {
        orphaned(graceMs = 10_000, checkMs = 30_000)
        host.genuine = false
        advance(9_999)
        assertNull(host.exitCode)
        advance(1)
        assertEquals(listOf("releaseAll", "exit ${HelperLifecycle.EXIT_OK}"), host.log)
    }

    @Test
    fun anAppUninstalledAfterTheGraceMakesTheHelperExitAtTheNextPackageCheck() {
        val driver = orphaned(graceMs = 10_000, checkMs = 30_000)
        advance(10_000)
        assertEquals(State.Idle("1".repeat(64)), driver.state)
        host.genuine = false
        advance(19_999)
        assertNull("the first check is 30 s after the death", host.exitCode)
        advance(1)
        assertEquals(listOf("releaseAll", "releaseAll", "exit ${HelperLifecycle.EXIT_OK}"), host.log)
    }

    @Test
    fun aCertificateChangeWhileOrphanedMakesTheHelperExitBeforeTheGraceEnds() {
        orphaned(graceMs = 60_000, checkMs = 5_000)
        host.genuine = false
        advance(4_999)
        assertNull(host.exitCode)
        advance(1)
        assertEquals(listOf("releaseAll", "exit ${HelperLifecycle.EXIT_OK}"), host.log)
    }

    @Test
    fun aGenuineAppKeepsTheHelperWaitingAndCheckingUntilTheAppReturns() {
        val driver = orphaned(graceMs = 10_000, checkMs = 30_000)
        advance(10_000)
        val checksWhenIdle = host.genuineChecks
        advance(3 * 30_000)
        assertEquals(State.Idle("1".repeat(64)), driver.state)
        assertNull(host.exitCode)
        assertEquals("one check per interval", checksWhenIdle + 3, host.genuineChecks)

        driver.dispatch(Event.AppStarted)
        assertEquals(State.Attached(2, "2".repeat(64)), driver.state)
        val checksWhenAttached = host.genuineChecks
        host.genuine = false
        advance(2 * 30_000)
        assertEquals(
            "once the app is back only the check already scheduled fires, and it is ignored",
            checksWhenAttached + 1,
            host.genuineChecks,
        )
        assertNull(host.exitCode)
    }
}
