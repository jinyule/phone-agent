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
 * services are resolved one at a time (older systems resolve only one at once). Discovery that
 * cannot start, and services that do not resolve in time, are logged and give no port.
 */
internal class NsdAdbServiceFinder(
    nsd: Lazy<NsdManager>,
    private val callbacks: Executor,
    private val config: NsdConfig = NsdConfig(),
    private val isLocal: (InetAddress) -> Boolean = ::isThisDevice,
) : AdbServiceFinder {
    private val nsd by nsd

    override suspend fun findPorts(
        service: AdbService,
        timeoutMs: Long,
    ): List<Int> {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val discovery = Discovery(found)
        nsd.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, discovery)
        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) { collect(found, ports) }
        } finally {
            discovery.stop()
        }
        return ports.toList()
    }

    /** Resolves what [found] delivers until it closes or [NsdConfig.settleMs] after the first port. */
    private suspend fun collect(
        found: Channel<NsdServiceInfo>,
        ports: MutableSet<Int>,
    ) {
        var next = found.receiveCatching().getOrNull()
        while (next != null) {
            resolve(next)?.let { ports += it }
            next =
                if (ports.isEmpty()) {
                    found.receiveCatching().getOrNull()
                } else {
                    withTimeoutOrNull(config.settleMs) { found.receiveCatching().getOrNull() }
                }
        }
    }

    /** The port of [info] when it resolves in time to an address of this device, else null. */
    private suspend fun resolve(info: NsdServiceInfo): Int? {
        val resolved = withTimeoutOrNull(config.resolveTimeoutMs) { resolution(info) }
        val local = resolved?.let { addresses(it).any(isLocal) } == true
        if (resolved != null && !local) Log.i(TAG, "${info.serviceName} is another device's; skipped")
        return resolved?.port?.takeIf { local }
    }

    private suspend fun resolution(info: NsdServiceInfo): NsdServiceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            suspendCancellableCoroutine { waiting ->
                val callback = ServiceInfoCallback { waiting.resume(it) }
                nsd.registerServiceInfoCallback(info, callbacks, callback)
                waiting.invokeOnCancellation { callback.unregister() }
            }
        } else {
            suspendCancellableCoroutine { waiting ->
                @Suppress("DEPRECATION") // The only way before Android 14.
                nsd.resolveService(info, LegacyResolve { waiting.resume(it) })
            }
        }

    private fun addresses(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION") // Replaced by hostAddresses in Android 14.
            listOfNotNull(info.host)
        }

    /** Feeds found services into [found]; closes it when discovery cannot start. */
    private inner class Discovery(
        private val found: Channel<NsdServiceInfo>,
    ) : NsdManager.DiscoveryListener {
        @Volatile private var started = false

        override fun onDiscoveryStarted(serviceType: String) {
            started = true
        }

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

        fun stop() {
            found.close()
            if (!started) return
            try {
                nsd.stopServiceDiscovery(this)
            } catch (notActive: IllegalArgumentException) {
                // Discovery already ended (it failed, or the system stopped it): nothing to stop.
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

    private companion object {
        const val TAG = "PhoneAgentAdbMdns"
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
