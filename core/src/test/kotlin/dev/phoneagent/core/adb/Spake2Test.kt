package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

class Spake2Test {
    @Test
    fun `M and N are BoringSSL's constants derived from their seeds`() {
        assertEquals(Spake2.M, Spake2.derivePoint(Spake2.SEED_M))
        assertEquals(Spake2.N, Spake2.derivePoint(Spake2.SEED_N))
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", Spake2.M.encode().toHex())
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", Spake2.N.encode().toHex())
    }

    @Test
    fun `point derivation hashes again until the digest is a point`() {
        val sha256 = { bytes: ByteArray -> MessageDigest.getInstance("SHA-256").digest(bytes) }
        val seed = (1..100).map { "seed $it" }.first { EdPoint.decode(sha256(it.toByteArray())) == null }
        var digest = sha256(seed.toByteArray())
        while (EdPoint.decode(digest) == null) digest = sha256(digest)
        assertEquals(EdPoint.decode(digest), Spake2.derivePoint(seed))
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `matches BoringSSL message for message and key for key`(case: Vectors.Case) {
        val alice = client()
        val bob = server()
        assertEquals(
            case.text("alice_msg"),
            alice.generateMessage(case.hex("alice_pw"), case.hex("alice_seed")).toHex(),
        )
        assertEquals(case.text("bob_msg"), bob.generateMessage(case.hex("bob_pw"), case.hex("bob_seed")).toHex())
        assertEquals(case.text("alice_shared"), alice.processMessage(case.hex("bob_msg"))?.toHex())
        assertEquals(case.text("bob_shared"), bob.processMessage(case.hex("alice_msg"))?.toHex())
    }

    @Test
    fun `the vectors cover every cofactor correction and both outcomes`() {
        val patterns = Vectors.spake2.map { passwordScalar(it.hex("alice_pw")).toInt() and 7 }.toSet()
        assertEquals((0..7).toSet(), patterns)
        val agreeing = Vectors.spake2.count { it.text("alice_shared") == it.text("bob_shared") }
        assertEquals(8, agreeing)
        assertEquals(2, Vectors.spake2.size - agreeing, "a wrong code and a different exported key")
    }

    @Test
    fun `cofactor clearing keeps the scalar mod L and makes it a multiple of 8`() {
        for (low in 0L..7L) {
            val scalar = BigInteger.valueOf(1_000_000L * 8 + low)
            val cleared = Spake2.cofactorCleared(scalar)
            assertEquals(scalar.mod(EdPoint.L), cleared.mod(EdPoint.L), "low bits $low")
            assertEquals(0, cleared.toInt() and 7, "low bits $low")
        }
    }

    @Test
    fun `fresh randomness agrees on the same password and not on another`() {
        val (aliceKey, bobKey) = exchange("123456", "123456")
        assertEquals(64, aliceKey?.size)
        assertArrayEquals(aliceKey, bobKey)
        val (wrongAlice, wrongBob) = exchange("123456", "123457")
        assertFalse(wrongAlice.contentEquals(wrongBob))
    }

    @Test
    fun `a peer message that is not a point gives no key`() {
        val alice = client()
        alice.generateMessage(byteArrayOf(1), SecureRandom())
        assertNull(alice.processMessage(ByteArray(31)))
        val bob = server()
        bob.generateMessage(byteArrayOf(1), SecureRandom())
        assertNull(bob.processMessage(toLittleEndian(EdPoint.P, 32)))
    }

    @Test
    fun `one instance runs one exchange`() {
        val alice = client()
        assertThrows<IllegalStateException> { alice.processMessage(ByteArray(32)) }
        assertThrows<IllegalArgumentException> { alice.generateMessage(byteArrayOf(1), ByteArray(63)) }
        val message = alice.generateMessage(byteArrayOf(1), ByteArray(64))
        assertThrows<IllegalStateException> { alice.generateMessage(byteArrayOf(1), ByteArray(64)) }
        assertTrue(alice.processMessage(message) != null)
        assertThrows<IllegalStateException> { alice.processMessage(message) }
    }

    private fun exchange(
        alicePassword: String,
        bobPassword: String,
    ): Pair<ByteArray?, ByteArray?> {
        val random = SecureRandom()
        val alice = client()
        val bob = server()
        val toBob = alice.generateMessage(alicePassword.toByteArray(), random)
        val toAlice = bob.generateMessage(bobPassword.toByteArray(), random)
        return alice.processMessage(toAlice) to bob.processMessage(toBob)
    }

    private fun client(): Spake2 = Spake2(Spake2.Role.ALICE, PairingAuth.CLIENT_NAME, PairingAuth.SERVER_NAME)

    private fun server(): Spake2 = Spake2(Spake2.Role.BOB, PairingAuth.SERVER_NAME, PairingAuth.CLIENT_NAME)

    private fun passwordScalar(password: ByteArray): BigInteger =
        fromLittleEndian(MessageDigest.getInstance("SHA-512").digest(password)).mod(EdPoint.L)

    companion object {
        @JvmStatic
        fun vectors(): List<Vectors.Case> = Vectors.spake2
    }
}
