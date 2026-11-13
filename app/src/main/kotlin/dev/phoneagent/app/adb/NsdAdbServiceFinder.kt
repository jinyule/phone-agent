/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/NsdAdbServiceFinder.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: English documentation; the settle and resolve times are a
 * validated config; resolution through registerServiceInfoCallback from Android 14; failures to
 * start or stop discovery are logged instead of dropped.
 */
package dev.phoneagent.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.phoneagent.core.adb.AdbService
import dev.phoneagent.core.adb.AdbServiceFinder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Timing of [NsdAdbServiceFinder], validated on construction.
 *
 * @property settleMs after the first local service is resolved, how long to wait for more
 *   (a stale announcement and the current one often arrive together), 0..[MAX_MS].
 * @property resolveTimeoutMs the longest wait for one service's address and port, 1..[MAX_MS].
 */
internal data class NsdConfig(
    val settleMs: Long = 400,
    val resolveTimeoutMs: Long = 1_500,
) {
    init {
        require(settleMs in 0..MAX_MS) { "settleMs must be 0..$MAX_MS, got $settleMs" }
        require(resolveTimeoutMs in 1..MAX_MS) { "resolveTimeoutMs must be 1..$MAX_MS, got $resolveTimeoutMs" }
    }

    companion object {
        const val MAX_MS: Long = 10_000
    }
}

/**
 * Finds the ports of this device's adbd services by mDNS (NsdManager). Only services whose address
 * belongs to this device count: other phones on the network announce the same service types. The
 * services are resolved one at a time. Discovery that cannot start, and services that do not
 * resolve in time, are logged and give no port. Discovery is stopped when a search ends, also one
 * that ends before the system reported discovery started.
 *
 * Before Android 14 ([legacyResolve]) a resolve cannot be cancelled, and the system resolves one
 * service per NsdManager at a time (others fail with FAILURE_ALREADY_ACTIVE). So a resolve that did
 * not answer in time still holds that slot: until it calls back, every search of this finder ends
 * its mDNS part with the ports it has, and the caller goes on (the connect search to the scan). Use
 * one finder per NsdManager ([of]).
 */
