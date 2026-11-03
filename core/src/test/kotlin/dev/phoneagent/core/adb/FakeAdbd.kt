/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/test/kotlin/com/wangzan101/mika/core/adb/AdbConnectionTest.kt
 * (FakeAdbd).
 * Modified by PhoneAgent contributors: blocking per-connection serving instead of an event loop;
 * AOSP's own framing and checksum rule (Aosp.kt) instead of the client's; the key check inside the
 * TLS handshake; broken handshake and stream behaviors; the shell protocol with stdin and exit
 * status; barriers.
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
import kotlin.concurrent.thread

/**
 * adbd's wireless-debugging port in-process (AOSP `adb.cpp`, `transport.cpp`,
 * `daemon/services.cpp`): CNXN → STLS, TLS 1.3 that accepts only [authorized] client keys (as
 * `adbd_tls_verify_cert`), CNXN with a banner, then `shell,v2,raw:` and `exec:` streams with plain
 * flow control. Packets are framed by [Aosp], with `data_check` as `send_packet` fills it at the
 * negotiated version (min of the client's CNXN version and adbd's own: A_VERSION, A_VERSION_MIN
 * with [Behavior.OLD_VERSION]); received checksums are never checked, as adbd does not. [behavior]
 * breaks the handshake, [reply] scripts each command, [cnxnBytes] replaces the CNXN after TLS byte
 * for byte. Serves connections one after another.
 */
