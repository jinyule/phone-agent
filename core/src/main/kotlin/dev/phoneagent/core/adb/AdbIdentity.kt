/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbIdentity.kt.
 * Modified by PhoneAgent contributors: English documentation; the key pair is private and never
 * printed; storage is a port without file paths (the host encrypts); loading reports whether the
 * key was stored, created or replaced; a stored key that parses but cannot sign is replaced too;
 * key names are sanitized and their length checked on load; certificate subject PhoneAgent.
 */
package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Where the host keeps the adb private key: an opaque PKCS#8 blob that core never writes to a
 * path. The host encrypts it at rest (on Android with a Keystore-wrapped key in app-private
 * storage). Both methods block and are called on an IO thread.
 */
public interface AdbKeyStore {
    /** The stored PKCS#8 bytes, or `null` when nothing is stored. Throws [IOException] when reading fails. */
    @Throws(IOException::class)
    public fun read(): ByteArray?

    /** Replaces the stored key with [pkcs8] durably (atomically) before returning; throws [IOException] on failure. */
    @Throws(IOException::class)
    public fun write(pkcs8: ByteArray)
}

/**
 * This app's adb identity: an RSA-2048 key pair and a self-signed certificate over it. Pairing
 * hands [adbPublicKey] to the device; every later TLS connection presents the certificate, and
 * adbd compares its public key with the paired ones. The private key never leaves this class
 * except as the PKCS#8 blob given to [AdbKeyStore.write]; [toString] shows only [name] and a
 * fingerprint prefix.
 */
