package dev.phoneagent.privileged.cap

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import dev.phoneagent.privileged.ProbeKeys
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The probe's lock step through the Proxy, PrivilegedCore and Prober: a lock state that cannot be
 * read is named and fails the probe, and the launch path reads it the same way.
 */
@RunWith(RobolectricTestRunner::class)
class ProbeLockTest {
    @get:Rule
    val folder = TemporaryFolder()
    private var helper: CapabilityHarness? = null

    /** A harness on which every other probe step works, so `ok` depends on the lock step alone. */
    private fun helper(): CapabilityHarness =
        CapabilityHarness(stateDir = folder.root).also { h ->
            helper = h
            h.bridge.atm.tasks += CapabilityHarness.task(TASK, MAIN, ComponentName("com.example.home", "Home"))
            h.bridge.atm.acceptsListener = true
            h.install("com.android.settings", filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS))
        }

    @After
    fun close() {
        helper?.close()
    }

    private class Probed(
        val steps: Map<String, String>,
        val ok: Boolean,
    )

    private fun probe(h: CapabilityHarness): Probed {
        val result = h.core.probe()
        val steps = result.getStringArray(ProbeKeys.STEPS).orEmpty()
        val outcomes = result.getStringArray(ProbeKeys.RESULTS).orEmpty()
        return Probed(steps.zip(outcomes).toMap(), result.getBoolean(ProbeKeys.OK))
    }

    private fun start(h: CapabilityHarness): Int =
        h.core.startActivity(h.display(), Intent(Settings.ACTION_SETTINGS), 0, false)

    @Test
    fun aReadableLockStateIsOkWhicheverItIs() {
        val h = helper()
        for (locked in listOf(false, true)) {
            h.bridge.wm.keyguardLocked = locked
            val probed = probe(h)
            assertEquals("locked=$locked: ${probed.steps}", ProbeKeys.RESULT_OK, probed.steps[ProbeKeys.Step.LOCK])
            assertTrue("locked=$locked: ${probed.steps}", probed.ok)
        }
    }

    /** The probe names the lock step and is not ok; startActivity treats the phone as locked. */
    private fun assertUnknownLock(
        h: CapabilityHarness,
        why: String,
    ) {
        val probed = probe(h)
        val lock = probed.steps[ProbeKeys.Step.LOCK].orEmpty()
        assertTrue("${probed.steps}", lock.contains("isKeyguardLocked") && lock.contains(why))
        assertFalse("${probed.steps}", probed.ok)
        assertEquals(
            "every other step worked",
            listOf(ProbeKeys.Step.LOCK),
            probed.steps
                .filterValues { it != ProbeKeys.RESULT_OK }
                .keys
                .toList(),
        )
        assertEquals(StartResult.LOCKED, start(h))
    }

    @Test
    fun aLockStateGetterThatThrowsFailsTheProbe() {
        val h = helper()
        h.bridge.wm.keyguardLocked = null
        assertUnknownLock(h, "failed through reflection")
    }

    @Test
    fun aRomWithoutTheLockStateGetterFailsTheProbe() {
        val h = helper()
        h.bridge.windowManagerOverride = WindowManagerWithoutKeyguard(h.bridge.wm)
        assertUnknownLock(h, "missing")
    }

    @Test
    fun aLockStateThatIsNoBooleanFailsTheProbe() {
        val h = helper()
        h.bridge.wm.keyguardAnswer = "locked"
        assertUnknownLock(h, "returned no boolean")
    }

    private companion object {
        const val TASK = 7
        const val MAIN = 0
    }
}
