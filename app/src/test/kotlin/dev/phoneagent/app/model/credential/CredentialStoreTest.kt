package dev.phoneagent.app.model.credential

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = SoftwareCipher()
    private val dir by lazy { File(temp.root, "credentials") }
    private val store by lazy { CredentialStore(dir, cipher) }
    private val ref = SecretRef("api-key/deepseek/0a1b2c3d")
    private val other = SecretRef("api-key/deepseek/ffeeddcc")

    private fun secret(credential: Credential): String? = runBlocking { store.secret(credential, rejected = false) }

    private fun onlyFile(): File = dir.listFiles()!!.single()

    @Test
    fun storedKeysComeBackAndAreNotInTheFile() {
        store.put(ref, KEY)
        assertEquals(KEY, secret(Credential.ApiKey(ref)))
        assertEquals(KEY, runBlocking { store.secret(Credential.ApiKey(ref), rejected = true) })
        assertEquals(CredentialStatus.PRESENT, store.status(ref))
        val bytes = onlyFile().readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(KEY))
        assertEquals(1.toByte(), bytes[0])
        assertEquals(1 + 12 + KEY.length + 16, bytes.size)
        assertTrue(onlyFile().name.matches(Regex("[0-9a-f]{64}\\.bin")))
        assertFalse(onlyFile().name.contains("deepseek"))
    }

    @Test
    fun noAuthenticationHasNoSecretAndSignInIsNotAvailableYet() {
        assertNull(secret(Credential.None))
        val error = assertThrows(ModelError.Auth::class.java) { secret(Credential.ChatGptPlan(ref)) }
        assertTrue(error.message!!.contains(ref.value))
    }

    @Test
    fun aMissingKeyIsAnAuthErrorNamingTheRefNotAnEmptySecret() {
        assertEquals(CredentialStatus.MISSING, store.status(ref))
        val error = assertThrows(ModelError.Auth::class.java) { secret(Credential.ApiKey(ref)) }
        assertTrue(error.message!!.contains(ref.value))
        assertTrue(error.message!!.contains("enter it again"))
    }

    @Test
    fun aLostOrReplacedKeystoreKeyReadsAsUnreadable() {
        store.put(ref, KEY)
        cipher.forget()
        assertUnreadable(ref)
        store.put(ref, KEY)
        cipher.lose()
        assertUnreadable(ref)
    }

    @Test
    fun aTamperedFileReadsAsUnreadable() {
        store.put(ref, KEY)
        val file = onlyFile()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertUnreadable(ref)
    }

    @Test
    fun aFileCopiedUnderAnotherRefDoesNotOpen() {
        store.put(ref, KEY)
        store.put(other, "sk-test-other-not-a-real-key")
        val (first, second) = dir.listFiles()!!.sortedBy { it.name }
        val firstBytes = first.readBytes()
        first.writeBytes(second.readBytes())
        second.writeBytes(firstBytes)
        assertUnreadable(ref)
        assertUnreadable(other)
    }

    @Test
    fun anUnknownVersionOrATruncatedFileReadsAsUnreadable() {
        store.put(ref, KEY)
        val file = onlyFile()
        val bytes = file.readBytes()
        file.writeBytes(byteArrayOf(2) + bytes.copyOfRange(1, bytes.size))
        assertUnreadable(ref)
        file.writeBytes(bytes.copyOfRange(0, 1 + 12 + 15))
        assertUnreadable(ref)
        file.writeBytes(bytes)
        assertEquals(CredentialStatus.PRESENT, store.status(ref))
    }

    @Test
    fun putReplacesAndRemoveDeletes() {
        store.put(ref, KEY)
        store.put(ref, "sk-test-second-not-a-real-key")
        assertEquals("sk-test-second-not-a-real-key", secret(Credential.ApiKey(ref)))
        store.remove(ref)
        assertEquals(CredentialStatus.MISSING, store.status(ref))
        store.remove(ref)
    }

    @Test
    fun retainOnlyDeletesUnreferencedKeysAndInterruptedWrites() {
        store.put(ref, KEY)
        store.put(other, "sk-test-other-not-a-real-key")
        val otherFile = dir.listFiles()!!.map { it.name }.toSet()
        store.retainOnly(setOf(ref))
        assertEquals(CredentialStatus.PRESENT, store.status(ref))
        assertEquals(CredentialStatus.MISSING, store.status(other))
        val kept = onlyFile()
        assertTrue(kept.name in otherFile)
        File(dir, "${"a".repeat(64)}.bin.tmp").writeText("x")
        File(dir, "${kept.name}.tmp").writeText("x")
        File(dir, "unrelated.txt").writeText("x")
        store.retainOnly(setOf(ref))
        assertEquals(setOf(kept.name, "unrelated.txt"), dir.listFiles()!!.map { it.name }.toSet())
        store.retainOnly(emptySet())
        assertEquals(setOf("unrelated.txt"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun retainOnlyWithoutADirectoryDoesNothing() {
        store.retainOnly(emptySet())
        assertFalse(dir.exists())
    }

    @Test
    fun aKeystoreThatRefusesToSealWritesNothing() {
        cipher.refuseSeal = true
        assertThrows(java.security.GeneralSecurityException::class.java) { store.put(ref, KEY) }
        assertFalse(dir.exists() && dir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun theKeyIsInNoLogOrMessage() {
        ShadowLog.clear()
        store.put(ref, KEY)
        secret(Credential.ApiKey(ref))
        cipher.forget()
        val error = assertThrows(ModelError.Auth::class.java) { secret(Credential.ApiKey(ref)) }
        val texts =
            ShadowLog.getLogs().map { "${it.msg} ${it.throwable}" } + listOfNotNull(error.message, error.toString())
        assertTrue(texts.none { KEY in it })
    }

    @Test
    fun formatRoundTripsAndRejectsWhatItCannotRead() {
        val sealed = Sealed(ByteArray(12) { it.toByte() }, ByteArray(20) { 7 })
        val decoded = CredentialFormat.decode(CredentialFormat.encode(sealed))!!
        assertArrayEquals(sealed.iv, decoded.iv)
        assertArrayEquals(sealed.ciphertext, decoded.ciphertext)
        assertNull(CredentialFormat.decode(ByteArray(0)))
        assertNull(CredentialFormat.decode(ByteArray(1 + 12 + 16) { 3 }))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { CredentialFormat.encode(Sealed(ByteArray(11), ByteArray(16))) }
    }

    private fun assertUnreadable(of: SecretRef) {
        assertEquals(CredentialStatus.UNREADABLE, store.status(of))
        val error = assertThrows(ModelError.Auth::class.java) { secret(Credential.ApiKey(of)) }
        assertTrue(error.message!!.contains("cannot be read"))
        assertFalse(error.message!!.contains(KEY))
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}
