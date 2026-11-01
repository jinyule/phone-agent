package dev.phoneagent.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.os.RemoteException
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
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HelperSelfCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun handoff(state: HelperState) = HelperSelfCheck.run(context, state, HelperSelfCheck.Request("handoff"))

    @Test
    fun passesAgainstAConformingHelper() {
        val helper = FakeHelper()
        val lines = handoff(HelperState.Connected(helper, FakeHelper.PID))
        assertEquals(lines.toString(), "PASS", lines.last())
        assertEquals("releaseAll", helper.calls.last())
    }

    @Test
    fun failsOnAWrongProtocolOrIdentity() {
        val wrongProtocol = FakeHelper(protocol = PrivilegedProtocol.VERSION + 1)
        assertTrue(handoff(HelperState.Connected(wrongProtocol, FakeHelper.PID)).last().startsWith("FAIL"))
        val wrongIdentity = FakeHelper(reportedIdentity = intArrayOf(HandoffContract.SHELL_UID, 1))
        assertTrue(handoff(HelperState.Connected(wrongIdentity, FakeHelper.PID)).last().startsWith("FAIL"))
    }

    @Test
    fun aHelperCallThatThrowsIsAFailNamingTheCallAndReleaseAllIsStillTried() {
        val broken =
            listOf(
                "protocolVersion" to FakeHelper().apply { failProtocol = IllegalStateException("bad protocol") },
                "identity" to FakeHelper(failIdentity = SecurityException("bad identity")),
                "releaseAll" to FakeHelper(failRelease = IllegalStateException("bad release")),
                "releaseAll" to FakeHelper(failRelease = RemoteException("release died")),
            )
        for ((call, helper) in broken) {
            val last = handoff(HelperState.Connected(helper, FakeHelper.PID)).last()
            assertTrue("$call: $last", last.startsWith("FAIL") && last.contains(call))
            assertEquals(call, "releaseAll", helper.calls.last())
        }
    }

    @Test
    fun theSummaryIsReportedOnceAlsoWhenTheCheckItselfThrows() {
        val finished = mutableListOf<String>()

        HelperSelfCheck.report({ handoff(HelperState.NotConnected) }) { finished += it }
        HelperSelfCheck.report({ error("the client broke") }) { finished += it }

        assertEquals(2, finished.size)
        assertEquals("FAIL: no helper connected", finished[0])
        assertTrue(finished[1], finished[1].startsWith("FAIL") && finished[1].contains("the client broke"))
    }

    @Test
    fun theReceiverFinishesTheOrderedBroadcastWithTheSummary() {
        val intent = Intent().setComponent(android.content.ComponentName(context, HelperSelfCheckReceiver::class.java))
        val result = AtomicReference<String?>()
        val resultReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    result.set(resultData ?: "finished without data")
                }
            }

        context.sendOrderedBroadcast(intent, null, resultReceiver, null, 0, null, null)

        // The check runs on its own thread; the result receiver runs on the main looper once it finishes.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (result.get() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            Thread.yield()
        }
        assertEquals("FAIL: no helper connected", result.get())
    }

    @Test
    fun failsWithoutAHelper() {
        assertEquals("FAIL: no helper connected", handoff(HelperState.NotConnected).last())
    }

    @Test
    fun anUnknownScenarioIsRejected() {
        val connected = HelperState.Connected(FakeHelper(), FakeHelper.PID)
        val lines = HelperSelfCheck.run(context, connected, HelperSelfCheck.Request("nope"))
        assertTrue(lines.last(), lines.last().startsWith("FAIL: scenario"))
    }

    @Test
    fun receiverIsReachableOnlyByDumpHolders() {
        val component = android.content.ComponentName(context, HelperSelfCheckReceiver::class.java)
        val info = context.packageManager.getReceiverInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val STEP_MS = 10L
    }
}
