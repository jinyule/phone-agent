package dev.phoneagent.app.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbEndpoint
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.ConnectResult
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairedDevice
import dev.phoneagent.core.adb.PairingFailure
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortSearchResult
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
    fun `only a search that ended on UNAUTHORIZED drops the pairing`() {
        for (reason in listOf(null) + ConnectFailure.entries) {
            record.pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")))
            record.searchEnded(failed(reason))
            assertEquals("$reason", reason != ConnectFailure.UNAUTHORIZED, record.isPaired)
        }
    }

    @Test
    fun `the port cache holds the last valid port`() {
        assertNull(record.load())
        record.save(37_133)
        assertEquals(37_133, AdbPairingRecord(prefs).load())
        prefs.edit().putInt("port", 70_000).commit()
        assertNull(record.load())
    }
}