class FakeAdbd(
    authorized: () -> Collection<ByteArray>,
    private val behavior: Behavior = Behavior.NORMAL,
    private val features: String = "shell_v2,cmd,stat_v2",
    private val maxPayload: Int = Aosp.MAX_PAYLOAD,
    private val cnxnBytes: ByteArray? = null,
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
        CORRUPT_RECORD_AFTER_TLS,
        OKAY_INSTEAD_OF_CNXN,
        ZERO_MAX_PAYLOAD,
        ANCIENT_VERSION,

        /** A working adbd from before A_VERSION_SKIP_CHECKSUM: every packet carries its checksum. */
        OLD_VERSION,
    }

    /** How the device answers one command. */
    sealed interface Reply {
        /**
         * Runs: [stdout] (plus the command's stdin when [echo]), [stderr], exit [code]; output in
         * WRTEs of [chunk] bytes, after late packets of the previous stream when [strayFirst].
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

        /** A packet that names the stream wrongly or does not belong in it, sent before a normal exit. */
        sealed interface Misaddressed : Reply

        /** The stream closes with a CLSE whose remote id is not the stream's. */
        data object CloseWithWrongId : Misaddressed

        /** The first stdin WRTE is acknowledged with an OKAY whose remote id is not the stream's. */
        data object AckWithWrongId : Reply

        /** Output comes in a WRTE whose remote id is not the stream's. */
        data object WriteWithWrongId : Misaddressed

        /** A CNXN arrives in the middle of the stream. */
        data object CnxnInStream : Misaddressed

        /** A packet with a command adb does not have, addressed to the stream. */
        data object UnknownCommand : Misaddressed

        /** An OKAY for the stream while no write of the client waits for one. */
        data object UnsolicitedOkay : Misaddressed

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

    /** How many stdin WRTEs each command received. */
    val stdinWrites: MutableList<Int> = CopyOnWriteArrayList()

    /** WRTE payloads larger than the max payload we announced. */
    val oversizedWrites: MutableList<Int> = CopyOnWriteArrayList()

    /** OPEN payloads larger than the max payload we announced; adbd drops the connection on one. */
    val oversizedOpens: MutableList<Int> = CopyOnWriteArrayList()

    /** The `features` of the client's banner, as adbd parses it. */
    val hostFeatures = CompletableFuture<String?>()

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
    private val adbdVersion =
        when {
            behavior == Behavior.ANCIENT_VERSION -> 1
            behavior == Behavior.OLD_VERSION -> Aosp.A_VERSION_MIN
            else -> Aosp.A_VERSION
        }
    private var nextId = 100
    private var previous: Stream? = null

    /** The version `send_packet` uses on the current connection; adbd starts at A_VERSION_MIN. */
    @Volatile private var protocol = Aosp.A_VERSION_MIN

    @Volatile private var current: Socket? = null

    private val worker =
        thread(isDaemon = true, name = "fake-adbd") {
            try {
                while (true) {
                    val plain = acceptOrNull() ?: break
                    current = plain
                    protocol = Aosp.A_VERSION_MIN
                    previous = null
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
        if (behavior == Behavior.CORRUPT_RECORD_AFTER_TLS) {
            // A TLS 1.3 application_data record of 5 bytes, shorter than its AEAD tag: bad_record_mac.
            answer(plain.getOutputStream(), byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5))
            awaitClose(plain.getInputStream())
        } else {
            serveTls(socket.inputStream.buffered(), socket.outputStream)
        }
    }

    /** The plain part: CNXN, then STLS both ways; `false` when [behavior] ends the connection there. */
    private fun startTls(
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val cnxn = Aosp.read(input, Aosp.MAX_PAYLOAD_V1) ?: return false
        check(cnxn.command == Aosp.A_CNXN) { "expected CNXN, got $cnxn" }
        protocol = minOf(cnxn.arg0, adbdVersion)
        hostFeatures.complete(Aosp.bannerFeatures(cnxn.payload))
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
                send(output, Aosp.A_STLS, Aosp.A_STLS_VERSION, 0)
                val stls = Aosp.read(input, Aosp.MAX_PAYLOAD_V1)
                check(stls == null || stls.command == Aosp.A_STLS) { "expected STLS, got $stls" }
                if (stls != null && behavior == Behavior.NOT_TLS_AFTER_STLS) {
                    answer(output, "not tls at all, sorry\r\n".repeat(4).toByteArray())
                    awaitClose(input)
                }
                stls != null && behavior != Behavior.NOT_TLS_AFTER_STLS
            }
        }
    }

    /** The TLS server side, or `null` when the key was refused (the handshake ended with an alert). */
    private fun acceptTls(plain: Socket) =
        try {
            TestTls.serve(tls, plain)
        } catch (expectedRefusal: IOException) {
            null
        }

    /** What adbd sends in answer to the plain CNXN when [behavior] breaks that step. */
    private fun plainAnswer(): ByteArray =
        when {
            behavior == Behavior.AUTH -> packet(Aosp.A_AUTH, 1, 0, ByteArray(20))
            behavior == Behavior.PLAIN_CNXN -> deviceCnxn()
            behavior == Behavior.BAD_MAGIC -> ByteArray(24) { 1 }
            behavior == Behavior.OKAY_FIRST -> packet(Aosp.A_OKAY, 1, 1)
            else -> ByteArray(0)
        }

    private fun serveTls(
        input: InputStream,
        output: OutputStream,
    ) {
        val banner =
            when {
                behavior == Behavior.SILENT_AFTER_TLS || behavior == Behavior.HANG_UP_AFTER_TLS -> null
                behavior == Behavior.OKAY_INSTEAD_OF_CNXN -> packet(Aosp.A_OKAY, 1, 1)
                behavior == Behavior.ZERO_MAX_PAYLOAD -> deviceCnxn(maxData = 0)
                else -> cnxnBytes ?: deviceCnxn()
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
        banner: ByteArray,
    ) {
        answer(output, banner)
        var open = true
        while (open) {
            val message = Aosp.read(input, Aosp.MAX_PAYLOAD)
            if (message == null) clientClosed.complete(Unit)
            open = message != null && (message.command != Aosp.A_OPEN || stream(message, input, output))
        }
    }

    private fun deviceCnxn(maxData: Int = maxPayload): ByteArray {
        val banner = "device::ro.product.name=fake;ro.product.model=Fake;ro.product.device=fake;features=$features"
        return packet(Aosp.A_CNXN, adbdVersion, maxData, banner.toByteArray())
    }

    /** Serves one stream; `false` when the connection is to be dropped. */
    private fun stream(
        open: Aosp.Packet,
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        if (open.payload.size > maxPayload) {
            oversizedOpens += open.payload.size
            return false
        }
        val service = open.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
        opened += service
        gotOpen.complete(service)
        val stream = Stream(open.arg0, nextId++, input, output)
        val shell = service.startsWith("shell,v2,raw:")
        val reply = reply(service.substringAfter(':'))
        val early =
            when {
                reply == Reply.Refuse -> packet(Aosp.A_CLSE, 0, stream.clientId)
                reply == Reply.WriteBeforeOkay -> packet(Aosp.A_WRTE, stream.myId, stream.clientId, byteArrayOf(1))
                reply == Reply.OkayWithoutId -> packet(Aosp.A_OKAY, 0, stream.clientId)
                else -> null
            }
        if (early != null) answer(output, early)
        val keep = early != null || stream.run(reply, shell, previous)
        previous = stream
        return keep
    }

    private inner class Stream(
        val clientId: Int,
        val myId: Int,
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        private val stdin = ByteArrayOutputStream()
        private var writes = 0

        fun run(
            reply: Reply,
            shell: Boolean,
            previous: Stream?,
        ): Boolean {
            send(Aosp.A_OKAY)
            if (shell) collectStdin(wrongAck = reply == Reply.AckWithWrongId) else startExecStdin(reply)
            return when {
                reply is Reply.Exit -> {
                    if (reply.strayFirst) previous?.strays()
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
                    output.write(ByteArray(24) { 7 })
                    true
                }

                reply == Reply.BadChecksum -> {
                    val exit = exitPacket(0)
                    answer(output, packet(Aosp.A_WRTE, myId, clientId, exit, dataCheck = Aosp.sum(exit) + 1))
                    true
                }

                reply == Reply.AckWithWrongId -> {
                    exit(Reply.Exit(0, ""), shell)
                }

                reply is Reply.Misaddressed -> {
                    misaddressed(reply, shell)
                }

                else -> {
                    write(brokenOutput(reply), chunk = 4096).also {
                        if (reply ==
                            Reply.CloseWithoutExit
                        ) {
                            send(Aosp.A_CLSE)
                        }
                    }
                }
            }
        }

        /** Late packets of this (finished) stream: output, an acknowledgement and its close. */
        fun strays() {
            answer(output, packet(Aosp.A_WRTE, myId, clientId, byteArrayOf(9)))
            send(Aosp.A_OKAY)
            send(Aosp.A_CLSE)
        }

        /** The packet of [reply], then a normal exit. */
        private fun misaddressed(
            reply: Reply.Misaddressed,
            shell: Boolean,
        ): Boolean {
            val other = myId + 50
            when (reply) {
                Reply.CloseWithWrongId -> answer(output, packet(Aosp.A_CLSE, other, clientId))
                Reply.WriteWithWrongId -> answer(output, packet(Aosp.A_WRTE, other, clientId, exitPacket(0)))
                Reply.CnxnInStream -> answer(output, deviceCnxn())
                Reply.UnknownCommand -> answer(output, packet(UNKNOWN_COMMAND, myId, clientId))
                Reply.UnsolicitedOkay -> send(Aosp.A_OKAY)
            }
            return exit(Reply.Exit(0, ""), shell)
        }

        /** The stream of a reply that breaks the shell protocol or its limits. */
        private fun brokenOutput(reply: Reply): ByteArray =
            when {
                reply == Reply.CloseWithoutExit -> Aosp.shellPacket(Aosp.ID_STDOUT, "partial".toByteArray())
                reply == Reply.BadExitLength -> Aosp.shellPacket(Aosp.ID_EXIT, byteArrayOf(0, 0))
                reply == Reply.DataAfterExit -> exitPacket(0) + Aosp.shellPacket(Aosp.ID_STDOUT, byteArrayOf(1))
                reply is Reply.Flood -> Aosp.shellPacket(Aosp.ID_STDOUT, ByteArray(reply.bytes) { 'x'.code.toByte() })
                else -> error("handled elsewhere: $reply")
            }

        private fun exit(
            reply: Reply.Exit,
            shell: Boolean,
        ): Boolean {
            val out = reply.stdout.toByteArray() + (if (reply.echo) stdins.last() else ByteArray(0))
            val stream =
                if (shell) {
                    Aosp.shellPacket(Aosp.ID_STDOUT, out) +
                        Aosp.shellPacket(Aosp.ID_STDERR, reply.stderr.toByteArray()) +
                        exitPacket(reply.code)
                } else {
                    out + reply.stderr.toByteArray()
                }
            if (!write(stream, reply.chunk)) return false
            send(Aosp.A_CLSE)
            return true
        }

        private fun exitPacket(code: Int) = Aosp.shellPacket(Aosp.ID_EXIT, byteArrayOf(code.toByte()))

        /** A packet of this stream from us to the client. */
        private fun send(command: Int) = answer(output, packet(command, myId, clientId))

        /**
         * Reads the shell stdin stream up to CLOSE_STDIN, acknowledging each WRTE (the first one
         * with a wrong id when [wrongAck]).
         */
        private fun collectStdin(wrongAck: Boolean) {
            val raw = ByteArrayOutputStream()
            while (!parseStdin(raw.toByteArray())) {
                val message =
                    Aosp.read(input, Aosp.MAX_PAYLOAD) ?: throw IOException("the client went away before closing stdin")
                if (message.command == Aosp.A_WRTE && message.arg1 == myId) {
                    if (message.payload.size > maxPayload) oversizedWrites += message.payload.size
                    raw.write(message.payload)
                    val ackId = if (wrongAck && writes == 0) myId + 50 else myId
                    writes++
                    answer(output, packet(Aosp.A_OKAY, ackId, clientId))
                }
            }
            stdins += stdin.toByteArray()
            stdinWrites += writes
        }

        /**
         * exec: stdin arrives along the output; with [Reply.AckWithWrongId] the first piece is
         * acknowledged with a wrong id.
         */
        private fun startExecStdin(reply: Reply) {
            stdins += ByteArray(0)
            stdinWrites += 0
            if (reply != Reply.AckWithWrongId) return
            val first = Aosp.read(input, Aosp.MAX_PAYLOAD) ?: throw IOException("the client went away")
            check(first.command == Aosp.A_WRTE && first.arg1 == myId) { "expected stdin, got $first" }
            countStdin(first.payload)
            answer(output, packet(Aosp.A_OKAY, myId + 50, clientId))
        }

        private fun countStdin(data: ByteArray) {
            stdins[stdins.size - 1] = stdins.last() + data
            stdinWrites[stdinWrites.size - 1] = stdinWrites.last() + 1
        }

        /** Whether [raw] holds complete packets up to CLOSE_STDIN; collects the stdin data. */
        private fun parseStdin(raw: ByteArray): Boolean {
            stdin.reset()
            var at = 0
            var closed = false
            while (!closed && at + Aosp.SHELL_HEADER_SIZE <= raw.size) {
                val id = raw[at].toInt()
                val length = ByteBuffer.wrap(raw, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                val end = at + Aosp.SHELL_HEADER_SIZE + length
                if (end > raw.size) break
                closed = id == Aosp.ID_CLOSE_STDIN
                if (!closed) stdin.write(raw, at + Aosp.SHELL_HEADER_SIZE, length)
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
                answer(output, packet(Aosp.A_WRTE, myId, clientId, data.copyOfRange(at, at + n)))
                at += n
                if (!awaitOkay()) return false
            }
            return true
        }

        /** Waits for the OKAY of our last WRTE, taking stdin meanwhile; `false` when the stream or connection ended. */
        private fun awaitOkay(): Boolean {
            var outcome: Boolean? = null
            while (outcome == null) {
                val message = Aosp.read(input, Aosp.MAX_PAYLOAD)
                outcome =
                    when {
                        message == null -> {
                            false
                        }

                        message.arg1 != myId -> {
                            null
                        }

                        message.command == Aosp.A_OKAY -> {
                            true
                        }

                        message.command == Aosp.A_CLSE -> {
                            false
                        }

                        else -> {
                            if (message.command == Aosp.A_WRTE) {
                                countStdin(message.payload)
                                send(Aosp.A_OKAY)
                            }
                            null
                        }
                    }
            }
            return outcome
        }
    }

    /** A packet as adbd's `send_packet` writes it on this connection. */
    private fun packet(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = ByteArray(0),
        dataCheck: Int? = null,
    ): ByteArray =
        dataCheck?.let { Aosp.frame(command, arg0, arg1, payload, it) }
            ?: Aosp.encode(command, arg0, arg1, payload, protocol)

    private fun send(
        output: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
    ) = answer(output, packet(command, arg0, arg1))

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
        /** "JUNK": no adb command has this value. */
        const val UNKNOWN_COMMAND = 0x4b4e554a

        val BEFORE_TLS =
            setOf(Behavior.SILENT, Behavior.AUTH, Behavior.PLAIN_CNXN, Behavior.BAD_MAGIC, Behavior.OKAY_FIRST)
    }
}
