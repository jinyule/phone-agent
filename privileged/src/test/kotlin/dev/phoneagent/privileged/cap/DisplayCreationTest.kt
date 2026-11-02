package dev.phoneagent.privileged.cap

import android.hardware.display.DisplayManager
import dev.phoneagent.privileged.DisplayResult
import dev.phoneagent.privileged.ProbeKeys
import org.junit.After
import org.junit.Assert.assertEquals
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
