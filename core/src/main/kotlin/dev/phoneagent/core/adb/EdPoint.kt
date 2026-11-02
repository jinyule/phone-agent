/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/Ed25519.kt.
 * Modified by PhoneAgent contributors: English documentation, named constants, coordinates kept
 * reduced, little-endian helpers moved to Bytes.kt.
 */
package dev.phoneagent.core.adb

import java.math.BigInteger

/**
 * A point of edwards25519 in extended coordinates (X:Y:Z:T with x = X/Z, y = Y/Z, xy = T/Z);
 * formulas and encoding per RFC 8032 §5.1. Only [Spake2] uses it, for a handful of scalar
 * multiplications per pairing, so BigInteger is fast enough. Not constant-time: the private
 * scalar lives for one pairing attempt, and the pairing code is shown on the device's screen.
 */
internal class EdPoint private constructor(
    private val x: BigInteger,
    private val y: BigInteger,
    private val z: BigInteger,
    private val t: BigInteger,
) {
    /** RFC 8032 §5.1.4 point addition (complete for edwards25519). */
    operator fun plus(other: EdPoint): EdPoint {
        val a = (y - x) * (other.y - other.x) % P
        val b = (y + x) * (other.y + other.x) % P
        val c = t * D2 % P * other.t % P
        val d = z * TWO * other.z % P
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return EdPoint((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    operator fun unaryMinus(): EdPoint = EdPoint((P - x).mod(P), y, z, (P - t).mod(P))

    operator fun minus(other: EdPoint): EdPoint = this + (-other)

    /** `k·this` by double-and-add, for `k >= 0`. */
    fun times(k: BigInteger): EdPoint {
        require(k.signum() >= 0) { "scalar must not be negative" }
        var acc = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            acc += acc
            if (k.testBit(i)) acc += this
        }
        return acc
    }

    /** Affine coordinates (x, y), both in `[0, p)`. */
    fun affine(): Pair<BigInteger, BigInteger> {
        val zInv = z.modInverse(P)
        return (x * zInv).mod(P) to (y * zInv).mod(P)
    }

    /** RFC 8032 §5.1.2: 32 bytes, y little-endian with the parity of x in the top bit. */
    fun encode(): ByteArray {
        val (ax, ay) = affine()
        val out = toLittleEndian(ay, ENCODED_SIZE)
        if (ax.testBit(0)) out[ENCODED_SIZE - 1] = (out[ENCODED_SIZE - 1].toInt() or SIGN_BIT).toByte()
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is EdPoint &&
            (x * other.z - other.x * z).mod(P).signum() == 0 &&
            (y * other.z - other.y * z).mod(P).signum() == 0

    override fun hashCode(): Int = encode().contentHashCode()

    companion object {
        /** Size of an encoded point. */
        const val ENCODED_SIZE = 32
        private const val SIGN_BIT = 0x80
        private const val Y_MASK = 0x7f

        /** The field prime 2^255 − 19. */
        val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

        /** Order of the prime-order subgroup, 2^252 + 27742317777372353535851937790883648493. */
        val L: BigInteger = BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")

        private val TWO = BigInteger.TWO
        private val D: BigInteger = (BigInteger.valueOf(-121_665) * BigInteger.valueOf(121_666).modInverse(P)).mod(P)
        private val D2 = D * TWO % P

        /** sqrt(−1) = 2^((p−1)/4). */
        private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
        private val SQRT_EXPONENT: BigInteger = (P - BigInteger.valueOf(5)).shiftRight(3)

        val IDENTITY: EdPoint = EdPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        /** The base point B (y = 4/5). */
        val BASE: EdPoint =
            checkNotNull(decode(hexBytes("5866666666666666666666666666666666666666666666666666666666666666")))

        /** RFC 8032 §5.1.3; `null` for a wrong size, a non-canonical y, or a point not on the curve. */
        fun decode(bytes: ByteArray): EdPoint? {
            if (bytes.size != ENCODED_SIZE) return null
            val raw = bytes.copyOf()
            val sign = raw[ENCODED_SIZE - 1].toInt() and SIGN_BIT != 0
            raw[ENCODED_SIZE - 1] = (raw[ENCODED_SIZE - 1].toInt() and Y_MASK).toByte()
            val y = fromLittleEndian(raw)
            val x = if (y < P) recoverX(y, sign) else null
            return x?.let { EdPoint(it, y, BigInteger.ONE, it * y % P) }
        }

        /** x with the given [sign] (parity) for [y], or `null` when there is none. */
        private fun recoverX(
            y: BigInteger,
            sign: Boolean,
        ): BigInteger? {
            val yy = y * y % P
            val u = (yy - BigInteger.ONE).mod(P)
            val v = (D * yy + BigInteger.ONE) % P
            val v3 = v * v % P * v % P
            val v7 = v3 * v3 % P * v % P
            val candidate = u * v3 % P * (u * v7 % P).modPow(SQRT_EXPONENT, P) % P
            val vxx = v * candidate % P * candidate % P
            val root =
                when (vxx) {
                    u -> candidate
                    (P - u).mod(P) -> candidate * SQRT_M1 % P
                    else -> null
                }
            return when {
                root == null -> null
                root.signum() == 0 && sign -> null
                root.testBit(0) != sign -> P - root
                else -> root
            }
        }
    }
}
