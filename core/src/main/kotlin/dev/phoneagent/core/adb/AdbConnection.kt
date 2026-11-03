/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbConnection.kt.
 * Modified by PhoneAgent contributors: English documentation; failures are a typed result; TLS is
 * required (a plain CNXN or legacy AUTH reply is a protocol failure); no reader thread and no
 * open streams in the API: a session runs one fixed command at a time to completion (see
 * AdbCommand.kt), with the shell protocol for the exit status, and checks every packet of the
 * stream against its state and ids; handshake checksums follow the sender's version (Mika does
 * not check them).
 */
package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLException

/** Why connecting to adbd failed. */
public enum class ConnectFailure {
    /** Nothing accepted a TCP connection at the endpoint (wireless debugging is off, or the port is stale). */
    UNREACHABLE,

    /** The TLS handshake failed. */
    TLS,

    /** adbd refused our key, in the TLS handshake or right after it: not paired, or revoked; pair again. */
    UNAUTHORIZED,

    /** The peer is not a wireless-debugging adbd, or broke the protocol. */
    PROTOCOL,

    /** No answer within [AdbConfig.connectTimeoutMs]. */
    TIMEOUT,
}

/** Outcome of [AdbClient.connect]. */
public sealed interface ConnectResult {
    /** Connected and authorized; the caller owns [session] and closes it. */
    public class Connected(
        /** The open session. */
        public val session: AdbSession,
    ) : ConnectResult

    /** Not connected; nothing is left open. */
    public data class Failed(
        /** The class of failure. */
        val reason: ConnectFailure,
        /** Diagnostic text for logs. */
        val detail: String,
    ) : ConnectResult
}

/** A classified connection failure travelling out of the blocking handshake. */
internal class ConnectError(
    val reason: ConnectFailure,
    detail: String,
    cause: Throwable? = null,
) : IOException(detail, cause)

/** What adbd announced in its CNXN. */
internal class Handshake(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream,
    val banner: String,
    val version: Int,
    val maxPayload: Int,
)

/**
 * The client handshake (AOSP `adb.cpp`, wireless debugging): send CNXN, adbd answers STLS, send
 * STLS and run TLS 1.3 with our paired certificate, then adbd sends its CNXN (the banner) over
 * TLS. adbd checks our key when it reads our certificate; in TLS 1.3 our side of the handshake may
 * already be done by then, so a refusal shows up either as a failure of our handshake after adbd
 * asked for the certificate, or as a TLS alert or a closed connection when we read that CNXN (as
 * the adb host tool's post-handshake peek). We announce no features beyond the shell protocol,
 * so adbd uses plain flow control (every WRTE waits for an OKAY).
 */
