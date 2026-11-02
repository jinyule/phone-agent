package dev.phoneagent.privileged.cap

import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.KeyEvent
import dev.phoneagent.privileged.DisplayResult
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

/** createDisplay and probe through the Proxy, PrivilegedCore, VirtualDisplays and Prober. */
@RunWith(RobolectricTestRunner::class)
class DisplayCreationTest {
    @get:Rule
    val folder = TemporaryFolder()
    private var helper: CapabilityHarness? = null

    private fun helper(sdkInt: Int): CapabilityHarness = CapabilityHarness(sdkInt, folder.root).also { helper = it }

    @After
    fun close() {
        helper?.close()
    }

    private fun agentDisplays(h: CapabilityHarness): List<String> =
        h.context
            .getSystemService(DisplayManager::class.java)
            .displays
            .map { it.name }
            .filter { it.startsWith("phoneagent") }

    private fun probe(h: CapabilityHarness): Map<String, String> {
        val result = h.core.probe()
        val steps = result.getStringArray(ProbeKeys.STEPS).orEmpty()
        val outcomes = result.getStringArray(ProbeKeys.RESULTS).orEmpty()
        return steps.zip(outcomes).toMap()
    }

    @Test
    fun aDisplayWhoseKeyboardWouldOpenOnTheUsersScreenFailsAndIsReleased() {
        val h = helper(36)
        h.bridge.wm.imeAccepted = false
        assertEquals(DisplayResult.FAILED, h.display())
        assertEquals("the display was released again", emptyList<String>(), agentDisplays(h))
    }

    @Test
    fun aRefusedDisplayWhoseReleaseFailsIsNotUsableAndReleaseAllRetriesIt() {
        val h = helper(36)
        h.install("com.android.settings", filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS))
        h.bridge.wm.imeAccepted = false
        h.releaseFails = true

        assertEquals(DisplayResult.FAILED, h.display())
        val id =
            h.context
                .getSystemService(DisplayManager::class.java)
                .displays
                .single { it.name.startsWith("phoneagent") }
                .displayId
        assertEquals("the release was tried and failed", listOf(id), h.releaseAttempts)

        h.bridge.wm.imeAccepted = true
        assertEquals(StartResult.REFUSED, h.core.startActivity(id, Intent(Settings.ACTION_SETTINGS), 0, false))
        assertFalse("key", h.core.injectKey(id, KeyEvent.KEYCODE_BACK))
        assertFalse("stroke", h.core.injectStroke(id, intArrayOf(10, 20), intArrayOf(10, 20), longArrayOf(0, 50)))
        assertEquals("nothing started", emptyList<Pair<Intent, Int>>(), h.bridge.atm.started)
        assertEquals("nothing injected", 0, h.bridge.input.injected)

        h.releaseFails = false
        h.core.releaseAll()
        assertEquals("releaseAll tried again", listOf(id, id), h.releaseAttempts)
        assertEquals(emptyList<String>(), agentDisplays(h))
        h.core.releaseAll()
        assertEquals("released, so not tried a third time", listOf(id, id), h.releaseAttempts)
    }

    /**
     * A display whose IME policy cannot be read back is not verified: it fails and is released, and
     * the probe says why.
     */
    private fun assertUnverifiedImeFails(h: CapabilityHarness) {
        assertEquals(DisplayResult.FAILED, h.display())
        assertEquals("the display was released again", emptyList<String>(), agentDisplays(h))
        val steps = probe(h)
        val ime = steps[ProbeKeys.Step.IME].orEmpty()
        assertTrue("$steps", ime != ProbeKeys.RESULT_OK && ime.contains("getDisplayImePolicy"))
        assertEquals("the probe released its display too", emptyList<String>(), agentDisplays(h))
    }

    @Test
    fun aDisplayWhoseImePolicyGetterThrowsFails() {
        val h = helper(36)
        h.bridge.wm.imeGetterFails = true
        assertUnverifiedImeFails(h)
    }

    @Test
    fun aDisplayOnARomWithoutTheImePolicyGetterFails() {
        val h = helper(36)
        h.bridge.windowManagerOverride = WindowManagerWithoutImeGetter(h.bridge.wm)
        assertUnverifiedImeFails(h)
    }

    /** The probe's display step for a display whose keyboard would open on the user's screen. */
    private fun refusedDisplayReason(releaseFails: Boolean): String {
        val h = helper(36)
        h.bridge.wm.imeAccepted = false
        h.releaseFails = releaseFails
        return probe(h)[ProbeKeys.Step.DISPLAY].orEmpty()
    }

    @Test
    fun theProbeSaysWhetherARefusedDisplayWasReleasedOrWaitsForCleanup() {
        val released = refusedDisplayReason(releaseFails = false)
        assertTrue(released, released.contains("was released"))
        close()
        val waiting = refusedDisplayReason(releaseFails = true)
        assertTrue(waiting, waiting.contains("could not be released and waits for cleanup by releaseAll"))
        assertFalse(waiting, waiting.contains("was released"))
    }

    @Test
    fun belowApi34NoDisplayIsCreated() {
        val h = helper(33)
        assertEquals(DisplayResult.REFUSED, h.display())
        assertEquals(emptyList<String>(), agentDisplays(h))
    }

    @Test
    fun theProbeSaysBackgroundDisplaysNeedApi34BeforeCreatingOne() {
        val h = helper(33)
        val display = probe(h)[ProbeKeys.Step.DISPLAY].orEmpty()
        assertTrue(display, display.contains("API 34"))
        assertEquals(emptyList<String>(), agentDisplays(h))
    }

    @Test
    fun theProbeStillSaysWhyTheKeyboardIsNotLocal() {
        val h = helper(36)
        h.bridge.wm.imeAccepted = false
        val steps = probe(h)
        val ime = steps[ProbeKeys.Step.IME].orEmpty()
        assertTrue("$steps", ime.contains("IME policy"))
        assertTrue("$steps", steps[ProbeKeys.Step.DISPLAY] != ProbeKeys.RESULT_OK)
    }
}
