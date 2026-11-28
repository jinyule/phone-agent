/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/PairingAuth.kt.
 * Modified by PhoneAgent contributors: English documentation, internal visibility, failed
 * exchanges reported as false, fixed seeds through a single factory.
 */
package dev.phoneagent.core.adb

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). A missing or empty salt is 32 zero bytes, as the RFC says. */
internal object Hkdf {
    private const val HASH_SIZE = 32
    private const val MAX_BLOCKS = 255

    fun sha256(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..MAX_BLOCKS * HASH_SIZE) { "HKDF length out of range" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt == null || salt.isEmpty()) ByteArray(HASH_SIZE) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var position = 0
        var counter = 1
        while (position < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - position)
            previous.copyInto(out, position, 0, n)
            position += n
            counter++
        }
        return out
    }
}

/**
 * AES-128-GCM of the pairing channel, as AOSP's `Aes128Gcm`: key = HKDF-SHA256(SPAKE2 key, no
 * salt, "adb pairing_auth aes-128-gcm key", 16); the 12-byte nonce is the direction's sequence
 * number (64-bit little-endian, from 0, one per message) followed by zeros; no associated data;
 * the 16-byte tag follows the ciphertext.
 */
internal class PairingCipher(
    keyMaterial: ByteArray,
) {
    private val key = SecretKeySpec(Hkdf.sha256(keyMaterial, null, INFO, KEY_SIZE), "AES")
    private var encryptSequence = 0L
    private var decryptSequence = 0L

    fun encrypt(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, encryptSequence++, plain)

    /** The plaintext; throws [javax.crypto.AEADBadTagException] when [sealed] does not authenticate. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(sealed: ByteArray): ByteArray =
        run(Cipher.DECRYPT_MODE, decryptSequence, sealed).also { decryptSequence++ }

    private fun run(
        mode: Int,
        sequence: Long,
        data: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        for (i in 0 until Long.SIZE_BYTES) nonce[i] = (sequence ushr (Byte.SIZE_BITS * i)).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(data)
    }

    companion object {
        private const val KEY_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The authenticated part of pairing: a SPAKE2 exchange, then [PairingCipher] with the agreed key.
 * The names include their terminating NUL, as AOSP passes `sizeof` of the C strings.
 */
internal class PairingAuth private constructor(
    role: Spake2.Role,
    password: ByteArray,
    seed: ByteArray?,
) {
    private val spake =
        when (role) {
            Spake2.Role.ALICE -> Spake2(role, CLIENT_NAME, SERVER_NAME)
            Spake2.Role.BOB -> Spake2(role, SERVER_NAME, CLIENT_NAME)
        }

    /** This side's SPAKE2 message. */
    val message: ByteArray =
        if (seed == null) spake.generateMessage(password, SecureRandom()) else spake.generateMessage(password, seed)

    private var cipher: PairingCipher? = null

    /** Takes the peer's message; `false` when it is malformed (the exchange cannot go on). */
    fun initCipher(theirMessage: ByteArray): Boolean {
        check(cipher == null) { "cipher already initialized" }
        val key = spake.processMessage(theirMessage) ?: return false
        cipher = PairingCipher(key)
        return true
    }

    fun encrypt(data: ByteArray): ByteArray = checkNotNull(cipher) { "initCipher first" }.encrypt(data)

    @Throws(GeneralSecurityException::class)
    fun decrypt(data: ByteArray): ByteArray = checkNotNull(cipher) { "initCipher first" }.decrypt(data)

    companion object {
        val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        /** The given side for [password]; a [seed] fixes its randomness (known-answer tests only). */
        fun create(
            role: Spake2.Role,
            password: ByteArray,
            seed: ByteArray? = null,
        ): PairingAuth = PairingAuth(role, password, seed)
    }
}
