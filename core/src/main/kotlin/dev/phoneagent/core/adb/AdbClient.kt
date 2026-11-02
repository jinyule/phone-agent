package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket

/** Where an adbd service listens: normally `127.0.0.1` and a port found by mDNS or a scan. */
public data class AdbEndpoint(
    /** Host name or address literal; not blank. */
    val host: String,
    /** TCP port, 1..65535. */
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port must be 1..$MAX_PORT, got $port" }
    }

    override fun toString(): String = "$host:$port"

    private companion object {
        const val MAX_PORT = 65_535
    }
}

/**
 * Time and size limits of the adb client, validated on construction. Every timeout covers a whole
 * operation, TCP connect included, and is measured in coroutine time.
 *
 * @property pairingTimeoutMs one [AdbClient.pair], > 0.
 * @property connectTimeoutMs one [AdbClient.connect] up to adbd's CNXN, > 0.
 * @property commandTimeoutMs one [AdbSession.run], from opening the stream to the exit status, > 0.
 * @property maxOutputBytes stdout and stderr of one command together, 1..[MAX_OUTPUT_LIMIT].
 */
public data class AdbConfig(
    val pairingTimeoutMs: Long = DEFAULT_PAIRING_TIMEOUT_MS,
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(pairingTimeoutMs > 0) { "pairingTimeoutMs must be > 0, got $pairingTimeoutMs" }
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be > 0, got $connectTimeoutMs" }
        require(commandTimeoutMs > 0) { "commandTimeoutMs must be > 0, got $commandTimeoutMs" }
        require(maxOutputBytes in 1..MAX_OUTPUT_LIMIT) {
            "maxOutputBytes must be 1..$MAX_OUTPUT_LIMIT, got $maxOutputBytes"
        }
    }

    /** Defaults. */
    public companion object {
        /** Pairing includes a TLS handshake and two round trips on the local network. */
        public const val DEFAULT_PAIRING_TIMEOUT_MS: Long = 15_000L

        /** Connecting includes a TLS handshake. */
        public const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000L

        /** Setup commands are short; starting the helper waits about a second. */
        public const val DEFAULT_COMMAND_TIMEOUT_MS: Long = 30_000L

        /** Setup commands print little. */
        public const val DEFAULT_MAX_OUTPUT_BYTES: Int = 1 shl 20

        /** Upper bound of [maxOutputBytes]: output is held in memory. */
        public const val MAX_OUTPUT_LIMIT: Int = 64 shl 20
    }
}

/**
 * The built-in wireless-debugging client (phase1 D3, §7.1 step ④): [pair] once with the code from
 * Settings, then [connect] and run fixed commands on the [AdbSession]. Pure JVM; the host supplies
 * the identity and the TLS exporter. Blocking I/O runs on [io]; cancellation closes the socket and
 * propagates, and a timeout ends as a typed failure.
 *
 * Capability boundary: this is app setup code. It is not a tool and must never be reachable from
 * the model or the tool layer (AGENTS.md §3.4); `PackageDependencyTest` keeps every other core
 * package from referring to it.
 */
public class AdbClient(
    private val identity: AdbIdentity,
    exporter: TlsKeyExporter,
    private val config: AdbConfig = AdbConfig(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tls = AdbTls(identity, exporter)
    private val pairing = AdbPairing(tls, identity)
    private val handshake = AdbHandshake(tls)

    /** Pairs [identity] with the device whose pairing service listens at [endpoint], using [code]. */
    public suspend fun pair(
        endpoint: AdbEndpoint,
        code: PairingCode,
    ): PairingResult {
        val socket = Socket()
        val timeoutMs = config.pairingTimeoutMs
        return try {
            val device =
                withTimeoutOrNull(timeoutMs) {
                    blockingIo(io, socket) { pairing.pair(socket, endpoint, code, timeoutMs.toSocketTimeout()) }
                }
            device?.let { PairingResult.Paired(it) }
                ?: PairingResult.Failed(PairingFailure.TIMEOUT, "no answer from $endpoint within $timeoutMs ms")
        } catch (failure: PairingError) {
            PairingResult.Failed(failure.reason, failure.message.orEmpty())
        } finally {
            socket.closeQuietly()
        }
    }

    /** Connects to adbd's wireless-debugging port at [endpoint] with the paired [identity]. */
    public suspend fun connect(endpoint: AdbEndpoint): ConnectResult {
        val socket = Socket()
        val timeoutMs = config.connectTimeoutMs
        var connected: Handshake? = null
        return try {
            connected =
                withTimeoutOrNull(timeoutMs) {
                    blockingIo(io, socket) { handshake.connect(socket, endpoint, timeoutMs.toSocketTimeout()) }
                }
            connected?.let { ConnectResult.Connected(AdbSession(it, config, io)) }
                ?: ConnectResult.Failed(ConnectFailure.TIMEOUT, "no answer from $endpoint within $timeoutMs ms")
        } catch (failure: ConnectError) {
            ConnectResult.Failed(failure.reason, failure.message.orEmpty())
        } finally {
            if (connected == null) socket.closeQuietly()
        }
    }

    private fun Long.toSocketTimeout(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun toString(): String = "AdbClient($identity)"
}