public class AdbIdentity private constructor(
    private val keyPair: KeyPair,
    name: String,
) {
    /**
     * The key's label in the device's list of paired devices: the requested name with every
     * whitespace character replaced by `_` (adbd and Settings split the key line on whitespace).
     */
    public val name: String = sanitize(name)

    init {
        requireNameFits(this.name)
    }

    internal val privateKey: PrivateKey get() = keyPair.private

    internal val rsaPublicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    internal val certificate: X509Certificate by lazy { SelfSignedCertificate.create(keyPair) }

    /** The line adbd stores for this key: base64 of adb's 524-byte key structure, a space, [name]. */
    public val adbPublicKey: String by lazy {
        val encoded = Base64.getEncoder().encodeToString(AdbPublicKey.encode(rsaPublicKey))
        "$encoded ${this.name}"
    }

    /** SHA-256 of adb's key structure, lowercase hex: tells identities apart without showing the key. */
    public val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(AdbPublicKey.encode(rsaPublicKey)).toHex()
    }

    override fun toString(): String = "AdbIdentity(name=$name, fingerprint=${fingerprint.take(FINGERPRINT_SHOWN)}…)"

    /** Loading and creating identities. */
    public companion object {
        private const val KEY_BITS = 2048
        private const val FINGERPRINT_SHOWN = 16
        private const val PROBE_ALGORITHM = "SHA256withRSA"
        private val WHITESPACE = Regex("\\s")

        /**
         * The longest [name], in UTF-8 bytes after sanitizing: pairing sends [adbPublicKey] (700
         * base64 bytes, a space, the name) NUL-terminated in the 8191 data bytes of a PeerInfo.
         */
        public const val MAX_NAME_BYTES: Int =
            AdbPairing.PEER_INFO_SIZE - 2 - (AdbPublicKey.SIZE + 2) / 3 * 4 - 1

        /**
         * The identity stored in [store], or a new one written to it when nothing is stored or the
         * stored bytes are not a usable RSA-2048 key ([IdentityLoad.Origin] says which; after a
         * replacement the device must be paired again). Usable means PKCS#8 that parses, exponent
         * 65537, and a key pair that signs and verifies. [name] must not be blank and must fit
         * [MAX_NAME_BYTES]; both are checked before the store is read. Runs on [io]; store
         * failures propagate as [IOException].
         */
        public suspend fun loadOrCreate(
            store: AdbKeyStore,
            name: String,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): IdentityLoad {
            require(name.isNotBlank()) { "adb key name must not be blank" }
            requireNameFits(sanitize(name))
            return withContext(io) {
                val stored = store.read()
                val loaded = stored?.let { fromPkcs8(it, name) }
                when {
                    loaded != null -> {
                        IdentityLoad(loaded, IdentityLoad.Origin.STORED)
                    }

                    else -> {
                        val created = generate(name)
                        store.write(created.keyPair.private.encoded)
                        val origin = if (stored == null) IdentityLoad.Origin.CREATED else IdentityLoad.Origin.REPLACED
                        IdentityLoad(created, origin)
                    }
                }
            }
        }

        internal fun generate(name: String): AdbIdentity {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4))
            return AdbIdentity(generator.generateKeyPair(), name)
        }

        /** The identity in [pkcs8], or `null` when it is not a usable RSA-2048 PKCS#8 private key. */
        internal fun fromPkcs8(
            pkcs8: ByteArray,
            name: String,
        ): AdbIdentity? {
            val factory = KeyFactory.getInstance("RSA")
            // We only create F4 keys, and adb's key structure has room for a 32-bit exponent only.
            val private =
                privateKey(factory, pkcs8)?.takeIf {
                    it.modulus.bitLength() == KEY_BITS && it.publicExponent == RSAKeyGenParameterSpec.F4
                }
            val pair = private?.let { key -> publicKey(factory, key)?.let { KeyPair(it, key) } }
            return pair?.takeIf(::signs)?.let { AdbIdentity(it, name) }
        }

        private fun sanitize(name: String): String = WHITESPACE.replace(name, "_")

        private fun requireNameFits(name: String) {
            val size = name.toByteArray(Charsets.UTF_8).size
            require(size <= MAX_NAME_BYTES) { "adb key name has $size UTF-8 bytes, at most $MAX_NAME_BYTES fit" }
        }

        private fun publicKey(
            factory: KeyFactory,
            private: RSAPrivateCrtKey,
        ): RSAPublicKey? =
            try {
                factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent)) as? RSAPublicKey
            } catch (ignoredUnusable: InvalidKeySpecException) {
                // The stored bytes are not to be trusted: they parse, but hold numbers no usable key has.
                null
            }

        /** Whether [pair] signs and verifies: parameters that parse may still disagree (a wrong CRT exponent). */
        private fun signs(pair: KeyPair): Boolean =
            try {
                roundTrip(pair)
            } catch (ignoredUnusable: GeneralSecurityException) {
                // The stored bytes are not to be trusted: the key they hold cannot sign.
                false
            }

        private fun roundTrip(pair: KeyPair): Boolean {
            val data = PROBE_ALGORITHM.toByteArray(Charsets.US_ASCII)
            val signer = Signature.getInstance(PROBE_ALGORITHM)
            signer.initSign(pair.private)
            signer.update(data)
            val signature = signer.sign()
            val verifier = Signature.getInstance(PROBE_ALGORITHM)
            verifier.initVerify(pair.public)
            verifier.update(data)
            return verifier.verify(signature)
        }

        private fun privateKey(
            factory: KeyFactory,
            pkcs8: ByteArray,
        ): RSAPrivateCrtKey? =
            try {
                factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as? RSAPrivateCrtKey
            } catch (ignoredUnreadable: GeneralSecurityException) {
                // Not a key we can use (corrupt, foreign or truncated): the caller replaces it.
                // Dropped on purpose, so no message about the key bytes travels further.
                null
            }
    }
}

/** Result of [AdbIdentity.loadOrCreate]. */
public data class IdentityLoad(
    /** The identity to use. */
    val identity: AdbIdentity,
    /** Where it came from. */
    val origin: Origin,
) {
    /** Where an identity came from. */
    public enum class Origin {
        /** Read back from the store. */
        STORED,

        /** Nothing was stored; a new key was created and stored. */
        CREATED,

        /** The stored bytes were not a usable key; a new key replaced them, so pairing must be redone. */
        REPLACED,
    }
}

/**
 * adb's RSA public key structure (`android_pubkey_encode`), 524 bytes, little-endian words:
 * modulus size in words (64), n0inv = −1/n mod 2^32, modulus n, rr = (2^2048)^2 mod n, exponent.
 */
