/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbPairing.kt.
 * Modified by PhoneAgent contributors: English documentation; failures are a typed result with
 * six classes instead of exceptions; the exchange runs over a socket the caller can close on
 * cancellation, with a socket read timeout behind it; header checks follow AOSP's ReadHeader
 * (version, type, 1..16384 bytes).
 */
package dev.phoneagent.core.adb

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLException

/** The six-digit code of Settings' "Pair device with pairing code" dialog. [toString] masks it. */
@JvmInline
public value class PairingCode(
    private val digits: String,
) {
    init {
        require(digits.length == LENGTH && digits.all { it in '0'..'9' }) { "a pairing code is $LENGTH ASCII digits" }
    }

    internal fun bytes(): ByteArray = digits.toByteArray(Charsets.US_ASCII)

    override fun toString(): String = "PairingCode(******)"

    private companion object {
        const val LENGTH = 6
    }
}

/** Why pairing failed. Each class asks the user for something different. */
public enum class PairingFailure {
    /** Nothing accepted a TCP connection at the endpoint (the pairing dialog is closed, or the port is stale). */
    UNREACHABLE,

    /** The TLS handshake or the keying material export failed. */
    TLS,

    /** The device closed the connection before the key exchange finished (it refused this attempt). */
    REJECTED,

    /** The key exchange completed but the keys differ: the code was wrong. */
    WRONG_CODE,

    /** The device broke the pairing wire format (a header, a curve point, the peer info). */
    PROTOCOL,

    /** No answer within [AdbConfig.pairingTimeoutMs]. */
    TIMEOUT,
}

/** What the device told us about itself when pairing succeeded. */
public data class PairedDevice(
    /** The device's adb GUID (the name of its `_adb-tls-connect` service), as sent; may be empty. */
    val guid: String,
)

/** Outcome of [AdbClient.pair]. */
public sealed interface PairingResult {
    /** The device stored our key; [AdbClient.connect] now works with this identity. */
    public data class Paired(
        /** The device's own description. */
        val device: PairedDevice,
    ) : PairingResult

    /** Pairing failed; nothing was stored on the device. */
    public data class Failed(
        /** The class of failure. */
        val reason: PairingFailure,
        /** Diagnostic text for logs; never contains the code or key material. */
        val detail: String,
    ) : PairingResult
}

/** A classified pairing failure travelling out of the blocking exchange. */
internal class PairingError(
    val reason: PairingFailure,
    detail: String,
    cause: Throwable? = null,
) : IOException(detail, cause)

/**
 * The client side of adb's pairing protocol (AOSP `pairing_connection.cpp`): TLS 1.3, export 64
 * bytes for "adb-label\0", password = code + exported bytes, exchange SPAKE2 messages, then swap
 * 8192-byte PeerInfo blocks under the agreed AES-GCM key (ours carries our adb public key, the
 * device's its GUID). Every packet has a 6-byte header: version 1, type (0 SPAKE2, 1 PEER_INFO),
 * payload length (big-endian uint32, 1..16384).
 */
