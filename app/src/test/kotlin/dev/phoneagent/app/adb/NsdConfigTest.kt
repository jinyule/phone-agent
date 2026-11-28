package dev.phoneagent.app.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NsdConfigTest {
    @Test
    fun `mDNS timing is validated`() {
        NsdConfig(settleMs = 0, resolveTimeoutMs = 1)
        NsdConfig(settleMs = NsdConfig.MAX_MS, resolveTimeoutMs = NsdConfig.MAX_MS)
        val invalid: List<() -> NsdConfig> =
            listOf(
                { NsdConfig(settleMs = -1) },
                { NsdConfig(settleMs = NsdConfig.MAX_MS + 1) },
                { NsdConfig(resolveTimeoutMs = 0) },
                { NsdConfig(resolveTimeoutMs = NsdConfig.MAX_MS + 1) },
            )
        for (build in invalid) assertThrows(IllegalArgumentException::class.java) { build() }
    }

    @Test
    fun `loopback is this device, a documentation address is not`() {
        assertTrue(isThisDevice(InetAddress.getByName("127.0.0.1")))
        assertTrue(isThisDevice(InetAddress.getByName("::1")))
        assertFalse(isThisDevice(InetAddress.getByName("192.0.2.1")))
    }
}