internal class AdbHandshake(
    private val tls: AdbTls,
) {
    @Throws(ConnectError::class)
    fun connect(
        plain: Socket,
        endpoint: AdbEndpoint,
        connectTimeoutMs: Int,
    ): Handshake {
        val address = InetSocketAddress(endpoint.host, endpoint.port)
        step(ConnectFailure.UNREACHABLE) { plain.connect(address, connectTimeoutMs) }
        startTls(plain)
        val socket = step(ConnectFailure.TLS) { tls.handshake(plain, endpoint) }
        val input = step(ConnectFailure.TLS) { BufferedInputStream(socket.inputStream) }
        val output = step(ConnectFailure.TLS) { socket.outputStream }
        val cnxn =
            step(ConnectFailure.UNAUTHORIZED) { readHandshakePacket(input) }
                ?: fail(
                    ConnectFailure.UNAUTHORIZED,
                    "adbd closed the connection after TLS: our key is not paired",
                )
        return accept(PendingHandshake(socket, input, output, cnxn))
    }

    /**
     * CNXN in the clear, which adbd must answer with STLS, then our STLS. Read unbuffered: TLS
     * starts on this same stream right after.
     */
    private fun startTls(plain: Socket) {
        val input = step(ConnectFailure.PROTOCOL) { plain.getInputStream() }
        val output = step(ConnectFailure.PROTOCOL) { plain.getOutputStream() }
        step(ConnectFailure.PROTOCOL) {
            send(output, AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER))
        }
        val first =
            step(ConnectFailure.PROTOCOL) { readHandshakePacket(input) }
                ?: fail(ConnectFailure.PROTOCOL, "the peer closed the connection without answering CNXN")
        when (first.command) {
            AdbMessage.STLS -> {
                step(ConnectFailure.PROTOCOL) {
                    send(output, AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0))
                }
            }

            AdbMessage.AUTH -> {
                fail(
                    ConnectFailure.PROTOCOL,
                    "the peer asks for legacy RSA auth: not a wireless-debugging port",
                )
            }

            AdbMessage.CNXN -> {
                fail(
                    ConnectFailure.PROTOCOL,
                    "the peer answered without TLS: not a wireless-debugging port",
                )
            }

            else -> {
                fail(ConnectFailure.PROTOCOL, "unexpected $first in answer to CNXN")
            }
        }
    }

    /**
     * CNXN and AUTH are limited to adb's v1 payload size, as the peer's limit is not known yet;
     * so is its version, which a CNXN brings along.
     */
    private fun readHandshakePacket(input: InputStream): AdbMessage? =
        AdbMessage.read(input, AdbMessage.MAX_PAYLOAD_V1, senderVersion = null)

    private fun accept(handshake: PendingHandshake): Handshake {
        val cnxn = handshake.cnxn
        when {
            cnxn.command != AdbMessage.CNXN -> {
                fail(ConnectFailure.PROTOCOL, "expected CNXN over TLS, got $cnxn")
            }

            cnxn.arg0 < AdbMessage.VERSION_MIN -> {
                fail(
                    ConnectFailure.PROTOCOL,
                    "adb version ${cnxn.arg0.toUInt()} is too old",
                )
            }

            cnxn.arg1 <= 0 -> {
                fail(ConnectFailure.PROTOCOL, "adbd announced a max payload of ${cnxn.arg1}")
            }
        }
        return Handshake(
            socket = handshake.socket,
            input = handshake.input,
            output = handshake.output,
            banner = cnxn.payload.toString(Charsets.UTF_8).trimEnd('\u0000'),
            version = minOf(cnxn.arg0, AdbMessage.VERSION),
            maxPayload = minOf(cnxn.arg1, AdbMessage.MAX_PAYLOAD),
        )
    }

    private inline fun <T> step(
        reason: ConnectFailure,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: IOException) {
            throw classify(failure, reason)
        }

    private fun fail(
        reason: ConnectFailure,
        detail: String,
    ): Nothing = throw ConnectError(reason, detail)

    companion object {
        /**
         * We are a host; the shell protocol is the only feature we use. No NUL at the end: adbd
         * takes the whole payload as the banner (AOSP host's `get_connection_string` sends none).
         */
        private val HOST_BANNER = "host::features=${AdbFeatures.SHELL_V2}".toByteArray(Charsets.US_ASCII)

        /** The connection failure that [failure] in a step of [reason] stands for. */
        fun classify(
            failure: IOException,
            reason: ConnectFailure,
        ): ConnectError {
            if (failure is ConnectError) return failure
            // Once adbd has our certificate, an alert, a reset or a hang-up is adbd refusing the key.
            val refusedInHandshake = failure is TlsHandshakeException && failure.certificateRequested
            val refusedAfterwards =
                reason == ConnectFailure.UNAUTHORIZED && (failure is SSLException || failure is TruncatedFrameException)
            val classified =
                when {
                    refusedInHandshake || refusedAfterwards -> ConnectFailure.UNAUTHORIZED
                    failure is TruncatedFrameException -> reason
                    failure is AdbProtocolException -> ConnectFailure.PROTOCOL
                    failure is SSLException -> ConnectFailure.TLS
                    else -> reason
                }
            return ConnectError(classified, describe(failure), failure)
        }

        @Throws(IOException::class)
        fun send(
            output: OutputStream,
            message: AdbMessage,
        ) {
            output.write(message.encode())
            output.flush()
        }
    }
}

/** The TLS stream and adbd's CNXN, before the CNXN is checked. */
private class PendingHandshake(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream,
    val cnxn: AdbMessage,
)

/** Feature names from adbd's banner that we act on. */
internal object AdbFeatures {
    const val SHELL_V2: String = "shell_v2"

