/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/test/kotlin/com/wangzan101/mika/core/adb/AdbConnectionTest.kt
 * (FakeAdbd).
 * Modified by PhoneAgent contributors: blocking per-connection serving instead of an event loop;
 * the key check inside the TLS handshake; broken handshake and stream behaviors; the shell
 * protocol with stdin and exit status; barriers.
 */
package dev.phoneagent.core.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * adbd's wireless-debugging port in-process (AOSP `adb.cpp`, `daemon/services.cpp`): CNXN →
 * STLS, TLS 1.3 that accepts only [authorized] client keys (as `adbd_tls_verify_cert`), CNXN with
 * a banner, then `shell,v2,raw:` and `exec:` streams with plain flow control. [behavior] breaks
 * the handshake, [reply] scripts each command. Serves connections one after another.
 */
class FakeAdbd(
    authorized: () -> Collection<ByteArray>,
    private val behavior: Behavior = Behavior.NORMAL,
    private val features: String = "shell_v2,cmd,stat_v2",
    private val maxPayload: Int = AdbMessage.MAX_PAYLOAD,
    private val version: Int = AdbMessage.VERSION,
    private val reply: (String) -> Reply = { Reply.Exit(0, "") },
) : AutoCloseable {
    enum class Behavior {
        NORMAL,
        SILENT,
        HANG_UP,
        AUTH,
        PLAIN_CNXN,
        BAD_MAGIC,
        OKAY_FIRST,
        NOT_TLS_AFTER_STLS,
        SILENT_AFTER_TLS,
        HANG_UP_AFTER_TLS,
        OKAY_INSTEAD_OF_CNXN,
        ZERO_MAX_PAYLOAD,
        ANCIENT_VERSION,
    }

    /** How the device answers one command. */
    sealed interface Reply {
        /**
         * Runs: [stdout] (plus the command's stdin when [echo]), [stderr], exit [code]; output in
         * WRTEs of [chunk] bytes, after packets of other streams when [strayFirst].
         */
        data class Exit(
            val code: Int,
            val stdout: String,
            val stderr: String = "",
            val echo: Boolean = false,
            val chunk: Int = Int.MAX_VALUE,
            val strayFirst: Boolean = false,
        ) : Reply

        data object Refuse : Reply

        data object CloseWithoutExit : Reply

        data object DropConnection : Reply

        data object Hang : Reply

        data object Garbage : Reply

        data object BadChecksum : Reply

        data object OkayWithoutId : Reply

        data object WriteBeforeOkay : Reply

        data object BadExitLength : Reply

        data object DataAfterExit : Reply

        data class Flood(
            val bytes: Int,
        ) : Reply
    }

    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val endpoint: AdbEndpoint get() = AdbEndpoint("127.0.0.1", server.localPort)

    /** Services opened, in order (`shell,v2,raw:…` or `exec:…`). */
    val opened: MutableList<String> = CopyOnWriteArrayList()

    /** The stdin each command received (shell: up to CLOSE_STDIN; exec: what arrived before it ended). */
    val stdins: MutableList<ByteArray> = CopyOnWriteArrayList()

    /** WRTE payloads larger than the max payload we announced. */
    val oversizedWrites: MutableList<Int> = CopyOnWriteArrayList()

    /** Barriers: the first CNXN arrived, the first TLS handshake succeeded, the first OPEN arrived. */
    val gotCnxn = CompletableFuture<Unit>()
    val gotTls = CompletableFuture<Unit>()
    val gotOpen = CompletableFuture<String>()

    /** Completes when a client closed its connection. */
    val clientClosed = CompletableFuture<Unit>()

    private val failure = CompletableFuture<Throwable?>()
    private val tls =
        TestTls.serverContext(TestTls.device) { certificate ->
            val key = certificate.publicKey.encoded
            if (authorized().none { it.contentEquals(key) }) throw CertificateException("key not paired")
        }
    private var nextId = 100

    @Volatile private var current: Socket? = null

    private val worker =
        thread(isDaemon = true, name = "fake-adbd") {
            try {
                while (true) {
                    val plain = acceptOrNull() ?: break
                    current = plain
                    plain.use(::serveOne)
                }
                failure.complete(null)
            } catch (broken: Exception) {
                failure.complete(broken)
            }
        }

    private fun acceptOrNull(): Socket? =
        try {
            server.accept()
        } catch (closed: IOException) {
            if (!server.isClosed) throw closed
            null
        }

    /** One connection; the client hanging up at any point is normal (probes do it right after STLS). */
    private fun serveOne(plain: Socket) {
        try {
            serve(plain)
        } catch (expectedHangUp: IOException) {
            clientClosed.complete(Unit)
        }
    }

    private fun serve(plain: Socket) {
        if (!startTls(plain.getInputStream(), plain.getOutputStream())) return
        val socket = acceptTls(plain) ?: return
        gotTls.complete(Unit)
        serveTls(socket.inputStream.buffered(), socket.outputStream)
    }

    /** The plain part: CNXN, then STLS both ways; `false` when [behavior] ends the connection there. */
    private fun startTls(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val cnxn = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD_V1, verifyChecksum = true) ?: return false
        check(cnxn.command == AdbMessage.CNXN) { "expected CNXN, got $cnxn" }
        gotCnxn.complete(Unit)
        return when {
            behavior == Behavior.HANG_UP -> {
                false
            }

            behavior in BEFORE_TLS -> {
                answer(output, plainAnswer())
                awaitClose(input)
                false
            }

            else -> {
                answer(output, AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0).encode())
                val stls = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD_V1, verifyChecksum = true)
                check(stls == null || stls.command == AdbMessage.STLS) { "expected STLS, got $stls" }
                if (stls != null && behavior == Behavior.NOT_TLS_AFTER_STLS) {
                    answer(output, "not tls at all, sorry\r\n".repeat(4).toByteArray())
                    awaitClose(input)
                }
                stls != null && behavior != Behavior.NOT_TLS_AFTER_STLS
            }
        }
    }

    /** The TLS server side, or `null` when the key was refused (the handshake ended with an alert). */
    private fun acceptTls(plain: Socket): SSLSocket? =
        try {
            TestTls.serve(tls, plain)
        } catch (expectedRefusal: IOException) {
            null
        }

    /** What adbd sends in answer to the plain CNXN when [behavior] breaks that step. */
    private fun plainAnswer(): ByteArray =
        when {
            behavior == Behavior.AUTH -> AdbMessage(AdbMessage.AUTH, 1, 0, ByteArray(20)).encode()
            behavior == Behavior.PLAIN_CNXN -> deviceCnxn().encode()
            behavior == Behavior.BAD_MAGIC -> ByteArray(AdbMessage.HEADER_SIZE) { 1 }
            behavior == Behavior.OKAY_FIRST -> AdbMessage(AdbMessage.OKAY, 1, 1).encode()
            else -> ByteArray(0)
        }

    private fun serveTls(
        input: InputStream,
        output: OutputStream,
    ) {
        val banner =
            when {
                behavior == Behavior.SILENT_AFTER_TLS || behavior == Behavior.HANG_UP_AFTER_TLS -> null
                behavior == Behavior.OKAY_INSTEAD_OF_CNXN -> AdbMessage(AdbMessage.OKAY, 1, 1)
                behavior == Behavior.ZERO_MAX_PAYLOAD -> deviceCnxn(maxData = 0)
                behavior == Behavior.ANCIENT_VERSION -> deviceCnxn(protocol = 0x00000001)
                else -> deviceCnxn()
            }
        when {
            behavior == Behavior.SILENT_AFTER_TLS -> awaitClose(input)
            banner != null -> serveStreams(input, output, banner)
        }
    }

    /** Sends [banner], then serves streams until the client hangs up or a reply drops the connection. */
    private fun serveStreams(
        input: InputStream,
        output: OutputStream,
        banner: AdbMessage,
    ) {
        send(output, banner)
        var open = true
        while (open) {
            val message = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, verifyChecksum = false)
            if (message == null) clientClosed.complete(Unit)
            open = message != null && (message.command != AdbMessage.OPEN || stream(message, input, output))
        }
    }

    private fun deviceCnxn(
        protocol: Int = version,
        maxData: Int = maxPayload,
    ): AdbMessage {
        val banner = "device::ro.product.name=fake;ro.product.model=Fake;ro.product.device=fake;features=$features"
        return AdbMessage(AdbMessage.CNXN, protocol, maxData, banner.toByteArray())
    }

    /** Serves one stream; `false` when the connection is to be dropped. */
    private fun stream(
        open: AdbMessage,
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val service = open.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
        opened += service
        gotOpen.complete(service)
        val stream = Stream(open.arg0, nextId++, input, output)
        val shell = service.startsWith("shell,v2,raw:")
        val reply = reply(service.substringAfter(':'))
        val early =
            when {
                reply == Reply.Refuse -> {
                    AdbMessage(AdbMessage.CLSE, 0, stream.clientId)
                }

                reply == Reply.WriteBeforeOkay -> {
                    AdbMessage(
                        AdbMessage.WRTE,
                        stream.myId,
                        stream.clientId,
                        byteArrayOf(1),
                    )
                }

                reply == Reply.OkayWithoutId -> {
                    AdbMessage(AdbMessage.OKAY, 0, stream.clientId)
                }

                else -> {
                    null
                }
            }
        if (early != null) send(output, early)
        return early != null || stream.run(reply, shell)
    }

    private inner class Stream(
        val clientId: Int,
        val myId: Int,
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        private val stdin = ByteArrayOutputStream()

        fun run(
            reply: Reply,
            shell: Boolean,
        ): Boolean {
            send(output, AdbMessage(AdbMessage.OKAY, myId, clientId))
            if (shell) collectStdin() else stdins += ByteArray(0)
            return when {
                reply is Reply.Exit -> {
                    exit(reply, shell)
                }

                reply == Reply.DropConnection -> {
                    false
                }

                reply == Reply.Hang -> {
                    awaitClose(input)
                    false
                }

                reply == Reply.Garbage -> {
                    output.write(ByteArray(AdbMessage.HEADER_SIZE) { 7 })
                    true
                }

                reply == Reply.BadChecksum -> {
                    badChecksum()
                }

                else -> {
                    write(brokenOutput(reply), chunk = 4096).also { if (reply == Reply.CloseWithoutExit) close() }
                }
            }
        }

        /** The stream of a reply that breaks the shell protocol or its limits. */
        private fun brokenOutput(reply: Reply): ByteArray =
            when {
                reply == Reply.CloseWithoutExit -> {
                    ShellProtocol.packet(ShellProtocol.STDOUT, "partial".toByteArray())
                }

                reply == Reply.BadExitLength -> {
                    ShellProtocol.packet(ShellProtocol.EXIT, byteArrayOf(0, 0))
                }

                reply == Reply.DataAfterExit -> {
                    exitPacket(0) + ShellProtocol.packet(ShellProtocol.STDOUT, byteArrayOf(1))
                }

                reply is Reply.Flood -> {
                    ShellProtocol.packet(
                        ShellProtocol.STDOUT,
                        ByteArray(reply.bytes) { 'x'.code.toByte() },
                    )
                }

                else -> {
                    error("handled elsewhere: $reply")
                }
            }

        private fun exit(
            reply: Reply.Exit,
            shell: Boolean,
        ): Boolean {
            if (reply.strayFirst) {
                send(output, AdbMessage(AdbMessage.WRTE, 77, clientId + 1000, byteArrayOf(9)))
                send(output, AdbMessage(AdbMessage.CNXN, 0, clientId))
            }
            val out = reply.stdout.toByteArray() + (if (reply.echo) stdins.last() else ByteArray(0))
            val stream =
                if (shell) {
                    ShellProtocol.packet(ShellProtocol.STDOUT, out) +
                        ShellProtocol.packet(ShellProtocol.STDERR, reply.stderr.toByteArray()) +
                        exitPacket(reply.code)
                } else {
                    out + reply.stderr.toByteArray()
                }
            if (!write(stream, reply.chunk)) return false
            close()
            return true
        }

        private fun badChecksum(): Boolean {
            val good = AdbMessage(AdbMessage.WRTE, myId, clientId, exitPacket(0)).encode()
            good[16] = (good[16] + 1).toByte()
            output.write(good)
            return true
        }

        private fun exitPacket(code: Int) = ShellProtocol.packet(ShellProtocol.EXIT, byteArrayOf(code.toByte()))

        private fun close() = send(output, AdbMessage(AdbMessage.CLSE, myId, clientId))

        /** Reads the shell stdin stream up to CLOSE_STDIN, acknowledging each WRTE. */
        private fun collectStdin() {
            val raw = ByteArrayOutputStream()
            while (!parseStdin(raw.toByteArray())) {
                val message =
                    AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, verifyChecksum = false)
                        ?: throw IOException("the client went away before closing stdin")
                if (message.command == AdbMessage.WRTE && message.arg1 == myId) {
                    if (message.payload.size > maxPayload) oversizedWrites += message.payload.size
                    raw.write(message.payload)
                    send(output, AdbMessage(AdbMessage.OKAY, myId, clientId))
                }
            }
            stdins += stdin.toByteArray()
        }

        /** Whether [raw] holds complete packets up to CLOSE_STDIN; collects the stdin data. */
        private fun parseStdin(raw: ByteArray): Boolean {
            stdin.reset()
            var at = 0
            var closed = false
            while (!closed && at + ShellProtocol.HEADER_SIZE <= raw.size) {
                val id = raw[at].toInt()
                val length = ByteBuffer.wrap(raw, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                val end = at + ShellProtocol.HEADER_SIZE + length
                if (end > raw.size) break
                closed = id == ShellProtocol.CLOSE_STDIN
                if (!closed) stdin.write(raw, at + ShellProtocol.HEADER_SIZE, length)
                at = end
            }
            return closed
        }

        /** Sends [data] in WRTEs of [chunk] bytes, each acknowledged; `false` when the client went away. */
        private fun write(
            data: ByteArray,
            chunk: Int = Int.MAX_VALUE,
        ): Boolean {
            var at = 0
            while (at < data.size) {
                val n = minOf(chunk, data.size - at)
                send(output, AdbMessage(AdbMessage.WRTE, myId, clientId, data.copyOfRange(at, at + n)))
                at += n
                if (!awaitOkay()) return false
            }
            return true
        }

        /** Waits for the OKAY of our last WRTE, taking stdin meanwhile; `false` when the stream or connection ended. */
        private fun awaitOkay(): Boolean {
            var outcome: Boolean? = null
            while (outcome == null) {
                val message = AdbMessage.read(input, AdbMessage.MAX_PAYLOAD, verifyChecksum = false)
                outcome =
                    when {
                        message == null -> {
                            false
                        }

                        message.arg1 != myId -> {
                            null
                        }

                        message.command == AdbMessage.OKAY -> {
                            true
                        }

                        message.command == AdbMessage.CLSE -> {
                            false
                        }

                        else -> {
                            if (message.command == AdbMessage.WRTE) takeStdin(message.payload)
                            null
                        }
                    }
            }
            return outcome
        }

        private fun takeStdin(data: ByteArray) {
            stdins[stdins.size - 1] = stdins.last() + data
            send(output, AdbMessage(AdbMessage.OKAY, myId, clientId))
        }
    }

    private fun send(
        output: OutputStream,
        message: AdbMessage,
    ) = answer(output, message.encode())

    private fun answer(
        output: OutputStream,
        bytes: ByteArray,
    ) {
        output.write(bytes)
        output.flush()
    }

    private fun awaitClose(input: InputStream) {
        try {
            while (input.read() >= 0) continue
        } catch (expectedReset: IOException) {
            // A reset is a close as well.
        }
        clientClosed.complete(Unit)
    }

    /** Waits for the server thread after [close]; returns its own failure (a broken fake or test), if any. */
    fun join(): Throwable? = failure.get(10, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        current?.close()
        worker.join(TimeUnit.SECONDS.toMillis(10))
    }

    private companion object {
        val BEFORE_TLS =
            setOf(Behavior.SILENT, Behavior.AUTH, Behavior.PLAIN_CNXN, Behavior.BAD_MAGIC, Behavior.OKAY_FIRST)
    }
}
