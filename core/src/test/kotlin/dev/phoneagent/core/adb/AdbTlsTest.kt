package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class AdbTlsTest {
    private val identity = TestTls.client

    @Test
    fun `the key manager presents our certificate whatever the server asks for`() {
        val keys = FixedKeyManager(identity.privateKey, identity.certificate)
        assertArrayEquals(arrayOf("adb"), keys.getClientAliases("RSA", null))
        assertFalse(keys.requested)
        assertEquals("adb", keys.chooseClientAlias(arrayOf("EC"), arrayOf(), null))
        assertTrue(keys.requested)
        val engineKeys = FixedKeyManager(identity.privateKey, identity.certificate)
        assertEquals("adb", engineKeys.chooseEngineClientAlias(arrayOf("RSA"), null, null))
        assertTrue(engineKeys.requested)
        assertNull(keys.getServerAliases("RSA", null))
        assertNull(keys.chooseServerAlias("RSA", null, null))
        assertSame(identity.certificate, keys.getCertificateChain("anything").single())
        assertSame(identity.privateKey, keys.getPrivateKey(null))
    }

    @Test
    fun `the trust manager accepts any server and no client`() {
        val chain = arrayOf(TestTls.device.certificate)
        TrustAll.checkServerTrusted(chain, "RSA")
        TrustAll.checkServerTrusted(chain, "RSA", null as java.net.Socket?)
        TrustAll.checkServerTrusted(chain, "RSA", null as javax.net.ssl.SSLEngine?)
        assertThrows<CertificateException> { TrustAll.checkClientTrusted(chain, "RSA") }
        assertThrows<CertificateException> { TrustAll.checkClientTrusted(chain, "RSA", null as java.net.Socket?) }
        assertThrows<CertificateException> {
            TrustAll.checkClientTrusted(
                chain,
                "RSA",
                null as javax.net.ssl.SSLEngine?,
            )
        }
        assertEquals(0, TrustAll.acceptedIssuers.size)
    }

    @Test
    fun `a handshake that fails before the server asked for our certificate says so`() =
        realTime {
            FakePairingServer("000000", FakePairingServer.Behavior.NOT_TLS).use { server ->
                java.net.Socket().use { plain ->
                    plain.connect(java.net.InetSocketAddress("127.0.0.1", server.endpoint.port))
                    val tls = AdbTls(identity, TestTls.exporter)
                    val failure = assertThrows<TlsHandshakeException> { tls.handshake(plain, server.endpoint) }
                    assertFalse(failure.certificateRequested)
                    assertTrue(failure.cause is javax.net.ssl.SSLException)
                }
            }
        }

    @Test
    fun `an exporter that returns the wrong length or fails is an IO failure`() {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket.use {
            val short = AdbTls(identity) { _, _, length -> ByteArray(length - 1) }
            assertThrows<IOException> { short.export(it, "adb-label\u0000", 64) }
            val failing = AdbTls(identity) { _, _, _ -> throw IOException("no exporter") }
            assertEquals("no exporter", assertThrows<IOException> { failing.export(it, "x", 1) }.message)
            val exact = AdbTls(identity) { _, label, length -> ByteArray(length) { label.length.toByte() } }
            assertArrayEquals(ByteArray(4) { 10 }, exact.export(it, "adb-label\u0000", 4))
        }
    }
}