internal class AdbPairing(
    private val tls: AdbTls,
    private val identity: AdbIdentity,
) {
    /**
     * Pairs over the unconnected [plain] socket, which the caller closes, within [timeoutMs] (the
     * caller's coroutine timeout; the socket's read timeout is the [socketTimeoutBackstop] of it).
     * Blocks; throws [PairingError].
     */
    @Throws(PairingError::class)
    fun pair(
        plain: Socket,
        endpoint: AdbEndpoint,
        code: PairingCode,
        timeoutMs: Long,
    ): PairedDevice {
        val address = InetSocketAddress(endpoint.host, endpoint.port)
        step(PairingFailure.UNREACHABLE) {
            plain.soTimeout = socketTimeoutBackstop(timeoutMs)
            plain.connect(address, timeoutMs.toSocketTimeout())
        }
        val socket = step(PairingFailure.TLS) { tls.handshake(plain, endpoint) }
        val exported = step(PairingFailure.TLS) { tls.export(socket, EXPORT_LABEL, EXPORT_SIZE) }
        val input = step(PairingFailure.TLS) { BufferedInputStream(socket.inputStream) }
        val output = step(PairingFailure.TLS) { socket.outputStream }
        val auth = PairingAuth.create(Spake2.Role.ALICE, code.bytes() + exported)
        exchangeKeys(input, output, auth)
        return parsePeerInfo(exchangePeerInfo(input, output, auth))
    }

    private fun exchangeKeys(
        input: InputStream,
        output: OutputStream,
        auth: PairingAuth,
    ) {
        step(PairingFailure.REJECTED) { writePacket(output, TYPE_SPAKE2, auth.message) }
        val theirs =
            step(PairingFailure.REJECTED) { readPacket(input, TYPE_SPAKE2) }
                ?: fail(PairingFailure.REJECTED, "device closed the connection before the key exchange")
        if (!auth.initCipher(theirs)) fail(PairingFailure.PROTOCOL, "device's SPAKE2 message is not a curve point")
    }

    /** Sends our PeerInfo and opens the device's. */
    private fun exchangePeerInfo(
        input: InputStream,
        output: OutputStream,
        auth: PairingAuth,
    ): ByteArray {
        step(PairingFailure.WRONG_CODE) { writePacket(output, TYPE_PEER_INFO, auth.encrypt(ourPeerInfo())) }
        // With a wrong code the device still sends its PeerInfo, then fails to open ours and hangs up.
        val sealed =
            step(PairingFailure.WRONG_CODE) { readPacket(input, TYPE_PEER_INFO) }
                ?: fail(PairingFailure.WRONG_CODE, "device closed the connection after the key exchange")
        return try {
            auth.decrypt(sealed)
        } catch (wrongKey: AEADBadTagException) {
            throw PairingError(PairingFailure.WRONG_CODE, "device's peer info does not authenticate", wrongKey)
        }
    }

    private fun ourPeerInfo(): ByteArray {
        val key = identity.adbPublicKey.toByteArray(Charsets.UTF_8)
        check(key.size < PEER_INFO_SIZE - 1) { "adb public key line too long" }
        return ByteArray(PEER_INFO_SIZE).also {
            it[0] = PEER_RSA_PUB_KEY
            key.copyInto(it, 1)
        }
    }

    private fun parsePeerInfo(info: ByteArray): PairedDevice {
        when {
            info.size != PEER_INFO_SIZE -> {
                fail(
                    PairingFailure.PROTOCOL,
                    "peer info has ${info.size} bytes, not $PEER_INFO_SIZE",
                )
            }

            info[0] != PEER_DEVICE_GUID -> {
                fail(
                    PairingFailure.PROTOCOL,
                    "peer info of type ${info[0]}, not a device GUID",
                )
            }
        }
        var end = 1
        while (end < info.size && info[end] != 0.toByte()) end++
        return PairedDevice(String(info, 1, end - 1, Charsets.UTF_8))
    }

    private fun fail(
        reason: PairingFailure,
        detail: String,
    ): Nothing = throw PairingError(reason, detail)

    /** Runs one I/O step, turning its failure into a [PairingError] of [reason] unless it is more specific. */
    private inline fun <T> step(
        reason: PairingFailure,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: IOException) {
            throw classify(failure, reason)
        }

    private fun classify(
        failure: IOException,
        reason: PairingFailure,
    ): PairingError =
        when {
            failure is PairingError -> failure
            failure.timedOut() -> PairingError(PairingFailure.TIMEOUT, describe(failure), failure)
            failure is TruncatedFrameException -> PairingError(reason, describe(failure), failure)
            failure is AdbProtocolException -> PairingError(PairingFailure.PROTOCOL, describe(failure), failure)
            failure is SSLException -> PairingError(PairingFailure.TLS, describe(failure), failure)
            else -> PairingError(reason, describe(failure), failure)
        }

    /** The socket read timeout fired, directly or inside the TLS handshake. */
    private fun IOException.timedOut(): Boolean =
        this is SocketTimeoutException || (this is TlsHandshakeException && cause is SocketTimeoutException)

    companion object {
        /** AOSP's `kExportedKeyLabel`: `sizeof("adb-label")` bytes, so the NUL is part of it. */
        const val EXPORT_LABEL: String = "adb-label\u0000"
        const val EXPORT_SIZE: Int = 64
        const val PEER_INFO_SIZE: Int = 8192
        const val MAX_PAYLOAD: Int = PEER_INFO_SIZE * 2
        const val VERSION: Byte = 1
        const val TYPE_SPAKE2: Byte = 0
        const val TYPE_PEER_INFO: Byte = 1
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1
        private const val HEADER_SIZE = 6

        @Throws(IOException::class)
        fun writePacket(
            output: OutputStream,
            type: Byte,
            payload: ByteArray,
        ) {
            val header =
                ByteBuffer
                    .allocate(HEADER_SIZE)
                    .put(VERSION)
                    .put(type)
                    .putInt(payload.size)
                    .array()
            output.write(header + payload)
            output.flush()
        }

        /**
         * The payload of the next packet, which must be of [expectedType]; `null` when the stream
         * ends before a header. Throws [AdbProtocolException] for a bad header and
         * [TruncatedFrameException] when the stream ends inside the packet.
         */
        @Throws(IOException::class)
        fun readPacket(
            input: InputStream,
            expectedType: Byte,
        ): ByteArray? {
            val header = readFully(input, HEADER_SIZE, "pairing header") ?: return null
            val buffer = ByteBuffer.wrap(header)
            val version = buffer.get()
            val type = buffer.get()
            val length = buffer.getInt().toUInt().toLong()
            when {
                version != VERSION -> protocolError("unsupported pairing packet version $version")
                type != expectedType -> protocolError("pairing packet of type $type, expected $expectedType")
                length !in 1..MAX_PAYLOAD -> protocolError("pairing packet payload of $length bytes")
            }
            return readFully(input, length.toInt(), "pairing payload")
                ?: throw TruncatedFrameException("stream ended before the pairing payload")
        }
    }
}

/** A failure's class name and message, for diagnostics. */
internal fun describe(failure: Throwable): String = "${failure.javaClass.simpleName}: ${failure.message}"
