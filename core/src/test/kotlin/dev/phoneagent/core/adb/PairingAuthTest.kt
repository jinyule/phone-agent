package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import javax.crypto.AEADBadTagException

class PairingAuthTest {
    @Test
    fun `HKDF matches RFC 5869 test cases 1 to 3`() {
        // A.1: basic.
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf
                .sha256(
                    hexBytes("0b".repeat(22)),
                    hexBytes("000102030405060708090a0b0c"),
                    hexBytes("f0f1f2f3f4f5f6f7f8f9"),
                    42,
                ).toHex(),
        )
        // A.2: longer inputs and outputs (three blocks).
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hkdf.sha256(ikm, salt, info, 82).toHex(),
        )
        // A.3: zero-length salt and info; a missing salt is the same.
        val a3 = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertEquals(a3, Hkdf.sha256(hexBytes("0b".repeat(22)), ByteArray(0), ByteArray(0), 42).toHex())
        assertEquals(a3, Hkdf.sha256(hexBytes("0b".repeat(22)), null, ByteArray(0), 42).toHex())
    }

    @Test
    fun `HKDF output length is bounded`() {
        assertThrows<IllegalArgumentException> { Hkdf.sha256(ByteArray(1), null, ByteArray(0), 0) }
        assertThrows<IllegalArgumentException> { Hkdf.sha256(ByteArray(1), null, ByteArray(0), 255 * 32 + 1) }
        assertEquals(255 * 32, Hkdf.sha256(ByteArray(1), null, ByteArray(0), 255 * 32).size)
    }

    @ParameterizedTest
    @MethodSource("vectors")
    fun `the cipher matches BoringSSL's AEAD as AOSP keys and numbers it`(case: Vectors.Case) {
        assertEquals(case.text("alice_aes"), Hkdf.sha256(case.hex("alice_shared"), null, INFO, 16).toHex())
        val alice = PairingCipher(case.hex("alice_shared"))
        val plain = case.hex("plaintext")
        assertEquals(case.text("alice_sealed0"), alice.encrypt(plain).toHex())
        assertEquals(case.text("alice_sealed1"), alice.encrypt(plain).toHex())

        val bob = PairingCipher(case.hex("bob_shared"))
        if (case.text("alice_shared") == case.text("bob_shared")) {
            assertArrayEquals(plain, bob.decrypt(case.hex("alice_sealed0")))
            assertArrayEquals(plain, bob.decrypt(case.hex("alice_sealed1")))
        } else {
            assertThrows<AEADBadTagException> { bob.decrypt(case.hex("alice_sealed0")) }
        }
    }

    @Test
    fun `a failed decryption does not use up a sequence number`() {
        val case = Vectors.spake2.first()
        val bob = PairingCipher(case.hex("bob_shared"))
        assertThrows<AEADBadTagException> { bob.decrypt(case.hex("alice_sealed1")) }
        assertArrayEquals(case.hex("plaintext"), bob.decrypt(case.hex("alice_sealed0")))
    }

    @Test
    fun `pairing auth with a fixed seed is the vector's client`() {
        val case = Vectors.spake2.first()
        val auth = PairingAuth.create(Spake2.Role.ALICE, case.hex("alice_pw"), case.hex("alice_seed"))
        assertEquals(case.text("alice_msg"), auth.message.toHex())
        assertThrows<IllegalStateException> { auth.encrypt(ByteArray(1)) }
        assertThrows<IllegalStateException> { auth.decrypt(ByteArray(17)) }
        assertTrue(auth.initCipher(case.hex("bob_msg")))
        assertThrows<IllegalStateException> { auth.initCipher(case.hex("bob_msg")) }
        assertEquals(case.text("alice_sealed0"), auth.encrypt(case.hex("plaintext")).toHex())
        assertArrayEquals(case.hex("plaintext"), auth.decrypt(case.hex("bob_sealed0")))
    }

    @Test
    fun `client and device with random seeds talk, a malformed message does not start a cipher`() {
        val password = "000000".toByteArray()
        val client = PairingAuth.create(Spake2.Role.ALICE, password)
        val device = PairingAuth.create(Spake2.Role.BOB, password)
        assertTrue(client.initCipher(device.message))
        assertTrue(device.initCipher(client.message))
        assertArrayEquals(byteArrayOf(1, 2, 3), device.decrypt(client.encrypt(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(4), client.decrypt(device.encrypt(byteArrayOf(4))))

        val broken = PairingAuth.create(Spake2.Role.ALICE, password)
        assertFalse(broken.initCipher(ByteArray(5)))
        assertThrows<IllegalStateException> { broken.encrypt(ByteArray(1)) }
        assertEquals("adb pair client\u0000", PairingAuth.CLIENT_NAME.toString(Charsets.US_ASCII))
        assertEquals("adb pair server\u0000", PairingAuth.SERVER_NAME.toString(Charsets.US_ASCII))
    }

    companion object {
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray()

        @JvmStatic
        fun vectors(): List<Vectors.Case> = Vectors.spake2
    }
}
