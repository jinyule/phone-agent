package dev.phoneagent.app.adb

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
import java.io.File
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

    /** AES-GCM under a key in memory; replacing [key] is what a keystore reset does. */
    private class SoftwareWrap : KeyWrap {
        var key: SecretKey = newKey()

        /** Thrown by [seal] instead of sealing. */
        var sealFailure: Exception? = null

        /** Thrown by [open] instead of opening. */
        var openFailure: Exception? = null

        override fun seal(plain: ByteArray): ByteArray {
            sealFailure?.let { throw it }
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            openFailure?.let { throw it }
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
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

    @Test
    fun `a lost keystore key, a damaged file or an unknown format reads as unusable and the identity is replaced`() {
        val damages: List<(File) -> Unit> =
            listOf(
                { wrap.key = SoftwareWrap.newKey() },
                { it.writeBytes(it.readBytes().also { bytes -> bytes[bytes.size - 1] = (bytes.last() + 1).toByte() }) },
                { it.writeBytes(byteArrayOf(2) + it.readBytes().copyOfRange(1, it.length().toInt())) },
                { it.writeBytes(ByteArray(0)) },
            )
        for ((index, damage) in damages.withIndex()) {
            assertTrue(!file.exists() || file.delete())
            assertEquals("case $index", IdentityLoad.Origin.CREATED, load("first"))
            damage(file)
            assertArrayEquals("case $index", ByteArray(0), store.read())
            assertEquals("case $index", IdentityLoad.Origin.REPLACED, load("again"))
            assertEquals("case $index", IdentityLoad.Origin.STORED, load("again"))
        }
    }

    @Test
    fun `a key that cannot be opened, checked or Keystore-internal, reads as unusable`() {
        store.write(pkcs8)
        val read =
            keystoreFailures().map { failure ->
                wrap.openFailure = failure
                runCatching { store.read() }.fold({ "${it?.size} bytes" }, { it.javaClass.simpleName })
            }
        assertEquals(listOf("0 bytes", "0 bytes"), read)
    }

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

    /** A checked Keystore refusal, and an internal failure as the Keystore reports it: unchecked. */
    private fun keystoreFailures(): List<Exception> =
        listOf(GeneralSecurityException("keystore is locked"), ProviderException("Keystore operation failed"))
}

private const val GCM = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128
