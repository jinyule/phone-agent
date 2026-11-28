package dev.phoneagent.app.model.credential

/**
 * Authenticated encryption of stored secrets under one device-bound key. [KeystoreCipher] is the
 * app's (Android Keystore); tests use a software AES-GCM one, so tampering and AAD checks are real.
 */
internal interface SecretCipher {
    /** Encrypts [plain] bound to [aad]; creates the key the first time it is needed. */
    fun seal(
        plain: ByteArray,
        aad: ByteArray,
    ): Sealed

    /**
     * Decrypts [sealed] bound to [aad]. Never creates a key: a missing or replaced key, a wrong
     * [aad] and a failed tag all throw [CipherUnreadable].
     */
    fun open(
        sealed: Sealed,
        aad: ByteArray,
    ): ByteArray
}

/** An AES-GCM result: the 12-byte [iv] and the [ciphertext] with its 16-byte tag. */
internal class Sealed(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * A stored secret that cannot be decrypted (Keystore cleared, restored on another device, data
 * damaged). The message names only the cause's class: crypto messages can quote key material.
 */
internal class CipherUnreadable(
    cause: Throwable?,
) : Exception("stored secret cannot be decrypted (${cause?.javaClass?.name ?: "bad format"})")
