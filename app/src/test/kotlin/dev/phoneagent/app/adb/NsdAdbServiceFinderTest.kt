package dev.phoneagent.app.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNsdManager
import java.net.InetAddress
import java.util.concurrent.Executor

/** Discovery as ShadowNsdManager reports it, except that onDiscoveryStarted waits in [starts]. */
@Implements(NsdManager::class)
class LateStartNsdManager : ShadowNsdManager() {
    @Implementation
    override fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener,
    ) {
        super.discoverServices(serviceType, protocolType, null, { starts += it }, listener)
    }

    companion object {
        val starts = mutableListOf<Runnable>()
    }
}

/**
 * ShadowNsdManager records the discovery listeners, service info callbacks and resolve listeners;
 * the tests answer as the system would, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NsdAdbServiceFinderTest {
    private val nsd: NsdManager by lazy {
        ApplicationProvider.getApplicationContext<Context>().getSystemService(NsdManager::class.java)
    }
    private val shadow: ShadowNsdManager by lazy { Shadow.extract(nsd) }
    private val type = AdbService.CONNECT.type

    private fun finder(legacy: Boolean = false) =
        NsdAdbServiceFinder(
            lazyOf(nsd),
            Executor { it.run() },
            NsdConfig(settleMs = SETTLE_MS, resolveTimeoutMs = RESOLVE_MS),
            isLocal = { it.isLoopbackAddress },
            legacyResolve = legacy,
        )

    private fun service(name: String) =
        NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
        }

    private fun TestScope.search(finder: NsdAdbServiceFinder): Deferred<List<Int>> =
        async { finder.findPorts(AdbService.CONNECT, TIMEOUT_MS) }.also { runCurrent() }

    private fun discoveryListeners(): List<NsdManager.DiscoveryListener> = shadow.getDiscoveryListeners(type).orEmpty()

    private fun callbacks(info: NsdServiceInfo): List<NsdManager.ServiceInfoCallback> =
        shadow.getServiceInfoCallbacks(info).orEmpty()

    private fun resolveListeners(info: NsdServiceInfo): List<NsdManager.ResolveListener> =
        shadow.getResolveListeners(info).orEmpty()

    private fun found(vararg services: NsdServiceInfo) {
        val listener = discoveryListeners().single()
        services.forEach(listener::onServiceFound)
    }

    /** [info] resolved, as Android 14's ServiceInfoCallback reports it. */
    private fun resolve(
        info: NsdServiceInfo,
        address: String,
        port: Int,
    ) {
        val resolved =
            service(info.serviceName).apply {
                hostAddresses = listOf(InetAddress.getByName(address))
                this.port = port
            }
        callbacks(info).single().onServiceUpdated(resolved)
    }

    /** [info] resolved, as resolveService reports it before Android 14. */
    private fun legacyResolved(
        info: NsdServiceInfo,
        address: String,
        port: Int,
    ) = service(info.serviceName).apply {
        @Suppress("DEPRECATION") // What resolveService fills in.
        host = InetAddress.getByName(address)
        this.port = port
    }

    @Test
    fun `a service that does not resolve in time is unregistered, and the next one still counts`() =
        runTest {
            val stale = service("adb-stale")
            val real = service("adb-real")
            val ports = search(finder())
            found(stale, real)
            runCurrent()
            assertEquals(1, callbacks(stale).size)
            advanceTimeBy(RESOLVE_MS)
            runCurrent()
            assertEquals(0, callbacks(stale).size)
            resolve(real, "127.0.0.1", 40001)
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            assertTrue(ports.isCompleted)
            assertEquals(listOf(40001), ports.await())
            assertEquals(0, callbacks(real).size)
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())
        }

    @Test
    fun `a service at another device's address gives no port`() =
        runTest {
            val other = service("adb-other")
            val ports = search(finder())
            found(other)
            runCurrent()
            resolve(other, "192.0.2.1", 40002)
            advanceTimeBy(TIMEOUT_MS)
            runCurrent()
            assertEquals(emptyList<Int>(), ports.await())
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())
        }

    @Test
    fun `after a port the search waits the settle time for more, from the last one`() =
        runTest {
            val first = service("adb-first")
            val second = service("adb-second")
            val ports = search(finder())
            found(first)
            runCurrent()
            resolve(first, "127.0.0.1", 40001)
            advanceTimeBy(SETTLE_MS - 1)
            found(second)
            runCurrent()
            resolve(second, "127.0.0.1", 40002)
            advanceTimeBy(SETTLE_MS - 1)
            runCurrent()
            assertFalse(ports.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(ports.isCompleted)
            assertEquals(listOf(40001, 40002), ports.await())
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())
        }

    @Test
    @Config(shadows = [LateStartNsdManager::class])
    fun `a search that ends before discovery started still stops it`() =
        runTest {
            LateStartNsdManager.starts.clear()
            val ports = search(finder())
            advanceTimeBy(TIMEOUT_MS)
            runCurrent()
            assertEquals(emptyList<Int>(), ports.await())
            assertEquals(1, LateStartNsdManager.starts.size)
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())
            LateStartNsdManager.starts.forEach(Runnable::run)
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())
        }

    @Test
    fun `before Android 14 a resolve that did not answer ends the searches until it calls back`() =
        runTest {
            val finder = finder(legacy = true)
            val stuck = service("adb-stuck")
            val real = service("adb-real")
            val first = search(finder)
            found(stuck, real)
            runCurrent()
            assertEquals(1, resolveListeners(stuck).size)
            advanceTimeBy(RESOLVE_MS)
            runCurrent()
            // Ended at once, with the next service never asked for.
            assertTrue(first.isCompleted)
            assertEquals(emptyList<Int>(), first.await())
            assertEquals(0, resolveListeners(real).size)
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())

            val second = search(finder)
            assertTrue("no discovery while the resolve is out", second.isCompleted)
            assertEquals(emptyList<Int>(), second.await())
            assertEquals(emptyList<NsdManager.DiscoveryListener>(), discoveryListeners())

            resolveListeners(stuck).single().onResolveFailed(stuck, NsdManager.FAILURE_INTERNAL_ERROR)
            val third = search(finder)
            found(real)
            runCurrent()
            resolveListeners(real).single().onServiceResolved(legacyResolved(real, "127.0.0.1", 40001))
            advanceTimeBy(SETTLE_MS)
            runCurrent()
            assertEquals(listOf(40001), third.await())
        }
}

private const val SETTLE_MS = 400L
private const val RESOLVE_MS = 1_500L
private const val TIMEOUT_MS = 3_000L
