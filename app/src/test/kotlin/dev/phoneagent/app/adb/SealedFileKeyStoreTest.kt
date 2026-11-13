package dev.phoneagent.app.adb

import android.util.AtomicFile
import dev.phoneagent.core.adb.AdbIdentity
import dev.phoneagent.core.adb.IdentityLoad
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
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AtomicFile is Android's: Robolectric. The keystore is not, so a software AES-GCM wrap stands in. */
@RunWith(RobolectricTestRunner::class)
class SealedFileKeyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * AES-GCM under a key in memory. A `null` [key] is a keystore reset: [open] fails with
     * [WrapKeyLost], and the next [seal] makes a new key, as [KeystoreKeyWrap] does.
     */
    private class SoftwareWrap : KeyWrap {
        var key: SecretKey? = newKey()

        /** Thrown by [seal] instead of sealing. */
        var sealFailure: Exception? = null

        /** Thrown by [open] instead of opening. */
        var openFailure: Exception? = null

        override fun seal(plain: ByteArray): ByteArray {
            sealFailure?.let { throw it }
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key ?: newKey().also { key = it }, GCMParameterSpec(TAG_BITS, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            openFailure?.let { throw it }
            val cipher = Cipher.getInstance(GCM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key ?: throw WrapKeyLost("no key"),
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
            )
            return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }

        companion object {
            fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val wrap = SoftwareWrap()
    private val file: File by lazy { File(folder.root, "adb/adbkey.sealed") }
    private val store by lazy { SealedFileKeyStore(file, wrap) }
    private val pkcs8 = ByteArray(1_200) { (it * 7).toByte() }

    private fun load(name: String): IdentityLoad.Origin = runBlocking { AdbIdentity.loadOrCreate(store, name).origin }

    @Test
    fun `nothing stored reads as absent`() {
        assertNull(store.read())
    }

    @Test
    fun `a written key reads back, and is not on disk in the clear`() {
        store.write(pkcs8)
        assertArrayEquals(pkcs8, store.read())
        assertArrayEquals(pkcs8, SealedFileKeyStore(file, wrap).read())
        val disk = file.readBytes()
        assertEquals(1.toByte(), disk[0])
        assertFalse(disk.asList().windowed(16).contains(pkcs8.copyOfRange(100, 116).asList()))
    }

    /**
     * After [damage] to a stored identity, the read is unusable, logged as [logged] alone (no
     * failure, nothing of the file), and the identity is replaced once, then stored again.
     */
    private fun replacedAfter(
        logged: String,
        damage: (File) -> Unit,
    ) {
        assertEquals(IdentityLoad.Origin.CREATED, load("first"))
        damage(file)
        val since = ShadowLog.getLogs().size
        assertArrayEquals(ByteArray(0), store.read())
        val lines = ShadowLog.getLogs().drop(since).filter { it.tag == "PhoneAgentBuiltinAdb" }
        assertEquals(listOf("$logged; a new identity replaces it"), lines.map { it.msg })
        assertNull(lines.single().throwable)
        assertEquals(IdentityLoad.Origin.REPLACED, load("again"))
        assertEquals(IdentityLoad.Origin.STORED, load("again"))
    }

    @Test
    fun `a lost sealing key reads as unusable and the identity is replaced`() =
        replacedAfter("the key that sealed the stored adb key is gone") { wrap.key = null }

    @Test
    fun `a key sealed under another key reads as unusable and the identity is replaced`() =
        replacedAfter("the stored adb key does not authenticate") { wrap.key = SoftwareWrap.newKey() }

    @Test
    fun `a damaged file reads as unusable and the identity is replaced`() =
        replacedAfter("the stored adb key does not authenticate") {
            it.writeBytes(it.readBytes().also { bytes -> bytes[bytes.size - 1] = (bytes.last() + 1).toByte() })
        }

    @Test
    fun `an unknown format reads as unusable and the identity is replaced`() =
        replacedAfter("the stored adb key file is empty or of an unknown format") {
            it.writeBytes(byteArrayOf(2) + it.readBytes().copyOfRange(1, it.length().toInt()))
        }

    @Test
    fun `an empty file reads as unusable and the identity is replaced`() =
        replacedAfter("the stored adb key file is empty or of an unknown format") { it.writeBytes(ByteArray(0)) }

    /**
     * [failure] from opening fails the read and the identity load as IOException, leaves the file,
     * and the next read opens the same key.
     */
    private fun failsForNow(failure: Exception) {
        store.write(pkcs8)
        val before = file.readBytes()
        wrap.openFailure = failure
        val read = runCatching { store.read() }.exceptionOrNull()
        assertTrue("$read", read is IOException && read.cause === failure)
        val loaded = runCatching { load("again") }.exceptionOrNull()
        assertTrue("$loaded", loaded is IOException)
        assertArrayEquals(before, file.readBytes())
        wrap.openFailure = null
        assertArrayEquals(pkcs8, store.read())
    }

    @Test
    fun `a Keystore-internal failure to open fails the read as IOException, and the key opens next time`() =
        failsForNow(ProviderException("Keystore operation failed"))

    @Test
    fun `a checked failure to open fails the read as IOException, and the key opens next time`() =
        failsForNow(GeneralSecurityException("keystore is locked"))

    @Test
    fun `a key that cannot be sealed, checked or Keystore-internal, is not written and fails as IOException`() {
        store.write(pkcs8)
        val before = file.readBytes()
        val written =
            keystoreFailures().map { failure ->
                wrap.sealFailure = failure
                val thrown = runCatching { store.write(ByteArray(10)) }.exceptionOrNull()
                "${thrown?.javaClass?.simpleName} from ${thrown?.cause?.javaClass?.simpleName}, " +
                    "kept ${before.contentEquals(file.readBytes())}"
            }
        assertEquals(
            listOf(
                "IOException from GeneralSecurityException, kept true",
                "IOException from ProviderException, kept true",
            ),
            written,
        )
    }

    @Test
    fun `a file that a directory stands in for fails the write as IOException and is left alone`() {
        file.mkdirs()
        File(file, "kept").writeText("not ours")
        val thrown = runCatching { store.write(pkcs8) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IOException)
        assertEquals("not ours", File(file, "kept").readText())
    }

    @Test
    fun `a rename that is lost fails the write as IOException and keeps the old key`() {
        store.write(pkcs8)
        val lostRename = SealedFileKeyStore(file, wrap) { LostRename(it) }
        val thrown = runCatching { lostRename.write(ByteArray(10)) }.exceptionOrNull()
        assertTrue("$thrown", thrown is IOException)
        assertArrayEquals(pkcs8, SealedFileKeyStore(file, wrap).read())
    }

    @Test
    fun `a write that fails midway is undone, keeps the old key and fails as it failed`() {
        store.write(pkcs8)
        val failing = SealedFileKeyStore(file, wrap) { FailingWrite(it) }
        val thrown = runCatching { failing.write(ByteArray(10)) }.exceptionOrNull()
        assertEquals("disk full", thrown?.message)
        assertArrayEquals(pkcs8, SealedFileKeyStore(file, wrap).read())
        assertFalse("the half-written file is deleted", File(file.path + ".new").exists())
    }

    /** As a failed rename: `finishWrite` closes the new file, logs, and returns normally. */
    private class LostRename(
        file: File,
    ) : AtomicFile(file) {
        override fun finishWrite(str: FileOutputStream?) {
            str?.close()
        }
    }

    /** A write to the new file that fails, as on a full disk. */
    private class FailingWrite(
        file: File,
    ) : AtomicFile(file) {
        override fun startWrite(): FileOutputStream =
            object : FileOutputStream(super.startWrite().fd) {
                override fun write(b: ByteArray) = throw IOException("disk full")
            }
    }

    /** A checked Keystore refusal, and an internal failure as the Keystore reports it: unchecked. */
    private fun keystoreFailures(): List<Exception> =
        listOf(GeneralSecurityException("keystore is locked"), ProviderException("Keystore operation failed"))
}

private const val GCM = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128
