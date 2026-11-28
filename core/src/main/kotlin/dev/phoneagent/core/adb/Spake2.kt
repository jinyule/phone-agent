/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/Spake2.kt.
 * Modified by PhoneAgent contributors: English documentation, explicit one-shot state, peer
 * message failures reported as null instead of an exception.
 */
package dev.phoneagent.core.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The SPAKE2 variant of adb wireless pairing (edwards25519 + SHA-512), byte for byte as BoringSSL's
 * `spake25519.cc`, which adbd uses:
 * - private scalar = (64 random bytes mod L) · 8 (clears the cofactor);
 * - password scalar = SHA-512(password) mod L, plus L, 2L and 4L for each of its low three bits
 *   that is set (BoringSSL's unilateral fix that makes it a multiple of 8);
 * - message = private·B + password scalar·(M for Alice, N for Bob);
 * - key = SHA-512 over, each with an 8-byte little-endian length prefix: Alice's name, Bob's name,
 *   Alice's message, Bob's message, private·(peer message − password scalar·peer constant),
 *   SHA-512(password).
 *
 * One instance runs one exchange: [generateMessage] once, then [processMessage] once.
 */
internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    /** Alice is the pairing client, Bob the device. */
    enum class Role { ALICE, BOB }

    private class Generated(
        val privateKey: BigInteger,
        val passwordScalar: BigInteger,
        val passwordHash: ByteArray,
        val message: ByteArray,
    )

    private var generated: Generated? = null
    private var processed = false

    /** This side's 32-byte message for [password], from fresh randomness. */
    fun generateMessage(
        password: ByteArray,
        random: SecureRandom,
    ): ByteArray = generateMessage(password, ByteArray(SEED_SIZE).also(random::nextBytes))

    /** This side's message from a fixed 64-byte [seed] (known-answer tests). */
    fun generateMessage(
        password: ByteArray,
        seed: ByteArray,
    ): ByteArray {
        check(generated == null) { "message already generated" }
        require(seed.size == SEED_SIZE) { "seed must be $SEED_SIZE bytes" }
        val privateKey = fromLittleEndian(seed).mod(EdPoint.L).shiftLeft(COFACTOR_BITS)
        val hash = sha512().digest(password)
        val scalar = cofactorCleared(fromLittleEndian(hash).mod(EdPoint.L))
        val mask = (if (role == Role.ALICE) M else N).times(scalar)
        val message = (EdPoint.BASE.times(privateKey) + mask).encode()
        generated = Generated(privateKey, scalar, hash, message)
        return message.copyOf()
    }

    /**
     * The 64-byte shared key from the peer's message, or `null` when that message is not 32 bytes
     * or not a point on the curve. Either way the exchange is over.
     */
    fun processMessage(theirMessage: ByteArray): ByteArray? {
        val mine = checkNotNull(generated) { "generateMessage first" }
        check(!processed) { "message already processed" }
        processed = true
        val peerPoint = EdPoint.decode(theirMessage) ?: return null
        val peerMask = (if (role == Role.ALICE) N else M).times(mine.passwordScalar)
        val shared = (peerPoint - peerMask).times(mine.privateKey).encode()
        val digest = sha512()
        val put = { part: ByteArray ->
            digest.update(toLittleEndian(BigInteger.valueOf(part.size.toLong()), LENGTH_PREFIX_SIZE))
            digest.update(part)
        }
        if (role == Role.ALICE) {
            listOf(myName, theirName, mine.message, theirMessage).forEach(put)
        } else {
            listOf(theirName, myName, theirMessage, mine.message).forEach(put)
        }
        put(shared)
        put(mine.passwordHash)
        return digest.digest()
    }

    companion object {
        /** Bytes of randomness behind the private scalar. */
        const val SEED_SIZE = 64
        private const val COFACTOR_BITS = 3
        private const val LENGTH_PREFIX_SIZE = 8

        /** The seed strings M and N are derived from (see [derivePoint]). */
        const val SEED_M = "edwards25519 point generation seed (M)"
        const val SEED_N = "edwards25519 point generation seed (N)"

        /** BoringSSL's constants, checked against [derivePoint] by the tests. */
        val M: EdPoint =
            checkNotNull(EdPoint.decode(hexBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        val N: EdPoint =
            checkNotNull(EdPoint.decode(hexBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))

        /** A multiple of 8 congruent to [scalar] mod L: adds L, 2L, 4L for each low bit that is set. */
        fun cofactorCleared(scalar: BigInteger): BigInteger {
            var s = scalar
            for (bit in 0 until COFACTOR_BITS) {
                if (s.testBit(bit)) s += EdPoint.L.shiftLeft(bit)
            }
            return s
        }

        /** SHA-256 of [seed], then SHA-256 again until the digest decodes as a point (BoringSSL's script). */
        fun derivePoint(seed: String): EdPoint {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
            while (true) {
                EdPoint.decode(v)?.let { return it }
                v = MessageDigest.getInstance("SHA-256").digest(v)
            }
        }

        private fun sha512(): MessageDigest = MessageDigest.getInstance("SHA-512")
    }
}
