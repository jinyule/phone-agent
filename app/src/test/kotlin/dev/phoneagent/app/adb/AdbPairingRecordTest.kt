package dev.phoneagent.app.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.AdbEndpoint
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.ConnectResult
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairedDevice
import dev.phoneagent.core.adb.PairingFailure
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdbPairingRecordTest {
    private val prefs =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
            "test_builtin_adb",
            Context.MODE_PRIVATE,
        )
    private val record = AdbPairingRecord(prefs)

    private fun failed(reason: ConnectFailure?) =
        PortSearchResult.Failed(
            reason?.let { ConnectResult.Failed(it, "x") },
            listOf(AdbEndpoint("127.0.0.1", 5)),
            null,
        )

    @Test
    fun `a pairing is recorded and survives a new record on the same preferences`() {
        assertFalse(record.isPaired)
        record.pairingEnded(PairingResult.Failed(PairingFailure.WRONG_CODE, "x"))
        assertFalse(record.isPaired)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
        assertTrue(AdbPairingRecord(prefs).isPaired)
    }

    @Test
    fun `a created or replaced identity drops the pairing, a stored one keeps it`() {
        for (origin in IdentityLoad.Origin.entries) {
            record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
            record.identityLoaded(origin)
            assertEquals("$origin", origin == IdentityLoad.Origin.STORED, record.isPaired)
        }
    }

    @Test
    fun `a search that ended on UNAUTHORIZED keeps the pairing and records the refusal`() {
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        val next = AdbPairingRecord(prefs)
        assertTrue(next.isPaired)
        assertTrue(next.lastRefused)
    }

    @Test
    fun `other failed searches record no refusal and keep the cached port`() {
        for (reason in listOf(null) + ConnectFailure.entries - ConnectFailure.UNAUTHORIZED) {
            record.connectionEnded(PORT, launched)
            record.searchEnded(failed(reason))
            assertFalse("$reason", record.lastRefused)
            assertEquals("$reason", PORT, record.portCache.load())
        }
    }

    @Test
    fun `a launch that connected clears the refusal, whatever its outcome`() {
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        record.connectionEnded(PORT, LaunchOutcome.AlreadyConnected)
        assertFalse(AdbPairingRecord(prefs).lastRefused)
    }

    @Test
    fun `a pairing that succeeds clears the refusal, one that fails keeps it`() {
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        record.pairingEnded(PairingResult.Failed(PairingFailure.WRONG_CODE, "x"))
        assertTrue(record.lastRefused)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
        assertFalse(AdbPairingRecord(prefs).lastRefused)
    }

    @Test
    fun `after a refusal the next launch connects again`() =
        runTest {
            record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
            var connects = 0
            val adb =
                object : HelperLauncher.Adb {
                    override val isPaired get() = record.isPaired

                    override fun wirelessDebuggingOn() = true

                    override suspend fun connect(): HelperLauncher.Connection {
                        connects++
                        return FakeAdb.failed(ConnectFailure.UNAUTHORIZED).also { record.searchEnded(it.search) }
                    }
                }
            val launcher = HelperLauncher(adb, FakeHelperPort(), AdbCommand("launch"), HANDOFF_MS)
            assertEquals(LaunchOutcome.Unauthorized, launcher.launch())
            assertEquals(LaunchOutcome.Unauthorized, launcher.launch())
            assertEquals(2, connects)
        }

    @Test
    fun `a handoff caches the port its connection went to`() {
        assertNull(record.portCache.load())
        record.connectionEnded(PORT, launched)
        assertEquals(PORT, AdbPairingRecord(prefs).portCache.load())
    }

    @Test
    fun `a search that ended on UNAUTHORIZED forgets the cached port`() {
        record.connectionEnded(PORT, launched)
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        assertNull(AdbPairingRecord(prefs).portCache.load())
    }

    @Test
    fun `a launch without a handoff forgets the cached port`() {
        record.connectionEnded(PORT, launched)
        record.connectionEnded(PORT, LaunchOutcome.NoHandoff(HANDOFF_MS))
        assertNull(AdbPairingRecord(prefs).portCache.load())
    }

    @Test
    fun `a launch whose command failed forgets the cached port`() {
        record.connectionEnded(PORT, launched)
        record.connectionEnded(PORT, LaunchOutcome.CommandFailed(CommandResult.Completed(1, "", "denied")))
        assertNull(AdbPairingRecord(prefs).portCache.load())
    }

    @Test
    fun `a launch that found a helper connected or got no nonce leaves the cache alone`() {
        record.connectionEnded(PORT, launched)
        record.connectionEnded(OTHER_PORT, LaunchOutcome.AlreadyConnected)
        assertEquals(PORT, record.portCache.load())
        record.connectionEnded(OTHER_PORT, LaunchOutcome.NoNonce("the boot count is unknown"))
        assertEquals(PORT, record.portCache.load())
    }

    @Test
    fun `what AdbConnectPort saves is dropped`() {
        record.portCache.save(PORT)
        assertNull(record.portCache.load())
        record.connectionEnded(PORT, launched)
        record.portCache.save(OTHER_PORT)
        assertEquals(PORT, record.portCache.load())
    }

    @Test
    fun `a stored port out of range is no port`() {
        prefs.edit().putInt("port", 70_000).commit()
        assertNull(record.portCache.load())
    }

    private companion object {
        const val PORT = 37_133
        const val OTHER_PORT = 41_017
        const val HANDOFF_MS = 1_000L
        val launched = LaunchOutcome.Launched(4242, PortSource.CACHE)
    }
}
