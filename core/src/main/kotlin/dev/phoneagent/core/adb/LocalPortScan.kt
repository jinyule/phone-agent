/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/LocalPortScan.kt.
 * Modified by PhoneAgent contributors: English documentation; limits are a validated config;
 * one probe per port (TCP connect, then CNXN must be answered by STLS) on coroutines with bounded
 * parallelism instead of an NIO selector; the budget is coroutine time; own ports come from the
 * caller instead of a global registry.
 */
package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.TreeSet

/**
 * Limits of [LocalPortScan], validated on construction.
 *
 * @property budgetMs the whole scan, all hosts together, in coroutine time; > 0.
 * @property connectTimeoutMs one TCP connect (local refusals are immediate); 1..60000.
 * @property probeTimeoutMs one CNXN → STLS check on an open port, in coroutine time; > 0.
 * @property parallelism ports probed at once; 1..1024.
 */
public data class PortScanConfig(
    val budgetMs: Long = DEFAULT_BUDGET_MS,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    val parallelism: Int = DEFAULT_PARALLELISM,
) {
    init {
        require(budgetMs > 0) { "budgetMs must be > 0, got $budgetMs" }
        require(connectTimeoutMs in 1..MAX_CONNECT_TIMEOUT_MS) {
            "connectTimeoutMs must be 1..$MAX_CONNECT_TIMEOUT_MS, got $connectTimeoutMs"
        }
        require(probeTimeoutMs > 0) { "probeTimeoutMs must be > 0, got $probeTimeoutMs" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism must be 1..$MAX_PARALLELISM, got $parallelism" }
    }

    /** Defaults. */
    public companion object {
        /** A full sweep of the ephemeral range on loopback takes a few seconds. */
        public const val DEFAULT_BUDGET_MS: Long = 10_000L

        /** Local connections are refused or accepted at once. */
        public const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 200

        /** adbd answers CNXN at once; other services may never answer. */
        public const val DEFAULT_PROBE_TIMEOUT_MS: Long = 1_000L

        /** Concurrent connects; each is a short-lived socket. */
        public const val DEFAULT_PARALLELISM: Int = 64

        private const val MAX_CONNECT_TIMEOUT_MS = 60_000
        private const val MAX_PARALLELISM = 1024
    }
}

/** What a scan found: the endpoints that answered like adbd, and how many ports were open at all. */
public data class ScanResult(
    /** Endpoints whose service answered CNXN with STLS, in host order, then port order. */
    val found: List<AdbEndpoint>,
    /** Ports that accepted a TCP connection (diagnostics). */
    val openPorts: Int,
)

/** What one port turned out to be. */
internal enum class PortState { CLOSED, OPEN, ADB }

/** Looks at one port. */
internal fun interface PortProbe {
    suspend fun probe(
        host: String,
        port: Int,
    ): PortState
}

/**
 * The last way to find the wireless-debugging port (phase1 §7.1, after the cached port and mDNS):
 * scan this device's own addresses. adbd listens on a port the system picks from the ephemeral
 * range. Hosts are scanned in order (`127.0.0.1` first, then the device's Wi-Fi addresses, as
 * adbd may bind only one of them); once a host has an adbd, later hosts are skipped. A port
 * counts as adbd when it answers our CNXN with STLS, which other services, and a socket that
 * happens to connect to itself, never do. Only local addresses belong in [hosts].
 */
public class LocalPortScan internal constructor(
    private val hosts: () -> List<String>,
    private val range: IntRange,
    private val config: PortScanConfig,
    private val probe: PortProbe,
) {
    /** A scan of [range] on [hosts] (called once per scan) with real sockets on [io]. */
    public constructor(
        hosts: () -> List<String>,
        range: IntRange = FALLBACK_RANGE,
        config: PortScanConfig = PortScanConfig(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(hosts, range, config, SocketPortProbe(config, io))

    init {
        require(!range.isEmpty() && range.first >= 1 && range.last <= MAX_PORT) {
            "range must lie within 1..$MAX_PORT, got $range"
        }
    }

    /**
     * Scans every port of the range except [skip] (ports already tried, our own listeners). When
     * [PortScanConfig.budgetMs] runs out, returns what was found so far.
     */
    public suspend fun scan(skip: Set<Int>): ScanResult {
        val tally = Tally()
        withTimeoutOrNull(config.budgetMs) {
            for (host in hosts().distinct()) {
                sweep(host, skip, tally)
                if (tally.foundAny()) break
            }
        }
        return tally.result()
    }

    private suspend fun sweep(
        host: String,
        skip: Set<Int>,
        tally: Tally,
    ) {
        val ports = Channel<Int>(Channel.UNLIMITED)
        range.filter { it !in skip }.forEach { ports.trySend(it) }
        ports.close()
        coroutineScope {
            repeat(config.parallelism) {
                launch {
                    for (port in ports) tally.add(host, port, probe.probe(host, port))
                }
            }
        }
    }

    /** Results of one scan, shared by the probing coroutines and kept when the budget cancels them. */
    private class Tally {
        private val adb = LinkedHashMap<String, TreeSet<Int>>()
        private var open = 0

        @Synchronized
        fun add(
            host: String,
            port: Int,
            state: PortState,
        ) {
            if (state != PortState.CLOSED) open++
            if (state == PortState.ADB) adb.getOrPut(host) { TreeSet() } += port
        }

        @Synchronized
        fun foundAny(): Boolean = adb.isNotEmpty()

        @Synchronized
        fun result(): ScanResult {
            val found = adb.flatMap { (host, ports) -> ports.map { AdbEndpoint(host, it) } }
            return ScanResult(found, open)
        }
    }

    /** Port ranges. */
    public companion object {
        private const val MAX_PORT = 65_535
        private const val PRIVILEGED_PORTS = 1024

        /** Scanned when the system's ephemeral range cannot be read (covers the usual settings). */
        public val FALLBACK_RANGE: IntRange = 30_000..MAX_PORT

        /**
         * The range in `/proc/sys/net/ipv4/ip_local_port_range` (two numbers separated by
         * whitespace), or [FALLBACK_RANGE] when [text] is `null`, malformed or implausible.
         */
        public fun parseRange(text: String?): IntRange {
            val numbers = text?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull() }
            val low = numbers?.getOrNull(0)
            val high = numbers?.getOrNull(1)
            return when {
                numbers?.size != 2 || low == null || high == null -> FALLBACK_RANGE
                low in PRIVILEGED_PORTS..MAX_PORT && high in low..MAX_PORT -> low..high
                else -> FALLBACK_RANGE
            }
        }
    }
}

/** Probes with sockets: a TCP connect bounded by the connect timeout, then CNXN → STLS in coroutine time. */
internal class SocketPortProbe(
    private val config: PortScanConfig,
    private val io: CoroutineDispatcher,
) : PortProbe {
    override suspend fun probe(
        host: String,
        port: Int,
    ): PortState {
        val socket = Socket()
        return try {
            if (!blockingIo(io, socket) { connect(socket, host, port) }) {
                PortState.CLOSED
            } else {
                withTimeoutOrNull(config.probeTimeoutMs) { blockingIo(io, socket) { answersStls(socket) } }
                    ?: PortState.OPEN
            }
        } finally {
            socket.closeQuietly()
        }
    }

    private fun connect(
        socket: Socket,
        host: String,
        port: Int,
    ): Boolean {
        try {
            socket.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
        } catch (expectedRefusal: IOException) {
            // Refused, unreachable or too slow: nothing usable listens there.
            return false
        }
        return true
    }

    private fun answersStls(socket: Socket): PortState {
        val reply =
            try {
                cnxnReply(socket)
            } catch (expectedOtherService: IOException) {
                // Something that is not adbd: it hung up or sent bytes that are not an adb packet.
                null
            }
        return if (reply?.command == AdbMessage.STLS) PortState.ADB else PortState.OPEN
    }

    @Throws(IOException::class)
    private fun cnxnReply(socket: Socket): AdbMessage? {
        AdbHandshake.send(
            socket.getOutputStream(),
            AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, PROBE_BANNER),
        )
        return AdbMessage.read(
            BufferedInputStream(socket.getInputStream()),
            AdbMessage.MAX_PAYLOAD_V1,
            verifyChecksum = false,
        )
    }
}

/** The CNXN banner of a probe: a host without features. */
private val PROBE_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
