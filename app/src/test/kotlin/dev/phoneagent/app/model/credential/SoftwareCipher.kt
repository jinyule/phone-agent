package dev.phoneagent.app.model.credential

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] with a software AES-256-GCM key, standing in for the Keystore on the JVM. GCM is
 * real, so tampering, a swapped AAD and a replaced key really fail. [forget] and [lose] simulate a
 * cleared Keystore (a new key, or none); [refuseSeal] a Keystore that refuses to create keys, and
 * [sealFailure] one that fails in a way nobody expects (thrown as is).
 */
internal class SoftwareCipher : SecretCipher {
    private var key: SecretKey? = newKey()
    var refuseSeal: Boolean = false
    var sealFailure: RuntimeException? = null
    var seals: Int = 0
        private set

    override fun seal(
        plain: ByteArray,
        aad: ByteArray,
    ): Sealed {
        if (refuseSeal) throw GeneralSecurityException("keystore refused")
        sealFailure?.let { throw it }
        seals++
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key ?: newKey().also { key = it })
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        return Sealed(cipher.iv, ciphertext)
    }

    override fun open(
        sealed: Sealed,
        aad: ByteArray,
    ): ByteArray {
        val current = key ?: throw CipherUnreadable(null)
        return try {
            decrypt(current, sealed, aad)
        } catch (e: GeneralSecurityException) {
            // Like the Keystore cipher: unreadable, named by class only.
            throw CipherUnreadable(e)
        }
    }

    private fun decrypt(
        current: SecretKey,
        sealed: Sealed,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    /** The key is replaced, as after a restore onto another device. */
    fun forget() {
        key = newKey()
    }

    /** The key is gone, as after the Keystore was cleared. */
    fun lose() {
        key = null
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
