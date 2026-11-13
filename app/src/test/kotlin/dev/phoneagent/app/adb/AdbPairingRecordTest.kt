package dev.phoneagent.app.adb

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.AdbEndpoint
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
        record.pairingEnded(PairingResult.Failed(PairingFailure.WRONG_CODE, "x"), KEY)
        assertFalse(record.isPaired)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        assertTrue(AdbPairingRecord(prefs).isPaired)
    }

    @Test
    fun `a created or replaced identity drops the pairing, a stored one keeps it`() {
        for (origin in IdentityLoad.Origin.entries) {
            record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
            record.identityLoaded(origin, KEY)
            assertEquals("$origin", origin == IdentityLoad.Origin.STORED, record.isPaired)
        }
    }

    @Test
    fun `without a pairing ever the reason is never paired, also after a new identity`() {
        assertEquals(NotPairedReason.NEVER_PAIRED, record.notPaired)
        record.identityLoaded(IdentityLoad.Origin.CREATED, KEY)
        record.identityLoaded(IdentityLoad.Origin.REPLACED, KEY)
        assertEquals(NotPairedReason.NEVER_PAIRED, AdbPairingRecord(prefs).notPaired)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        assertNull(record.notPaired)
    }

    @Test
    fun `a replaced identity is the reason until a pairing succeeds`() {
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        record.identityLoaded(IdentityLoad.Origin.REPLACED, KEY)
        assertEquals(NotPairedReason.IDENTITY_REPLACED, AdbPairingRecord(prefs).notPaired)
        record.identityLoaded(IdentityLoad.Origin.STORED, KEY)
        record.identityLoaded(IdentityLoad.Origin.CREATED, KEY)
        assertEquals(NotPairedReason.IDENTITY_REPLACED, record.notPaired)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        assertNull(record.notPaired)
        record.identityLoaded(IdentityLoad.Origin.STORED, KEY)
        assertNull(record.notPaired)
    }

    @Test
    fun `a key created while paired counts as a replaced identity`() {
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        record.identityLoaded(IdentityLoad.Origin.CREATED, KEY)
        assertEquals(NotPairedReason.IDENTITY_REPLACED, AdbPairingRecord(prefs).notPaired)
    }

    @Test
    fun `a stored identity other than the one that paired drops the pairing as replaced`() {
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        record.identityLoaded(IdentityLoad.Origin.STORED, KEY)
        assertTrue(record.isPaired)
        record.identityLoaded(IdentityLoad.Origin.STORED, OTHER_KEY)
        assertEquals(NotPairedReason.IDENTITY_REPLACED, AdbPairingRecord(prefs).notPaired)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), OTHER_KEY)
        record.identityLoaded(IdentityLoad.Origin.STORED, OTHER_KEY)
        assertTrue(record.isPaired)
    }

    @Test
    fun `a pairing recorded without a fingerprint is judged by the origin alone`() {
        prefs.edit().putBoolean("paired", true).commit()
        record.identityLoaded(IdentityLoad.Origin.STORED, OTHER_KEY)
        assertTrue(record.isPaired)
        record.identityLoaded(IdentityLoad.Origin.REPLACED, OTHER_KEY)
        assertEquals(NotPairedReason.IDENTITY_REPLACED, record.notPaired)
    }

    @Test
    fun `a stored reason this version does not know reads as never paired`() {
        prefs.edit().putString("not_paired", "SOMETHING_LATER").commit()
        assertEquals(NotPairedReason.NEVER_PAIRED, record.notPaired)
    }

    @Test
    fun `a search that ended on UNAUTHORIZED keeps the pairing and records the refusal`() {
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        val next = AdbPairingRecord(prefs)
        assertTrue(next.isPaired)
        assertTrue(next.lastRefused)
    }

    @Test
    fun `a failed search that tried the cached port forgets it, whatever failed there`() {
        for (reason in ConnectFailure.entries) {
            record.connectionEnded(PORT, launched)
            val tried = listOf(AdbEndpoint("127.0.0.1", PORT), AdbEndpoint("127.0.0.1", OTHER_PORT))
            record.searchEnded(PortSearchResult.Failed(ConnectResult.Failed(reason, "x"), tried, null))
            assertNull("$reason", AdbPairingRecord(prefs).portCache.load())
            assertEquals("$reason", reason == ConnectFailure.UNAUTHORIZED, record.lastRefused)
            record.connectionEnded(PORT, LaunchOutcome.AlreadyConnected)
        }
    }

    @Test
    fun `a search that connected elsewhere forgets the cached port`() {
        for (source in listOf(PortSource.MDNS, PortSource.SCAN)) {
            record.connectionEnded(PORT, launched)
            record.connectedThrough(source)
            assertNull("$source", AdbPairingRecord(prefs).portCache.load())
        }
    }

    @Test
    fun `a search that connected through the cached port keeps it`() {
        record.connectionEnded(PORT, launched)
        record.connectedThrough(PortSource.CACHE)
        record.connectionEnded(PORT, LaunchOutcome.AlreadyConnected)
        assertEquals(PORT, AdbPairingRecord(prefs).portCache.load())
    }

    @Test
    fun `without a cached port a search that connected changes nothing`() {
        record.searchEnded(failed(ConnectFailure.UNAUTHORIZED))
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        for (source in PortSource.entries) record.connectedThrough(source)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(emptyList<String?>(), changed)
        assertNull(record.portCache.load())
        assertTrue(record.lastRefused)
    }

    @Test
    fun `other failed searches record no refusal and keep a cached port they did not try`() {
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
        record.pairingEnded(PairingResult.Failed(PairingFailure.WRONG_CODE, "x"), KEY)
        assertTrue(record.lastRefused)
        record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
        assertFalse(AdbPairingRecord(prefs).lastRefused)
    }

    @Test
    fun `after a refusal the next launch connects again`() =
        runTest {
            record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), KEY)
            var connects = 0
            val adb =
                object : HelperLauncher.Adb {
                    override val notPaired get() = record.notPaired

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
        record.connectionEnded(PORT, LaunchOutcome.CommandFailed(1, null, 6))
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
        /** Fingerprints of two identities. */
        const val KEY = "1c45ed4a"
        const val OTHER_KEY = "9e0b77d2"
        const val PORT = 37_133
        const val OTHER_PORT = 41_017
        const val HANDOFF_MS = 1_000L
        val launched = LaunchOutcome.Launched(4242, PortSource.CACHE)
    }
}
