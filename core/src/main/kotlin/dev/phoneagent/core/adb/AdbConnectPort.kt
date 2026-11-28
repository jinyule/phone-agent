/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbConnectPort.kt
 * and the service types of AdbPairing.kt.
 * Modified by PhoneAgent contributors: English documentation; the order is phase1 §7.1's (cached
 * port, mDNS, local scan); the result is typed and names the source that worked; the system
 * property and manual port sources are left to the host; a failing finder is the host's to report;
 * an UNAUTHORIZED is tried once more before it ends the search; the cache runs on the I/O
 * dispatcher, and a failing save closes the new session.
 */
package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The mDNS services adbd announces. */
public enum class AdbService(
    /** The DNS-SD service type. */
    public val type: String,
) {
    /** Announced while Settings' "Pair device with pairing code" dialog is open. */
    PAIRING("_adb-tls-pairing._tcp"),

    /** Announced while wireless debugging is on; the port changes every time it is turned on. */
    CONNECT("_adb-tls-connect._tcp"),
}

/** mDNS discovery of this device's adbd services; the host implements it (NsdManager on Android). */
public fun interface AdbServiceFinder {
    /**
     * Ports of the [service] instances on this device found within [timeoutMs], first found
     * first. Stale announcements may be among them, so all are returned. An empty list when
     * nothing was found or discovery is unavailable; the finder reports its own problems.
     */
    public suspend fun findPorts(
        service: AdbService,
        timeoutMs: Long,
    ): List<Int>
}

/**
 * The last port a connection succeeded on, kept by the host (app-private storage). Blocking;
 * [AdbConnectPort] calls it on the client's I/O dispatcher. A failure propagates out of
 * [AdbConnectPort.connect]: on [load] before anything is tried, on [save] after closing the session
 * it would have returned.
 */
public interface PortCache {
    /** The stored port, or `null`. */
    public fun load(): Int?

    /** Stores [port] for next time. */
    public fun save(port: Int)
}

/**
 * Settings of [AdbConnectPort], validated on construction.
 *
 * @property host where cached and mDNS ports are tried; adbd listens on every local address, so
 *   loopback (the default) always reaches it.
 * @property mdnsTimeoutMs how long mDNS discovery may take, > 0.
 */
public data class PortSearchConfig(
    val host: String = LOOPBACK,
    val mdnsTimeoutMs: Long = DEFAULT_MDNS_TIMEOUT_MS,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(mdnsTimeoutMs > 0) { "mdnsTimeoutMs must be > 0, got $mdnsTimeoutMs" }
    }

    /** Defaults. */
    public companion object {
        /** IPv4 loopback. */
        public const val LOOPBACK: String = "127.0.0.1"

        /** mDNS on the local network answers within a few hundred milliseconds when it works at all. */
        public const val DEFAULT_MDNS_TIMEOUT_MS: Long = 3_000L
    }
}

/** Where a candidate port came from. */
public enum class PortSource {
    /** [PortCache]. */
    CACHE,

    /** [AdbServiceFinder]. */
    MDNS,

    /** [LocalPortScan]. */
    SCAN,
}

/** Outcome of [AdbConnectPort.connect]. */
public sealed interface PortSearchResult {
    /** Connected; the caller owns [session]. */
    public class Connected(
        /** The open session. */
        public val session: AdbSession,
        /** Where it is connected. */
        public val endpoint: AdbEndpoint,
        /** Which source gave [endpoint]. */
        public val source: PortSource,
    ) : PortSearchResult

    /**
     * No candidate worked. [lastFailure] is the last connection failure (`null` when no source
     * gave a candidate at all); it is [ConnectFailure.UNAUTHORIZED] when the search stopped
     * because adbd probably refused our key, twice in a row on the same endpoint.
     */
    public data class Failed(
        /** The last connection failure, or `null` when there was no candidate. */
        val lastFailure: ConnectResult.Failed?,
        /** Every endpoint tried, in order, each once (a retry is not listed again). */
        val tried: List<AdbEndpoint>,
        /** The scan's result when the scan ran. */
        val scan: ScanResult?,
    ) : PortSearchResult
}

