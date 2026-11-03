package dev.phoneagent.app.adb

import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.CommandFailure
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.PortSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** HelperState.Connected holds a Binder: Robolectric. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HelperLauncherTest {
    private val helper = FakeHelperPort()
    private val session = FakeSession()
    private val adb = FakeAdb { session }
    private val command = AdbCommand("read n; launch")

    private fun launcher() = HelperLauncher(adb, helper, command, HANDOFF_MS)

    @Test
    fun `a launch runs the command with the nonce on stdin and ends at the handoff`() =
        runTest {
            session.onRun = { helper.set(FakeHelperPort.connected(pid = 777)) }
            assertEquals(LaunchOutcome.Launched(777, PortSource.MDNS), launcher().launch())
            val (ran, stdin) = session.runs.single()
            assertEquals(command, ran)
            assertArrayEquals(HelperLaunchCommand.stdin(FakeHelperPort.NONCE.dropLast(1) + "1"), stdin)
            assertTrue(session.closed)
            assertTrue(helper.listeners.isEmpty())
        }

    @Test
    fun `the handoff may come after the command ended, within the wait`() =
        runTest {
            session.onRun = {
                launch {
                    delay(HANDOFF_MS - 1)
                    helper.set(FakeHelperPort.connected())
                }
            }
            assertTrue(launcher().launch() is LaunchOutcome.Launched)
            assertEquals(HANDOFF_MS - 1, testScheduler.currentTime)
        }

    @Test
    fun `no handoff within the wait is NoHandoff, and the session is closed`() =
        runTest {
            assertEquals(LaunchOutcome.NoHandoff(HANDOFF_MS), launcher().launch())
            assertEquals(HANDOFF_MS, testScheduler.currentTime)
            assertTrue(session.closed)
            assertTrue(helper.listeners.isEmpty())
        }

    @Test
    fun `nothing is tried with a connected helper, without pairing or with wireless debugging off`() =
        runTest {
            val connected = FakeHelperPort(FakeHelperPort.connected())
            assertEquals(LaunchOutcome.AlreadyConnected, HelperLauncher(adb, connected, command, HANDOFF_MS).launch())
            adb.isPaired = false
            assertEquals(LaunchOutcome.NotPaired, launcher().launch())
            adb.isPaired = true
            adb.wireless = false
            assertEquals(LaunchOutcome.WirelessDebuggingOff, launcher().launch())
            assertEquals(0, adb.connects)
            assertEquals(0, helper.nonces + connected.nonces)
        }

    @Test
    fun `wireless debugging that cannot be read is tried`() =
        runTest {
            adb.wireless = null
            session.onRun = { helper.set(FakeHelperPort.connected()) }
            assertTrue(launcher().launch() is LaunchOutcome.Launched)
        }

    @Test
    fun `a refused key is Unauthorized, other failed searches NoConnection, both without a nonce`() =
        runTest {
            adb.connection = { FakeAdb.failed(ConnectFailure.UNAUTHORIZED) }
            assertEquals(LaunchOutcome.Unauthorized, launcher().launch())
            for (reason in listOf(null, ConnectFailure.TLS, ConnectFailure.UNREACHABLE, ConnectFailure.TIMEOUT)) {
                val failed = FakeAdb.failed(reason)
                adb.connection = { failed }
                assertEquals("$reason", LaunchOutcome.NoConnection(failed.search), launcher().launch())
            }
            assertEquals(0, helper.nonces)
        }

    @Test
    fun `a helper connected while connecting makes the launch moot`() =
        runTest {
            adb.connection = {
                helper.set(FakeHelperPort.connected())
                session
            }
            assertEquals(LaunchOutcome.AlreadyConnected, launcher().launch())
            assertEquals(0, helper.nonces)
            assertTrue(session.runs.isEmpty())
            assertTrue(session.closed)
        }

    @Test
    fun `without a nonce nothing runs and the session is closed`() =
        runTest {
            helper.nonceRefusal = "the boot count is unknown"
            assertEquals(LaunchOutcome.NoNonce("the boot count is unknown"), launcher().launch())
            assertTrue(session.runs.isEmpty())
            assertTrue(session.closed)
            assertTrue(helper.listeners.isEmpty())
        }

    @Test
    fun `a command that fails or exits non-zero is CommandFailed, and no exit status counts as success`() =
        runTest {
            val failures =
                listOf(CommandResult.Completed(1, "", "denied"), CommandResult.Failed(CommandFailure.CLOSED, "eof"))
            for (result in failures) {
                adb.connection = { FakeSession(result = result) }
                assertEquals(LaunchOutcome.CommandFailed(result), launcher().launch())
            }
            adb.connection =
                {
                    FakeSession(
                        result = CommandResult.Completed(null, "", ""),
                    ) { helper.set(FakeHelperPort.connected()) }
                }
            assertTrue(launcher().launch() is LaunchOutcome.Launched)
        }

    @Test
    fun `a handoff wait of zero is a configuration error`() {
        val failure = runCatching { HelperLauncher(adb, helper, command, 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(helper.state is HelperState.Connected)
    }
}

private const val HANDOFF_MS = 15_000L
