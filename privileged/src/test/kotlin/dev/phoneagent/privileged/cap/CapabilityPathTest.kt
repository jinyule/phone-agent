package dev.phoneagent.privileged.cap

import android.content.Intent
import android.provider.Settings
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The test harness itself: a start goes through the Proxy, PrivilegedCore and Launcher to the framework. */
@RunWith(RobolectricTestRunner::class)
class CapabilityPathTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val helper by lazy { CapabilityHarness(stateDir = folder.root) }

    @After
    fun close() {
        helper.close()
    }

    @Test
    fun aStartTravelsTheWholePath() {
        val settings =
            helper.install(
                "com.android.settings",
                filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS),
            )
        val display = helper.display()
        assertTrue("display created: $display", display >= 0)
        assertEquals(StartResult.OK, helper.core.startActivity(display, Intent(Settings.ACTION_SETTINGS), 0, false))
        val (started, _) =
            helper.bridge.atm.started
                .single()
        assertEquals(Settings.ACTION_SETTINGS, started.action)
        assertTrue(settings.packageName.isNotEmpty())
    }
}