internal object AdbPublicKey {
    private const val MODULUS_BYTES = 256
    private const val WORDS = MODULUS_BYTES / Int.SIZE_BYTES
    private const val WORD_BITS = 32

    /** Size of the encoded structure. */
    const val SIZE: Int = Int.SIZE_BYTES * 2 + MODULUS_BYTES * 2 + Int.SIZE_BYTES

    fun encode(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() == MODULUS_BYTES * Byte.SIZE_BITS) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(WORD_BITS)
        val n0inv = r32 - n.mod(r32).modInverse(r32)
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * Byte.SIZE_BITS * 2).mod(n)
        return ByteBuffer
            .allocate(SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(WORDS)
            .putInt(n0inv.toInt())
            .put(toLittleEndian(n, MODULUS_BYTES))
            .put(toLittleEndian(rr, MODULUS_BYTES))
            .putInt(key.publicExponent.toInt())
            .array()
    }
}

/**
 * A minimal self-signed X.509 v3 certificate without extensions: CN=PhoneAgent, valid from
 * 1970-01-01 to 2099-12-31, signed with SHA256withRSA. adbd looks only at its public key.
 */
internal object SelfSignedCertificate {
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_UTF8 = 0x0c
    private const val TAG_UTC_TIME = 0x17
    private const val TAG_GENERALIZED_TIME = 0x18
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_VERSION = 0xa0
    private const val X509_V3 = 2
    private const val SHORT_LENGTH_LIMIT = 0x80
    private const val LONG_LENGTH_ONE_BYTE = 0x81
    private const val LONG_LENGTH_TWO_BYTES = 0x82
    private const val LONG_LENGTH_THREE_BYTES = 0x83
    private const val ONE_BYTE_LIMIT = 0x100
    private const val TWO_BYTES_LIMIT = 0x10000
    private const val BYTE_MASK = 0xff
    private val SHA256_WITH_RSA = hexBytes("2a864886f70d01010b")
    private val COMMON_NAME = hexBytes("550403")

    fun create(keyPair: KeyPair): X509Certificate {
        val algorithm = sequence(tlv(TAG_OID, SHA256_WITH_RSA), tlv(TAG_NULL, ByteArray(0)))
        val name =
            sequence(tlv(TAG_SET, sequence(tlv(TAG_OID, COMMON_NAME), tlv(TAG_UTF8, "PhoneAgent".toByteArray()))))
        val validity =
            sequence(
                tlv(TAG_UTC_TIME, "700101000000Z".toByteArray()),
                tlv(TAG_GENERALIZED_TIME, "20991231235959Z".toByteArray()),
            )
        val toBeSigned =
            sequence(
                tlv(TAG_VERSION, tlv(TAG_INTEGER, byteArrayOf(X509_V3.toByte()))),
                tlv(TAG_INTEGER, byteArrayOf(1)),
                algorithm,
                name,
                validity,
                name,
                keyPair.public.encoded,
            )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(toBeSigned)
        val der = sequence(toBeSigned, algorithm, tlv(TAG_BIT_STRING, byteArrayOf(0) + signer.sign()))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun sequence(vararg parts: ByteArray): ByteArray {
        val content = ByteArrayOutputStream()
        parts.forEach { content.write(it) }
        return tlv(TAG_SEQUENCE, content.toByteArray())
    }

    /** DER tag-length-value with the definite length form (contents below 16 MiB). */
    fun tlv(
        tag: Int,
        content: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < SHORT_LENGTH_LIMIT -> {
                out.write(n)
            }

            n < ONE_BYTE_LIMIT -> {
                out.write(LONG_LENGTH_ONE_BYTE)
                out.write(n)
            }

            n < TWO_BYTES_LIMIT -> {
                out.write(LONG_LENGTH_TWO_BYTES)
                out.write(n ushr Byte.SIZE_BITS)
                out.write(n and BYTE_MASK)
            }

            else -> {
                out.write(LONG_LENGTH_THREE_BYTES)
                out.write(n ushr (2 * Byte.SIZE_BITS))
                out.write((n ushr Byte.SIZE_BITS) and BYTE_MASK)
                out.write(n and BYTE_MASK)
            }
        }
        out.write(content)
        return out.toByteArray()
    }
}
