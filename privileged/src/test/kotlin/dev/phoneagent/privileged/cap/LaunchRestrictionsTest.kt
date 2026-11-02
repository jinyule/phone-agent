package dev.phoneagent.privileged.cap

import android.content.Intent
import android.provider.Settings
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What `startActivity` refuses, through the Proxy, PrivilegedCore and Launcher. */
@RunWith(RobolectricTestRunner::class)
class LaunchRestrictionsTest {
    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness
    private var display = -1

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        helper.install("com.android.settings", filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS))
        display = helper.display()
        assertTrue("display created: $display", display >= 0)
    }

    @After
    fun close() {
        helper.close()
    }

    private fun start(intent: Intent): Int = helper.core.startActivity(display, intent, 0, false)

    private fun assertNothingStarted() {
        assertEquals("nothing reached the activity manager", emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
        assertEquals("no command ran", emptyList<List<String>>(), helper.commands.commands)
    }

    @Test
    fun nothingStartsWhileThePhoneIsLocked() {
        helper.bridge.wm.keyguardLocked = true
        assertEquals(StartResult.LOCKED, start(Intent(Settings.ACTION_SETTINGS)))
        assertNothingStarted()
    }

    @Test
    fun aLockStateThatCannotBeReadCountsAsLocked() {
        helper.bridge.wm.keyguardLocked = null
        assertEquals(StartResult.LOCKED, start(Intent(Settings.ACTION_SETTINGS)))
        assertNothingStarted()
    }

    @Test
    fun anUnlockedPhoneStillStarts() {
        helper.bridge.wm.keyguardLocked = false
        assertEquals(StartResult.OK, start(Intent(Settings.ACTION_SETTINGS)))
    }
}