    /** The `features=` list of a banner such as `device::ro.product.name=x;features=shell_v2,cmd`. */
    fun of(banner: String): Set<String> =
        banner
            .substringAfter("::", "")
            .split(';')
            .firstOrNull { it.startsWith("features=") }
            ?.removePrefix("features=")
            ?.split(',')
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
}

/**
 * An authorized connection to adbd that runs fixed commands one at a time ([run]); there is no
 * way to open other services or keep a shell open. A failure other than [CommandFailure.REFUSED]
 * and [CommandFailure.TOO_LONG], a timeout or a cancellation closes the session, since the stream
 * is then in an unknown state. Safe to use from several coroutines (runs are serialized); [close]
 * may be called at any time.
 */
public class AdbSession internal constructor(
    private val handshake: Handshake,
    private val config: AdbConfig,
    private val io: CoroutineDispatcher,
) : AutoCloseable {
    /** adbd's banner, e.g. `device::ro.product.name=…;ro.product.model=…;features=shell_v2,…`. */
    public val banner: String get() = handshake.banner

    /** The features adbd announced. */
    public val features: Set<String> = AdbFeatures.of(handshake.banner)

    private val mutex = Mutex()
    private var nextLocalId = 1

    @Volatile private var closed = false

    /** `false` once closed, by [close] or by a failure. */
    public val isOpen: Boolean get() = !closed

    /**
     * Runs [command] with [stdin] as its standard input and waits for it to finish, at most
     * [AdbConfig.commandTimeoutMs]. Uses `shell,v2,raw:` when adbd supports the shell protocol
     * (separate stdout and stderr, an exit status, stdin closed after [stdin]); otherwise `exec:`
     * (stdout and stderr together, no exit status, stdin never closed). Cancellation closes the
     * session and propagates.
     */
    public suspend fun run(
        command: AdbCommand,
        stdin: ByteArray = ByteArray(0),
    ): CommandResult =
        mutex.withLock {
            if (closed) return@withLock CommandResult.Failed(CommandFailure.CLOSED, "the session is closed")
            val localId = nextLocalId++
            val shell = AdbFeatures.SHELL_V2 in features
            val exchange = CommandExchange(handshake, localId, shell, config)
            val result =
                try {
                    withTimeoutOrNull(config.commandTimeoutMs) {
                        blockingIo(io, handshake.socket) { exchange.run(command, stdin) }
                    }
                        ?: CommandResult.Failed(
                            CommandFailure.TIMEOUT,
                            "no exit status within ${config.commandTimeoutMs} ms",
                        )
                } catch (failure: IOException) {
                    CommandExchange.classify(failure)
                } finally {
                    if (!exchange.reusable) close()
                }
            result
        }

    /** Closes the connection; a running command fails or is cancelled. Idempotent. */
    override fun close() {
        closed = true
        handshake.socket.closeQuietly()
    }

    override fun toString(): String = "AdbSession(open=$isOpen, features=$features)"
}

/**
 * One command on one stream, run on a blocking thread. The stream goes from opening (our OPEN
 * sent) to open (adbd's OKAY gave its id) to done; every packet must fit that state and carry both
 * ids, and an OKAY must answer a WRTE of ours. Late packets of the session's earlier streams are
 * skipped; anything else (another command, an unknown one, a wrong id) is a protocol failure.
 */
