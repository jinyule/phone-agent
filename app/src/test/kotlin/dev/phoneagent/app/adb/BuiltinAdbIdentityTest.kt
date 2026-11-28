package dev.phoneagent.app.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbIdentity
import dev.phoneagent.core.adb.AdbServiceFinder
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairedDevice
import dev.phoneagent.core.adb.PairingCode
import dev.phoneagent.core.adb.PairingFailure
import dev.phoneagent.core.adb.PairingResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.UnrecoverableKeyException

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

    /** The files next to the key file set aside. */
    private fun asides(): List<File> = file.parentFile!!.listFiles()!!.filter { ".unreadable-" in it.name }

    /** An identity that paired, sealed in [file], which from now on cannot be opened. */
    private suspend fun pairedThenUnreadable(): ByteArray {
        val paired = AdbIdentity.loadOrCreate(store, NAME).identity
        AdbPairingRecord(prefs).pairingEnded(PairingResult.Paired(PairedDevice("adb-guid")), paired.fingerprint)
        wrap.openFailure = UnrecoverableKeyException("persistent invalid key blob")
        return file.readBytes()
    }

    @Test(timeout = TIMEOUT_MS)
    fun `pairing while the identity cannot be read sets it aside and pairs a new one`() =
        runBlocking {
            val before = pairedThenUnreadable()
            val adb = adb()
            val result = adb.pair(closedPort(), PairingCode("123456"))
            assertEquals(PairingFailure.UNREACHABLE, (result as PairingResult.Failed).reason)
            assertArrayEquals(before, asides().single().readBytes())
            assertFalse(before.contentEquals(file.readBytes()))
            assertEquals(NotPairedReason.IDENTITY_REPLACED, adb.notPaired)
            val logged = ShadowLog.getLogs().filter { "cannot read the adb identity" in it.msg }
            assertEquals(
                "cannot read the adb identity (java.io.IOException < java.security.UnrecoverableKeyException); " +
                    "pairing sets it aside for a new one",
                logged.single().msg,
            )
            assertNull(logged.single().throwable)
        }

    @Test(timeout = TIMEOUT_MS)
    fun `a launch while the identity cannot be read fails and keeps it`() =
        runBlocking {
            val before = pairedThenUnreadable()
            val adb = adb()
            repeat(2) { attempt ->
                val failure =
                    try {
                        adb.connect()
                        null
                    } catch (expected: IOException) {
                        expected
                    }
                val causes = generateSequence(failure?.cause) { cause -> cause.cause }
                assertTrue("$attempt: $failure", causes.any { cause -> cause is UnrecoverableKeyException })
            }
            assertArrayEquals(before, file.readBytes())
            assertTrue(asides().isEmpty())
            assertTrue(adb.isPaired)
        }

    @Test(timeout = TIMEOUT_MS)
    fun `only the latest key set aside is kept`() {
        store.write(byteArrayOf(1, 2, 3))
        store.setAside()
        assertNull(store.read())
        store.setAside()
        store.write(byteArrayOf(4, 5, 6))
        val latest = file.readBytes()
        store.setAside()
        assertArrayEquals(latest, asides().single().readBytes())
        assertNull(store.read())
    }

    private companion object {
        const val NAME = "test"
        const val TIMEOUT_MS = 30_000L

        /** A loopback port nothing listens on any more. */
        fun closedPort(): Int = ServerSocket(0).use { it.localPort }
    }
}