/**
 * Finds and connects to the wireless-debugging port (phase1 §7.1, reconnecting after a reboot or
 * a network change): the cached port first (a stale one is refused at once), then every port mDNS
 * reports (it may list stale ones too), and only then a [LocalPortScan] (mDNS registrations get
 * lost, and some networks block multicast). Candidates are told apart by address and port; each
 * is tried once. The first success is cached. [ConnectFailure.UNAUTHORIZED] is tried once more on
 * the same endpoint, since a connection that breaks right after TLS looks the same as a refusal;
 * a second one ends the search, as every other port leads to the same adbd. Other failures move
 * on to the next candidate.
 */
public class AdbConnectPort internal constructor(
    private val connector: suspend (AdbEndpoint) -> ConnectResult,
    private val cache: PortCache,
    private val finder: AdbServiceFinder,
    private val scan: LocalPortScan?,
    private val config: PortSearchConfig,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Connects with [client], calling [cache] on the client's I/O dispatcher; without [scan], only
     * the cache and mDNS are tried.
     */
    public constructor(
        client: AdbClient,
        cache: PortCache,
        finder: AdbServiceFinder,
        scan: LocalPortScan?,
        config: PortSearchConfig = PortSearchConfig(),
    ) : this(client::connect, cache, finder, scan, config, client.io)

    /** Tries the candidates in order until one connects. Cancellation propagates. */
    public suspend fun connect(): PortSearchResult {
        val search = Search()
        for (source in PortSource.entries) {
            search.attempt(source)?.let { return it }
        }
        return search.failed()
    }

    /** The state of one [connect]. */
    private inner class Search {
        private val tried = LinkedHashSet<AdbEndpoint>()
        private var last: ConnectResult.Failed? = null
        private var scanned: ScanResult? = null

        /** Tries the new candidates of [source]; the final result when one ends the search, else `null`. */
        suspend fun attempt(source: PortSource): PortSearchResult? {
            for (endpoint in candidates(source)) {
                if (!tried.add(endpoint)) continue
                outcome(connectTo(endpoint), endpoint, source)?.let { return it }
            }
            return null
        }

        /** Connects to [endpoint], once more after an UNAUTHORIZED that may have been a hang-up. */
        private suspend fun connectTo(endpoint: AdbEndpoint): ConnectResult {
            val first = connector(endpoint)
            val refused = first is ConnectResult.Failed && first.reason == ConnectFailure.UNAUTHORIZED
            return if (refused) connector(endpoint) else first
        }

        fun failed(): PortSearchResult.Failed = PortSearchResult.Failed(last, tried.toList(), scanned)

        private suspend fun candidates(source: PortSource): List<AdbEndpoint> =
            when (source) {
                PortSource.CACHE -> {
                    local(listOfNotNull(withContext(io) { cache.load() }))
                }

                PortSource.MDNS -> {
                    local(finder.findPorts(AdbService.CONNECT, config.mdnsTimeoutMs))
                }

                PortSource.SCAN -> {
                    scan
                        ?.scan(tried.toSet())
                        ?.also { scanned = it }
                        ?.found
                        .orEmpty()
                }
            }

        private suspend fun outcome(
            result: ConnectResult,
            endpoint: AdbEndpoint,
            source: PortSource,
        ): PortSearchResult? =
            when (result) {
                is ConnectResult.Connected -> {
                    save(result.session, endpoint.port)
                    PortSearchResult.Connected(result.session, endpoint, source)
                }

                is ConnectResult.Failed -> {
                    last = result
                    if (result.reason == ConnectFailure.UNAUTHORIZED) failed() else null
                }
            }
    }

    /** Caches [port]; when that fails or is cancelled, closes [session], which the caller then never gets. */
    private suspend fun save(
        session: AdbSession,
        port: Int,
    ) {
        var saved = false
        try {
            saved = withContext(io) { cache.save(port).let { true } }
        } finally {
            if (!saved) session.close()
        }
    }

    private fun local(ports: List<Int>): List<AdbEndpoint> {
        val valid = ports.filter { it in 1..MAX_PORT }
        return valid.map { AdbEndpoint(config.host, it) }
    }

    private companion object {
        const val MAX_PORT = 65_535
    }
}
