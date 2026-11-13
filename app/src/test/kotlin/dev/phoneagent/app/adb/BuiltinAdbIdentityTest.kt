package dev.phoneagent.app.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbIdentity
import dev.phoneagent.core.adb.AdbServiceFinder
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairedDevice
import dev.phoneagent.core.adb.PairingResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [BuiltinAdb.identity] over a real [SealedFileKeyStore] and pairing record; AtomicFile and
 * SharedPreferences: Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class BuiltinAdbIdentityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val prefs =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
            "test_builtin_adb_identity",
            Context.MODE_PRIVATE,
        )

    /** Seals nothing; [open] throws [openFailure] when one is set. */
    private class PlainWrap : KeyWrap {
        var openFailure: Exception? = null

        override fun seal(plain: ByteArray): ByteArray = plain

        override fun open(sealed: ByteArray): ByteArray {
            openFailure?.let { throw it }
            return sealed
        }
    }

    private val wrap = PlainWrap()
    private val file: File by lazy { File(folder.root, "adb/adbkey.sealed") }
    private val store by lazy { SealedFileKeyStore(file, wrap) }

    /** A client as a new process makes it, on the same files. */
    private fun adb(): BuiltinAdb =
        BuiltinAdb(
            store = store,
            record = AdbPairingRecord(prefs),
            finder = AdbServiceFinder { _, _ -> emptyList() },
            identityName = NAME,
            scan = { loopbackScan(IntRange.EMPTY) },
            wirelessDebugging = { true },
        )

    @Test(timeout = TIMEOUT_MS)
    fun `a process that died between replacing the identity and the record still reports the replacement`() =
        runBlocking {
            val paired = AdbIdentity.loadOrCreate(store, NAME).identity
            AdbPairingRecord(prefs).pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), paired.fingerprint)
            // An unknown format: the next load replaces the identity, and the process dies before
            // BuiltinAdb.identity tells the record (Codex r2).
            file.writeBytes(byteArrayOf(2))
            assertEquals(IdentityLoad.Origin.REPLACED, AdbIdentity.loadOrCreate(store, NAME).origin)
            val next = adb()
            assertEquals(IdentityLoad.Origin.STORED, next.identity().origin)
            assertEquals(NotPairedReason.IDENTITY_REPLACED, next.notPaired)
            assertFalse(next.isPaired)
        }

    private companion object {
        const val NAME = "test"
        const val TIMEOUT_MS = 30_000L
    }
}
