package dev.phoneagent.app.adb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.phoneagent.core.adb.AdbEndpoint
import dev.phoneagent.core.adb.AdbKeyStore
import dev.phoneagent.core.adb.AdbServiceFinder
import dev.phoneagent.core.adb.LocalPortScan
import dev.phoneagent.core.adb.PortSearchConfig
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

/**
 * [BuiltinAdb.search] with the real `AdbConnectPort` and loopback scan, against ports nothing
 * listens on: every candidate is refused at once, so the endpoints tried show which sources ran.
 */
@RunWith(RobolectricTestRunner::class)
class BuiltinAdbSearchTest {
    private val prefs =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(
            "test_builtin_adb_search",
            Context.MODE_PRIVATE,
        )
    private val record = AdbPairingRecord(prefs)
    private val ports = freePorts()
    private val cached = ports[0]
    private val announced = ports[1]
    private val scanned = ports[2]
    private var finds = 0

    private val adb =
        BuiltinAdb(
            store = MemoryKeyStore(),
            record = record,
            finder =
                AdbServiceFinder { _, _ ->
                    finds++
                    listOf(announced)
                },
            identityName = "test",
            scan = { LocalPortScan({ listOf(PortSearchConfig.LOOPBACK) }, scanned..scanned) },
            wirelessDebugging = { true },
        )

    private fun search(): PortSearchResult.Failed =
        runBlocking { withTimeout(SEARCH_TIMEOUT_MS) { adb.search() } } as PortSearchResult.Failed

    private fun loopback(port: Int) = AdbEndpoint(PortSearchConfig.LOOPBACK, port)

    @Test
    fun `a port that handed off is tried first`() {
        record.connectionEnded(cached, LaunchOutcome.Launched(4242, PortSource.MDNS))
        val search = search()
        assertEquals(listOf(loopback(cached), loopback(announced)), search.tried)
        assertNotNull(search.scan)
    }

    @Test
    fun `once a launch forgot the port, the next search starts at mDNS and ends with the scan`() {
        record.connectionEnded(cached, LaunchOutcome.Launched(4242, PortSource.MDNS))
        record.connectionEnded(cached, LaunchOutcome.NoHandoff(HANDOFF_MS))
        val search = search()
        assertEquals(listOf(loopback(announced)), search.tried)
        assertEquals(1, finds)
        assertNotNull(search.scan)
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val HANDOFF_MS = 1_000L

        /** Three ports that were free a moment ago; connections to them are refused. */
        fun freePorts(): List<Int> {
            val sockets = List(3) { ServerSocket(0) }
            return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
        }
    }
}

/** An identity store in memory. */
private class MemoryKeyStore : AdbKeyStore {
    private var stored: ByteArray? = null

    override fun read(): ByteArray? = stored

    override fun write(pkcs8: ByteArray) {
        stored = pkcs8
    }
}
