/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/test/kotlin/com/wangzan101/mika/core/adb/TestTls.kt
 * (FakePairingServer).
 * Modified by PhoneAgent contributors: thirteen broken behaviors instead of three; AOSP's order
 * (both sides write PeerInfo before reading); its own wire constants and framing; barriers.
 */
package dev.phoneagent.core.adb

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.concurrent.thread

/**
 * A device's pairing service in-process, following AOSP `pairing_connection.cpp` as the server:
 * TLS 1.3 (any client certificate), export, SPAKE2 as Bob, then both sides write their PeerInfo
 * before reading the other's; a PeerInfo that does not decrypt ends the connection. [behavior]
 * breaks one step on purpose. Serves one connection. The wire constants and the framing are its
 * own, taken from AOSP (see the companion), so that a wrong constant in the client cannot pass
 * against a fake that shares it; only SPAKE2 and the cipher, pinned by BoringSSL's vectors, are shared.
 */
class FakePairingServer(
    private val code: String,
    private val behavior: Behavior = Behavior.NORMAL,
    private val guid: String = "adb-FAKE0001-abc123",
) : AutoCloseable {
    enum class Behavior {
        NORMAL,
        NOT_TLS,
        CLOSE_BEFORE_SPAKE2,
        SILENT_AFTER_SPAKE2,
        TRUNCATED_SPAKE2,
        BAD_VERSION,
        WRONG_TYPE,
        EMPTY_PAYLOAD,
        OVERSIZED_PAYLOAD,
        NOT_A_POINT,
        CLOSE_AFTER_PEER_INFO,
        SHORT_PEER_INFO,
        WRONG_PEER_INFO_TYPE,
    }

    /** What the server does after a step. */
    private enum class Next { GO_ON, HANG_UP, WAIT_FOR_CLIENT }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val endpoint: AdbEndpoint get() = AdbEndpoint("127.0.0.1", server.localPort)

    /** The adb public key line the client sent, once it decrypted. */
    val receivedKey = CompletableFuture<String>()

    /** The encoded public key of the client's TLS certificate. */
    val clientCertificateKey = CompletableFuture<ByteArray>()

    /** Completes when the client's SPAKE2 message arrived (a barrier for cancellation tests). */
    val gotSpake2 = CompletableFuture<Unit>()

    /** Completes when the client closed the connection (it read end of stream or a reset). */
    val clientClosed = CompletableFuture<Unit>()

    private val failure = CompletableFuture<Throwable?>()
    private val tls = TestTls.serverContext(TestTls.device) { clientCertificateKey.complete(it.publicKey.encoded) }

    @Volatile private var current: Socket? = null

    private val worker =
        thread(isDaemon = true, name = "fake-pairing") {
            try {
                server.accept().also { current = it }.use(::serve)
                failure.complete(null)
            } catch (broken: Exception) {
                failure.complete(broken)
            }
        }

    private fun serve(plain: Socket) {
        if (behavior == Behavior.NOT_TLS) {
            plain.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            awaitClose(plain.getInputStream())
            return
        }
        TestTls.serve(tls, plain).use { socket ->
            val exported = TestTls.export(socket, EXPORT_LABEL, EXPORT_SIZE)
            val auth = PairingAuth.create(Spake2.Role.BOB, code.toByteArray() + exported)
            val input = BufferedInputStream(socket.inputStream)
            val output = socket.outputStream
            var next = exchangeSpake2(input, output, auth)
            if (next == Next.GO_ON) next = exchangePeerInfo(input, output, auth)
            if (next == Next.WAIT_FOR_CLIENT) awaitClose(input)
        }
    }

    private fun exchangeSpake2(
        input: InputStream,
        output: OutputStream,
        auth: PairingAuth,
    ): Next {
        val theirs = readPacket(input, TYPE_SPAKE2)
        gotSpake2.complete(Unit)
        output.write(spake2Reply(auth))
        output.flush()
        return when {
            behavior == Behavior.CLOSE_BEFORE_SPAKE2 || behavior == Behavior.TRUNCATED_SPAKE2 -> Next.HANG_UP
            behavior == Behavior.SILENT_AFTER_SPAKE2 || behavior in BROKEN_SPAKE2 -> Next.WAIT_FOR_CLIENT
            else -> Next.GO_ON.also { check(auth.initCipher(theirs)) { "client's SPAKE2 message is not a point" } }
        }
    }

    /** Our SPAKE2 packet, or what [behavior] sends instead (nothing, a broken header or payload). */
    private fun spake2Reply(auth: PairingAuth): ByteArray =
        when {
            behavior == Behavior.CLOSE_BEFORE_SPAKE2 || behavior == Behavior.SILENT_AFTER_SPAKE2 -> ByteArray(0)
            behavior == Behavior.TRUNCATED_SPAKE2 -> header(VERSION, TYPE_SPAKE2, 32) + ByteArray(10)
            behavior == Behavior.BAD_VERSION -> header(2, TYPE_SPAKE2, 32) + auth.message
            behavior == Behavior.WRONG_TYPE -> header(VERSION, TYPE_PEER_INFO, 32) + auth.message
            behavior == Behavior.EMPTY_PAYLOAD -> header(VERSION, TYPE_SPAKE2, 0)
            behavior == Behavior.OVERSIZED_PAYLOAD -> header(VERSION, TYPE_SPAKE2, MAX_PAYLOAD + 1)
            behavior == Behavior.NOT_A_POINT -> header(VERSION, TYPE_SPAKE2, 32) + toLittleEndian(2.toBigInteger(), 32)
            else -> header(VERSION, TYPE_SPAKE2, 32) + auth.message
        }

    private fun exchangePeerInfo(
        input: InputStream,
        output: OutputStream,
        auth: PairingAuth,
    ): Next {
        val mine = ByteArray(if (behavior == Behavior.SHORT_PEER_INFO) 100 else PEER_INFO_SIZE)
        mine[0] = if (behavior == Behavior.WRONG_PEER_INFO_TYPE) PEER_RSA_PUB_KEY else PEER_DEVICE_GUID
        guid.toByteArray().copyInto(mine, 1)
        if (behavior != Behavior.CLOSE_AFTER_PEER_INFO) {
            val sealed = auth.encrypt(mine)
            output.write(header(VERSION, TYPE_PEER_INFO, sealed.size) + sealed)
            output.flush()
        }
        // As adbd: a PeerInfo that does not decrypt ends the attempt.
        val info = readOrNull(input)?.let { decryptOrNull(auth, it) }
        if (info != null) {
            check(info.size == PEER_INFO_SIZE && info[0] == PEER_RSA_PUB_KEY) { "client's PeerInfo is malformed" }
            receivedKey.complete(
                String(info, 1, (1 until info.size).first { info[it] == 0.toByte() } - 1, Charsets.UTF_8),
            )
        }
        return if (info == null || behavior == Behavior.CLOSE_AFTER_PEER_INFO) Next.HANG_UP else Next.WAIT_FOR_CLIENT
    }

    private fun decryptOrNull(
        auth: PairingAuth,
        sealed: ByteArray,
    ): ByteArray? =
        try {
            auth.decrypt(sealed)
        } catch (expectedWrongCode: AEADBadTagException) {
            null
        }

    /** The client's PeerInfo, or `null` when it hung up first (after a failure of its own). */
    private fun readOrNull(input: InputStream): ByteArray? =
        try {
            readPacket(input, TYPE_PEER_INFO)
        } catch (expectedHangUp: IOException) {
            null
        }

    /** The payload of the next packet, which must be well formed and of [type] (AOSP `ReadHeader`). */
    private fun readPacket(
        input: InputStream,
        type: Int,
    ): ByteArray {
        val data = DataInputStream(input)
        val version = data.readUnsignedByte()
        val actualType = data.readUnsignedByte()
        val length = data.readInt()
        check(version == VERSION && actualType == type && length in 1..MAX_PAYLOAD) {
            "client sent a bad header: version $version, type $actualType, $length bytes"
        }
        return ByteArray(length).also(data::readFully)
    }

    private fun awaitClose(input: InputStream) {
        try {
            while (input.read() >= 0) continue
        } catch (expectedReset: IOException) {
            // A reset is a close as well.
        }
        clientClosed.complete(Unit)
    }

    /** Waits for the server thread; returns its own failure (a broken test), if any. */
    fun join(): Throwable? = failure.get(10, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        current?.close()
        worker.join(TimeUnit.SECONDS.toMillis(10))
    }

    private companion object {
        // AOSP pairing_connection.cpp / tls_connection.cpp: kExportedKeyLabel is "adb-label" passed
        // with sizeof (so its NUL counts), kExportedKeySize 64; PeerInfo is 8192 bytes, its type 0
        // an RSA public key and 1 a device GUID; headers are version 1, type 0 (SPAKE2) or 1
        // (PeerInfo), a big-endian length of 1..16384.
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64
        const val PEER_INFO_SIZE = 8192
        const val PEER_RSA_PUB_KEY: Byte = 0
        const val PEER_DEVICE_GUID: Byte = 1
        const val VERSION = 1
        const val TYPE_SPAKE2 = 0
        const val TYPE_PEER_INFO = 1
        const val MAX_PAYLOAD = 16_384

        val BROKEN_SPAKE2 =
            setOf(
                Behavior.BAD_VERSION,
                Behavior.WRONG_TYPE,
                Behavior.EMPTY_PAYLOAD,
                Behavior.OVERSIZED_PAYLOAD,
                Behavior.NOT_A_POINT,
            )

        fun header(
            version: Int,
            type: Int,
            length: Int,
        ): ByteArray =
            ByteBuffer
                .allocate(6)
                .put(version.toByte())
                .put(type.toByte())
                .putInt(length)
                .array()
    }
}