internal class CommandExchange(
    private val handshake: Handshake,
    private val localId: Int,
    private val shell: Boolean,
    config: AdbConfig,
) {
    private val output =
        if (shell) OutputCollector.Shell(config.maxOutputBytes) else OutputCollector.Raw(config.maxOutputBytes)

    /** Whether the session can run another command after this one: only after a clean end, a refusal or no start. */
    @Volatile var reusable: Boolean = false
        private set

    private var input = ByteArray(0)
    private var sent = 0
    private var remoteId = 0
    private var awaitingOkay = false

    @Throws(IOException::class)
    fun run(
        command: AdbCommand,
        stdin: ByteArray,
    ): CommandResult {
        val service = (if (shell) "shell,v2,raw:" else "exec:") + command.text
        val open = (service + "\u0000").toByteArray(Charsets.UTF_8)
        if (open.size > handshake.maxPayload) {
            reusable = true
            return CommandResult.Failed(
                CommandFailure.TOO_LONG,
                "the service name has ${open.size} bytes; adbd takes ${handshake.maxPayload} per packet",
            )
        }
        input = if (shell) ShellProtocol.stdinStream(stdin) else stdin
        send(AdbMessage(AdbMessage.OPEN, localId, 0, open))
        var result: CommandResult? = null
        while (result == null) {
            result = if (remoteId != 0 && !awaitingOkay && sent < input.size) writeNext() else handle(readNext())
        }
        return result
    }

    /** Sends the next piece of stdin; adbd must OKAY it before the one after. */
    private fun writeNext(): CommandResult? {
        val n = minOf(handshake.maxPayload, input.size - sent)
        send(AdbMessage(AdbMessage.WRTE, localId, remoteId, input.copyOfRange(sent, sent + n)))
        sent += n
        awaitingOkay = true
        return null
    }

    private fun readNext(): AdbMessage =
        AdbMessage.read(handshake.input, AdbMessage.MAX_PAYLOAD, handshake.version)
            ?: throw TruncatedFrameException("adbd closed the connection")

    /** The result once [message] ends the command, else `null`. */
    private fun handle(message: AdbMessage): CommandResult? {
        val stream = message.command in STREAM_COMMANDS
        return when {
            stream && message.arg1 in 1 until localId -> null
            !stream || message.arg1 != localId -> protocolError("unexpected $message during the command")
            remoteId == 0 -> opening(message)
            message.arg0 != remoteId -> protocolError("$message names stream ${message.arg0}, not $remoteId")
            else -> open(message)
        }
    }

    /** Before adbd's OKAY: it opens the stream (OKAY with its id) or refuses it (CLSE without one). */
    private fun opening(message: AdbMessage): CommandResult? =
        when {
            message.command == AdbMessage.OKAY && message.arg0 != 0 -> {
                remoteId = message.arg0
                null
            }

            message.command == AdbMessage.CLSE && message.arg0 == 0 -> {
                reusable = true
                CommandResult.Failed(CommandFailure.REFUSED, "adbd refused the service")
            }

            else -> {
                protocolError("$message before the stream opened")
            }
        }

    private fun open(message: AdbMessage): CommandResult? =
        when (message.command) {
            AdbMessage.WRTE -> {
                output.feed(message.payload)
                send(AdbMessage(AdbMessage.OKAY, localId, remoteId))
                output.exitCode?.let(::finish)
            }

            AdbMessage.OKAY -> {
                if (!awaitingOkay) protocolError("$message without a write waiting for it")
                awaitingOkay = false
                null
            }

            else -> {
                closedByDevice()
            }
        }

    private fun finish(exitCode: Int): CommandResult {
        send(AdbMessage(AdbMessage.CLSE, localId, remoteId))
        reusable = true
        return CommandResult.Completed(exitCode, output.stdoutText(), output.stderrText())
    }

    /** adbd closed the open stream: the end of an `exec:` command, too early for the shell protocol. */
    private fun closedByDevice(): CommandResult {
        if (shell) throw TruncatedFrameException("the stream closed before the exit status")
        reusable = true
        return CommandResult.Completed(null, output.stdoutText(), output.stderrText())
    }

    @Throws(IOException::class)
    private fun send(message: AdbMessage) = AdbHandshake.send(handshake.output, message)

    companion object {
        private val STREAM_COMMANDS = setOf(AdbMessage.OKAY, AdbMessage.WRTE, AdbMessage.CLSE)

        /** The result for a failure of the blocking exchange. */
        fun classify(failure: IOException): CommandResult.Failed =
            when (failure) {
                is OutputTooLargeException -> CommandResult.Failed(CommandFailure.OUTPUT_TOO_LARGE, describe(failure))
                is TruncatedFrameException -> CommandResult.Failed(CommandFailure.CLOSED, describe(failure))
                is AdbProtocolException -> CommandResult.Failed(CommandFailure.PROTOCOL, describe(failure))
                else -> CommandResult.Failed(CommandFailure.CLOSED, describe(failure))
            }
    }
}
