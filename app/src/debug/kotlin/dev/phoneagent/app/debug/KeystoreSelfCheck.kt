package dev.phoneagent.app.debug

import dev.phoneagent.app.model.credential.CipherUnreadable
import dev.phoneagent.app.model.credential.KeystoreCipher
import dev.phoneagent.app.model.credential.Sealed
import dev.phoneagent.core.ExcludeFromCoverage
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.security.SecureRandom

/**
 * Checks [KeystoreCipher] on the device, under a test alias of its own (the credential key is
 * never touched): a sealed secret opens again, a wrong AAD, a tampered ciphertext and a deleted
 * key all fail as [CipherUnreadable]. Returns `PASS`, or `FAIL: <step>` naming exception classes
 * only. The test key is deleted afterwards.
 */
@ExcludeFromCoverage(reason = "AndroidKeyStore is not available on the JVM; run on a device through the debug provider")
internal object KeystoreSelfCheck {
    private const val ALIAS = "phoneagent.credentials.selfcheck"
    private const val SECRET_BYTES = 32

    fun run(): String {
        val cipher = KeystoreCipher(ALIAS)
        return try {
            steps(cipher)
        } catch (e: GeneralSecurityException) {
            // A Keystore step that should have worked refused.
            "FAIL: ${e.javaClass.name}"
        } catch (e: ProviderException) {
            // Keystore-internal failures arrive unchecked.
            "FAIL: ${e.javaClass.name}"
        } catch (e: CipherUnreadable) {
            // A secret that should have opened did not.
            "FAIL: ${e.message}"
        } finally {
            cipher.delete()
        }
    }

    private fun steps(cipher: KeystoreCipher): String {
        val plain = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val aad = "selfcheck".toByteArray()
        val sealed = cipher.seal(plain, aad)
        val tampered = Sealed(sealed.iv, sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val failures =
            listOfNotNull(
                "round trip".takeUnless { cipher.open(sealed, aad).contentEquals(plain) },
                "IV length ${sealed.iv.size}".takeUnless { sealed.iv.size == IV_BYTES },
                "wrong AAD opened".takeUnless { refuses { cipher.open(sealed, "other".toByteArray()) } },
                "tampered ciphertext opened".takeUnless { refuses { cipher.open(tampered, aad) } },
                "deleted key opened".takeUnless {
                    cipher.delete()
                    refuses { cipher.open(sealed, aad) }
                },
            )
        return if (failures.isEmpty()) "PASS" else "FAIL: ${failures.joinToString()}"
    }

    private inline fun refuses(open: () -> ByteArray): Boolean =
        try {
            open()
            false
        } catch (ignoredExpected: CipherUnreadable) {
            // What this step expects.
            true
        }

    private const val IV_BYTES = 12
}
