package dev.phoneagent.app.model.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.phoneagent.core.ExcludeFromCoverage
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretCipher] on one AES-256-GCM key in the Android Keystore under [alias]. The key needs
 * neither user authentication nor an unlocked device (tasks run in the background) and is not in
 * StrongBox. [seal] creates it when it is missing; [open] never does, so a cleared Keystore or a
 * restore onto another device reads as [CipherUnreadable], never as an empty secret.
 */
@ExcludeFromCoverage(reason = "AndroidKeyStore is not available on the JVM; checked by the debug keystore self-check")
internal class KeystoreCipher(
    private val alias: String = ALIAS,
) : SecretCipher {
    override fun seal(
        plain: ByteArray,
        aad: ByteArray,
    ): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: create())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        return Sealed(cipher.iv, ciphertext)
    }

    override fun open(
        sealed: Sealed,
        aad: ByteArray,
    ): ByteArray {
        val key = keyOrUnreadable()
        return try {
            decrypt(key, sealed, aad)
        } catch (e: GeneralSecurityException) {
            // Wrong key, AAD or tag: unreadable, named by class only.
            throw CipherUnreadable(e)
        } catch (e: ProviderException) {
            // Keystore-internal failures (key invalidated underneath us) arrive unchecked.
            throw CipherUnreadable(e)
        }
    }

    private fun decrypt(
        key: SecretKey,
        sealed: Sealed,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    /** Deletes the key; every secret sealed with it becomes unreadable. */
    fun delete() {
        keyStore().deleteEntry(alias)
    }

    private fun keyOrUnreadable(): SecretKey =
        try {
            key() ?: throw CipherUnreadable(null)
        } catch (e: GeneralSecurityException) {
            // The Keystore itself cannot be read.
            throw CipherUnreadable(e)
        }

    private fun key(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        /** The credential store's key. */
        const val ALIAS: String = "phoneagent.credentials.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256
    }
}
