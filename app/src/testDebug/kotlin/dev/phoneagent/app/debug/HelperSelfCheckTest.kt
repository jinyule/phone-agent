package dev.phoneagent.app.debug

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.helper.FakeHelper
import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.privileged.PrivilegedProtocol
import dev.phoneagent.privileged.handoff.HandoffContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelperSelfCheckTest {
    @Test
    fun passesAgainstAConformingHelper() {
        val helper = FakeHelper()
        val lines = HelperSelfCheck.run(HelperState.Connected(helper, FakeHelper.PID))
        assertEquals("PASS", lines.last())
        assertTrue(lines.contains("probe -> NOT_IMPLEMENTED"))
        assertEquals("releaseAll", helper.calls.last())
    }

    @Test
    fun failsOnAWrongProtocolOrIdentity() {
        val wrongProtocol = FakeHelper(protocol = PrivilegedProtocol.VERSION + 1)
        assertTrue(HelperSelfCheck.run(HelperState.Connected(wrongProtocol, FakeHelper.PID)).last().startsWith("FAIL"))
        val wrongIdentity = FakeHelper(reportedIdentity = intArrayOf(HandoffContract.SHELL_UID, 1))
        assertTrue(HelperSelfCheck.run(HelperState.Connected(wrongIdentity, FakeHelper.PID)).last().startsWith("FAIL"))
    }

    @Test
    fun failsWithoutAHelper() {
        assertEquals("FAIL: no helper connected", HelperSelfCheck.run(HelperState.NotConnected).last())
    }

    @Test
    fun receiverIsReachableOnlyByDumpHolders() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = android.content.ComponentName(context, HelperSelfCheckReceiver::class.java)
        val info = context.packageManager.getReceiverInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
    }
}
