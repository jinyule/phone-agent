package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger
import java.security.MessageDigest

class EdPointTest {
    /** RFC 8032 §7.1 TEST 1–3 (Ed25519): secret key, public key, message, signature. */
    class Rfc8032(
        val name: String,
        val secret: String,
        val public: String,
        val message: String,
        val signature: String,
    ) {
        override fun toString(): String = name
    }

    @ParameterizedTest
    @MethodSource("rfc8032")
    fun `derives the RFC 8032 public key`(case: Rfc8032) {
        val (scalar, _) = expand(hexBytes(case.secret))
        assertEquals(
            case.public,
            EdPoint.BASE
                .times(scalar)
                .encode()
                .toHex(),
        )
    }

    @ParameterizedTest
    @MethodSource("rfc8032")
    fun `reproduces and verifies the RFC 8032 signature`(case: Rfc8032) {
        val (scalar, prefix) = expand(hexBytes(case.secret))
        val publicKey = hexBytes(case.public)
        val message = hexBytes(case.message)
        val r = fromLittleEndian(sha512(prefix, message)).mod(EdPoint.L)
        val encodedR = EdPoint.BASE.times(r).encode()
        val k = fromLittleEndian(sha512(encodedR, publicKey, message)).mod(EdPoint.L)
        val s = (r + k * scalar).mod(EdPoint.L)
        assertEquals(case.signature, (encodedR + toLittleEndian(s, 32)).toHex())

        val signature = hexBytes(case.signature)
        val pointR = checkNotNull(EdPoint.decode(signature.copyOf(32)))
        val pointA = checkNotNull(EdPoint.decode(publicKey))
        val sigS = fromLittleEndian(signature.copyOfRange(32, 64))
        assertEquals(EdPoint.BASE.times(sigS), pointR + pointA.times(k), "[S]B = R + [k]A")
    }

    @Test
    fun `group laws hold`() {
        val b = EdPoint.BASE
        val twice = b + b
        assertEquals(b, b + EdPoint.IDENTITY)
        assertEquals(EdPoint.IDENTITY, b - b)
        assertEquals(twice, b.times(BigInteger.TWO))
        assertEquals(b.times(BigInteger.valueOf(5)), twice + twice + b)
        assertEquals(EdPoint.IDENTITY, b.times(EdPoint.L))
        assertEquals(b, b.times(EdPoint.L + BigInteger.ONE))
        assertEquals(EdPoint.IDENTITY, b.times(BigInteger.ZERO))
        assertEquals(b, -(-b))
        assertNotEquals(b, -b)
        assertNotEquals(b, twice)
        assertFalse(b.equals("B"))
        assertThrows<IllegalArgumentException> { b.times(BigInteger.valueOf(-1)) }
    }

    @Test
    fun `equal points in different coordinates have one encoding and hash`() {
        val viaSum = EdPoint.BASE + EdPoint.BASE + EdPoint.BASE
        val viaTimes = EdPoint.BASE.times(BigInteger.valueOf(3))
        assertEquals(viaSum, viaTimes)
        assertEquals(viaSum.hashCode(), viaTimes.hashCode())
        assertEquals(viaSum.encode().toHex(), viaTimes.encode().toHex())
    }

    @Test
    fun `the base point is y = 4 over 5 with an even x`() {
        val (x, y) = EdPoint.BASE.affine()
        assertEquals(BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(EdPoint.P) % EdPoint.P, y)
        assertTrue(!x.testBit(0))
        assertEquals(
            "0100000000000000000000000000000000000000000000000000000000000000",
            EdPoint.IDENTITY.encode().toHex(),
        )
    }

    @Test
    fun `decoding round-trips points and their negatives`() {
        for (k in 1L..20L) {
            val point = EdPoint.BASE.times(BigInteger.valueOf(k))
            val encoded = point.encode()
            assertEquals(point, EdPoint.decode(encoded))
            encoded[31] = (encoded[31].toInt() xor 0x80).toByte()
            assertEquals(-point, EdPoint.decode(encoded), "sign bit flipped is the negative")
        }
    }

    @Test
    fun `decoding rejects wrong sizes, non-canonical y and points off the curve`() {
        assertNull(EdPoint.decode(ByteArray(31)))
        assertNull(EdPoint.decode(ByteArray(33)))
        // y = p is not canonical.
        assertNull(EdPoint.decode(toLittleEndian(EdPoint.P, 32)))
        // x = 0 (y = 1 and y = -1) cannot carry a set sign bit.
        assertNull(EdPoint.decode(hexBytes("01000000000000000000000000000000000000000000000000000000000000" + "80")))
        val minusOne = toLittleEndian(EdPoint.P - BigInteger.ONE, 32)
        assertNotNull(EdPoint.decode(minusOne), "(0, -1) is the point of order 2")
        minusOne[31] = (minusOne[31].toInt() or 0x80).toByte()
        assertNull(EdPoint.decode(minusOne))
        // Some small y have no x: x^2 = (y^2 - 1) / (d y^2 + 1) is a non-square (Euler's criterion).
        val offCurve = (2L..40L).map(BigInteger::valueOf).filter { !isSquare(xSquared(it)) }
        assertTrue(offCurve.isNotEmpty())
        for (y in offCurve) assertNull(EdPoint.decode(toLittleEndian(y, 32)), "y = $y")
        for (y in (2L..40L).map(BigInteger::valueOf) - offCurve.toSet()) {
            assertNotNull(EdPoint.decode(toLittleEndian(y, 32)), "y = $y")
        }
    }

    private fun xSquared(y: BigInteger): BigInteger {
        val p = EdPoint.P
        val d = (BigInteger.valueOf(-121_665) * BigInteger.valueOf(121_666).modInverse(p)).mod(p)
        val yy = y * y % p
        return (yy - BigInteger.ONE).mod(p) * (d * yy + BigInteger.ONE).mod(p).modInverse(p) % p
    }

    private fun isSquare(v: BigInteger): Boolean =
        v.signum() == 0 || v.modPow((EdPoint.P - BigInteger.ONE).shiftRight(1), EdPoint.P) == BigInteger.ONE

    /** RFC 8032 §5.1.5: the clamped scalar and the prefix from SHA-512 of the secret key. */
    private fun expand(secret: ByteArray): Pair<BigInteger, ByteArray> {
        val h = sha512(secret)
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 0xf8).toByte()
        a[31] = (a[31].toInt() and 0x7f or 0x40).toByte()
        return fromLittleEndian(a) to h.copyOfRange(32, 64)
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        parts.forEach(digest::update)
        return digest.digest()
    }

    companion object {
        @JvmStatic
        fun rfc8032(): List<Rfc8032> =
            listOf(
                Rfc8032(
                    "TEST 1",
                    "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                    "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                    "",
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
                ),
                Rfc8032(
                    "TEST 2",
                    "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                    "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                    "72",
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                        "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
                ),
                Rfc8032(
                    "TEST 3",
                    "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                    "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                    "af82",
                    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                        "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
                ),
            )
    }
}