internal class NsdAdbServiceFinder(
    nsd: Lazy<NsdManager>,
    private val callbacks: Executor,
    private val config: NsdConfig = NsdConfig(),
    private val isLocal: (InetAddress) -> Boolean = ::isThisDevice,
    /** Resolve with the pre-Android 14 resolveService; tests force it on a newer SDK. */
    private val legacyResolve: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
) : AdbServiceFinder {
    private val nsd by nsd

    /** Whether a legacy resolve has not called back yet. */
    private val legacyResolving = AtomicBoolean(false)

    override suspend fun findPorts(
        service: AdbService,
        timeoutMs: Long,
    ): List<Int> {
        if (legacyResolving.get()) {
            Log.i(TAG, "an earlier mDNS resolve has not ended; no mDNS search for ${service.type}")
            return emptyList()
        }
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val discovery = Discovery(found)
        nsd.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, discovery)
        val search = Search()
        try {
            withTimeoutOrNull(timeoutMs) { collect(found, search) }
        } finally {
            discovery.stop()
            // A count only: adbd's service names hold the device's serial number.
            if (search.others > 0) Log.i(TAG, "skipped ${search.others} ${service.type} services of other devices")
        }
        return search.ports.toList()
    }

    /** What one [findPorts] found: this device's ports, and how many services were other devices'. */
    private class Search {
        val ports = LinkedHashSet<Int>()
        var others = 0
    }

    /** Resolves what [found] delivers until it closes or [NsdConfig.settleMs] after the last port. */
    private suspend fun collect(
        found: Channel<NsdServiceInfo>,
        search: Search,
    ) {
        var next = found.receiveCatching().getOrNull()
        while (next != null) {
            if (legacyResolving.get()) {
                Log.i(TAG, "an mDNS resolve did not end in time; this search goes on without mDNS")
                return
            }
            resolve(next, search)
            next =
                if (search.ports.isEmpty()) {
                    found.receiveCatching().getOrNull()
                } else {
                    withTimeoutOrNull(config.settleMs) { found.receiveCatching().getOrNull() }
                }
        }
    }

    /** Adds the port of [info] to [search] when it resolves in time to an address of this device. */
    private suspend fun resolve(
        info: NsdServiceInfo,
        search: Search,
    ) {
        val resolved = withTimeoutOrNull(config.resolveTimeoutMs) { resolution(info) } ?: return
        if (addresses(resolved).any(isLocal)) search.ports += resolved.port else search.others++
    }

    private suspend fun resolution(info: NsdServiceInfo): NsdServiceInfo? =
        if (!legacyResolve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            suspendCancellableCoroutine { waiting ->
                val callback = ServiceInfoCallback { waiting.resume(it) }
                nsd.registerServiceInfoCallback(info, callbacks, callback)
                waiting.invokeOnCancellation { callback.unregister() }
            }
        } else {
            legacyResolution(info)
        }

    /** Holds [legacyResolving] until the system calls back, which may be after the wait ended. */
    private suspend fun legacyResolution(info: NsdServiceInfo): NsdServiceInfo? {
        if (!legacyResolving.compareAndSet(false, true)) return null
        return suspendCancellableCoroutine { waiting ->
            val listener =
                LegacyResolve { resolved ->
                    legacyResolving.set(false)
                    // Ignored when the wait was cancelled.
                    waiting.resume(resolved)
                }
            try {
                @Suppress("DEPRECATION") // The only way before Android 14.
                nsd.resolveService(info, listener)
            } catch (refused: IllegalArgumentException) {
                // An incomplete service or a listener in use: nothing will call back.
                legacyResolving.set(false)
                throw refused
            }
        }
    }

    private fun addresses(info: NsdServiceInfo): List<InetAddress> =
        if (!legacyResolve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION") // Replaced by hostAddresses in Android 14.
            listOfNotNull(info.host)
        }

    /** Feeds found services into [found]; closes it when discovery cannot start. */
    private inner class Discovery(
        private val found: Channel<NsdServiceInfo>,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            found.trySend(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Log.w(TAG, "mDNS discovery of $serviceType failed to start ($errorCode)")
            found.close()
        }

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Log.w(TAG, "mDNS discovery of $serviceType failed to stop ($errorCode)")
        }

        /**
         * Stops discovery, also before the system reported it started: NsdManager registers the
         * listener when discoverServices returns, and a later onDiscoveryStarted would otherwise
         * leave discovery running for the rest of the process.
         */
        fun stop() {
            found.close()
            try {
                nsd.stopServiceDiscovery(this)
            } catch (notActive: IllegalArgumentException) {
                // Discovery already ended (it failed to start, or the system stopped it): nothing to stop.
                Log.i(TAG, "mDNS discovery was not active any more", notActive)
            }
        }
    }

    /** Resumes with the resolved service once (or null on failure), then unregisters itself. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private inner class ServiceInfoCallback(
        private val done: (NsdServiceInfo?) -> Unit,
    ) : NsdManager.ServiceInfoCallback {
        @Volatile private var finished = false

        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(null)

        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = finish(serviceInfo)

        override fun onServiceLost() = finish(null)

        override fun onServiceInfoCallbackUnregistered() = Unit

        private fun finish(info: NsdServiceInfo?) {
            if (finished) return
            finished = true
            done(info)
            unregister()
        }

        fun unregister() {
            try {
                nsd.unregisterServiceInfoCallback(this)
            } catch (notRegistered: IllegalArgumentException) {
                // Registration failed or it is already unregistered.
                Log.i(TAG, "service info callback was not registered", notRegistered)
            }
        }
    }

    /** resolveService's listener before Android 14. */
    private class LegacyResolve(
        private val done: (NsdServiceInfo?) -> Unit,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(
            serviceInfo: NsdServiceInfo,
            errorCode: Int,
        ) = done(null)

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = done(serviceInfo)
    }

    companion object {
        private const val TAG = "PhoneAgentAdbMdns"

        @Volatile
        private var instance: NsdAdbServiceFinder? = null

        /** The process-wide finder, on the application's NsdManager. */
        fun of(context: Context): NsdAdbServiceFinder =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): NsdAdbServiceFinder =
            NsdAdbServiceFinder(
                lazy { app.getSystemService(NsdManager::class.java) },
                Executors.newSingleThreadExecutor(),
            )
    }
}

/** Whether [address] is loopback or an address of one of this device's network interfaces. */
internal fun isThisDevice(address: InetAddress): Boolean =
    address.isLoopbackAddress || interfaceAddresses().contains(address)

private fun interfaceAddresses(): Set<InetAddress> =
    try {
        NetworkInterface
            .getNetworkInterfaces()
            ?.asSequence()
            .orEmpty()
            .flatMap { it.inetAddresses.asSequence() }
            .toSet()
    } catch (unreadable: SocketException) {
        Log.w("PhoneAgentAdbMdns", "cannot list this device's addresses", unreadable)
        emptySet()
    }
