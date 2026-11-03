package dev.phoneagent.core.adb

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec.F4
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbIdentityTest {
    private val identity = TestTls.client

    @ParameterizedTest
    @MethodSource("publicKeys")
    fun `encodes public keys exactly as AOSP android_pubkey_encode`(case: Vectors.Case) {
        val spec = RSAPublicKeySpec(BigInteger(case.text("modulus"), 16), BigInteger(case.text("exponent")))
        val key = KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
        assertEquals(case.text("encoded"), Base64.getEncoder().encodeToString(AdbPublicKey.encode(key)))
        assertEquals(key, TestTls.decodeAdbKey(case.text("encoded")))
    }

    @Test
    fun `only RSA-2048 keys have the adb format`() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        val small = generator.generateKeyPair().public as RSAPublicKey
        assertThrows<IllegalArgumentException> { AdbPublicKey.encode(small) }
        assertEquals(524, AdbPublicKey.SIZE)
    }

    @Test
    fun `the public key line is the encoded key and the sanitized name`() {
        val named = AdbIdentity.generate("phone agent\tbox\nx")
        assertEquals("phone_agent_box_x", named.name)
        val (key, name) = named.adbPublicKey.split(' ')
        assertEquals("phone_agent_box_x", name)
        assertEquals(named.rsaPublicKey, TestTls.decodeAdbKey(key))
        val structure = Base64.getDecoder().decode(key)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(structure).toHex(), named.fingerprint)
    }

    @Test
    fun `the certificate is self-signed over the identity's key`() {
        val certificate = identity.certificate
        certificate.verify(identity.rsaPublicKey)
        certificate.checkValidity()
        assertEquals(identity.rsaPublicKey, certificate.publicKey)
        assertEquals("CN=PhoneAgent", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertEquals(3, certificate.version)
        assertEquals("SHA256withRSA", certificate.sigAlgName)
        assertSame(certificate, identity.certificate)
    }

    @Test
    fun `toString shows the name and a fingerprint prefix, never key material`() {
        val text = identity.toString()
        assertEquals("AdbIdentity(name=${identity.name}, fingerprint=${identity.fingerprint.take(16)}…)", text)
        val secrets = listOf(identity.privateKey.encoded.toHex(), identity.rsaPublicKey.modulus.toString(16))
        for (secret in secrets) assertFalse(text.contains(secret.take(16)))
        assertFalse(AdbClient(identity, TestTls.exporter).toString().contains(identity.adbPublicKey.take(16)))
    }

    @Test
    fun `DER lengths use the short form and one to three length bytes`() {
        fun header(
            size: Int,
            bytes: Int,
        ) = SelfSignedCertificate.tlv(0x04, ByteArray(size)).copyOf(bytes).toHex()
        assertEquals("0400", header(0, 2))
        assertEquals("047f", header(0x7f, 2))
        assertEquals("048180", header(0x80, 3))
        assertEquals("0481ff", header(0xff, 3))
        assertEquals("04820100", header(0x100, 4))
        assertEquals("0482ffff", header(0xffff, 4))
        assertEquals("0483010000", header(0x10000, 5))
        assertEquals(0x10000 + 5, SelfSignedCertificate.tlv(0x04, ByteArray(0x10000)).size)
    }

    @Test
    fun `loadOrCreate creates, stores and reads back the key`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val store = MemoryStore()
            val created = AdbIdentity.loadOrCreate(store, "phoneagent@phone", io)
            assertEquals(IdentityLoad.Origin.CREATED, created.origin)
            assertEquals(1, store.writes)
            val stored = AdbIdentity.loadOrCreate(store, "phoneagent@phone", io)
            assertEquals(IdentityLoad.Origin.STORED, stored.origin)
            assertEquals(created.identity.fingerprint, stored.identity.fingerprint)
            assertEquals(created.identity.adbPublicKey, stored.identity.adbPublicKey)
            assertEquals(1, store.writes)
            assertEquals(IdentityLoad.Origin.STORED, AdbIdentity.loadOrCreate(store, "default io").origin)
        }

    @Test
    fun `loadOrCreate replaces bytes that are not an RSA-2048 key`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
            val ec = KeyPairGenerator.getInstance("EC").generateKeyPair()
            for (bytes in listOf(byteArrayOf(1, 2, 3), small.private.encoded, ec.private.encoded)) {
                val store = MemoryStore(bytes)
                val load = AdbIdentity.loadOrCreate(store, "x", io)
                assertEquals(IdentityLoad.Origin.REPLACED, load.origin)
                assertArrayEquals(load.identity.privateKey.encoded, store.bytes)
            }
        }

    @Test
    fun `loadOrCreate replaces keys that parse but cannot be used`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            // Never derived from the shared test keys: the JDK caches RSA blinding values per modulus,
            // and a broken key with the same modulus would spoil them for the good one.
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, F4)) }
            val key = generator.generateKeyPair().private as RSAPrivateCrtKey
            val blobs =
                mapOf(
                    "public exponent 2: the public key cannot be rebuilt" to crt(key, exponent = BigInteger.TWO),
                    "public exponent 3: an RSA key, but not one we create" to
                        crt(
                            key,
                            exponent = BigInteger.valueOf(3),
                        ),
                    "CRT values that do not belong together: it cannot sign" to
                        crt(key, primeExponentP = key.primeExponentP + BigInteger.TWO),
                )
            for ((what, blob) in blobs) {
                val store = MemoryStore(blob)
                val load = AdbIdentity.loadOrCreate(store, "x", io)
                assertEquals(IdentityLoad.Origin.REPLACED, load.origin, what)
                assertArrayEquals(load.identity.privateKey.encoded, store.bytes, what)
            }
        }

    @Test
    fun `a name too long for the device's key line fails when loading`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val store = MemoryStore(identity.privateKey.encoded)
            val longest = AdbIdentity.loadOrCreate(store, "x".repeat(7_489), io).identity
            assertEquals(8_190, longest.adbPublicKey.toByteArray().size, "one byte below PeerInfo's 8191 for the NUL")
            for (name in listOf("x".repeat(7_490), "\u00e9".repeat(3_745), "x".repeat(8_000))) {
                assertThrows<IllegalArgumentException> { AdbIdentity.loadOrCreate(store, name, io) }
                assertThrows<IllegalArgumentException> { AdbIdentity.generate(name) }
                assertThrows<IllegalArgumentException> { AdbIdentity.fromPkcs8(identity.privateKey.encoded, name) }
            }
        }

    @Test
    fun `loadOrCreate needs a name and lets store failures through`() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            assertThrows<IllegalArgumentException> { AdbIdentity.loadOrCreate(MemoryStore(), " ", io) }
            val broken =
                object : AdbKeyStore {
                    override fun read(): ByteArray = throw IOException("disk gone")

                    override fun write(pkcs8: ByteArray) = throw IOException("disk gone")
                }
            assertEquals("disk gone", assertThrows<IOException> { AdbIdentity.loadOrCreate(broken, "x", io) }.message)
        }

    @Test
    fun `fromPkcs8 reads only RSA-2048 private keys`() {
        val copy = AdbIdentity.fromPkcs8(identity.privateKey.encoded, "copy")
        assertEquals(identity.fingerprint, copy?.fingerprint)
        assertNull(AdbIdentity.fromPkcs8(ByteArray(0), "x"))
        assertTrue(AdbIdentity.generate("a").fingerprint != AdbIdentity.generate("a").fingerprint)
    }

    /** [key] re-encoded as PKCS#8 with one of its values replaced. */
    private fun crt(
        key: RSAPrivateCrtKey,
        exponent: BigInteger = key.publicExponent,
        primeExponentP: BigInteger = key.primeExponentP,
    ): ByteArray {
        val spec =
            RSAPrivateCrtKeySpec(
                key.modulus,
                exponent,
                key.privateExponent,
                key.primeP,
                key.primeQ,
                primeExponentP,
                key.primeExponentQ,
                key.crtCoefficient,
            )
        return KeyFactory.getInstance("RSA").generatePrivate(spec).encoded
    }

    private class MemoryStore(
        var bytes: ByteArray? = null,
    ) : AdbKeyStore {
        var writes = 0

        override fun read(): ByteArray? = bytes

        override fun write(pkcs8: ByteArray) {
            writes++
            bytes = pkcs8
        }
    }

    companion object {
        @JvmStatic
        fun publicKeys(): List<Vectors.Case> = Vectors.adbPublicKeys
    }
}
