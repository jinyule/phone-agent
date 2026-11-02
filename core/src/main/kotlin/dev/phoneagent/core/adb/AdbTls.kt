/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbTls.kt.
 * Modified by PhoneAgent contributors: English documentation; the exporter is the only platform
 * hook (the TLS context always comes from the default provider); client mode only; the plain
 * socket is connected by the caller; a failed handshake reports whether our certificate was
 * already requested.
 */
package dev.phoneagent.core.adb

import java.io.IOException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The one TLS capability core cannot reach portably: the TLS 1.3 keying material exporter
 * (RFC 8446 §7.5), which pairing mixes into the SPAKE2 password. The host provides it: on Android
 * `android.net.ssl.SSLSockets.exportKeyingMaterial` (Conscrypt), on a JVM 25+
 * `ExtendedSSLSession.exportKeyingMaterialData`.
 */
public fun interface TlsKeyExporter {
    /**
     * [length] bytes exported for [label] (its UTF-8 bytes, here `"adb-label\u0000"`) with no
     * context, from [socket] after its handshake. Blocks; called on an IO thread. Throws
     * [IOException] (typically `SSLException`) when the platform cannot export.
     */
    @Throws(IOException::class)
    public fun export(
        socket: SSLSocket,
        label: String,
        length: Int,
    ): ByteArray
}

/**
 * The TLS handshake failed. [certificateRequested] tells whether the server had asked for our
 * certificate by then: adbd checks the key while it reads our certificate, and in TLS 1.3 its
 * refusal can then surface as an alert, a reset or a broken pipe inside our own handshake.
 */
internal class TlsHandshakeException(
    val certificateRequested: Boolean,
    cause: IOException,
) : IOException(describe(cause), cause)

/**
 * Client-side TLS 1.3 as adb uses it: both sides present self-signed certificates and neither
 * checks a chain. In pairing, SPAKE2 binds the code to this very channel through the exporter; on
 * connection, adbd checks our certificate's key against the paired keys. Like the adb host tool,
 * we do not authenticate adbd: what reaches it is limited to fixed commands, and the helper it
 * starts is checked by the app (uid, pid and nonce) before the app trusts it.
 */
internal class AdbTls(
    private val identity: AdbIdentity,
    private val exporter: TlsKeyExporter,
) {
    /**
     * Runs the client handshake over the connected [plain] socket; closing the result closes
     * [plain]. A failed handshake throws [TlsHandshakeException].
     */
    @Throws(IOException::class)
    fun handshake(
        plain: Socket,
        endpoint: AdbEndpoint,
    ): SSLSocket {
        val keys = FixedKeyManager(identity.privateKey, identity.certificate)
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keys), arrayOf(TrustAll), null)
        val socket = context.socketFactory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
        socket.enabledProtocols = arrayOf(TLS_1_3)
        socket.useClientMode = true
        try {
            socket.startHandshake()
        } catch (failure: IOException) {
            throw TlsHandshakeException(keys.requested, failure)
        }
        return socket
    }

    @Throws(IOException::class)
    fun export(
        socket: SSLSocket,
        label: String,
        length: Int,
    ): ByteArray {
        val material = exporter.export(socket, label, length)
        if (material.size != length) throw IOException("exporter returned ${material.size} bytes instead of $length")
        return material
    }

    companion object {
        const val ALIAS = "adb"
        private const val TLS_1_3 = "TLSv1.3"
    }
}

/**
 * Always presents [certificate], whatever issuers the server lists: adbd fills the TLS 1.3
 * `certificate_authorities` with names derived from its paired keys, which no chain of ours
 * matches, so a key manager that filters by issuer would send no certificate at all. Both the
 * socket and the engine variants answer, as providers built on an SSLEngine (Conscrypt) ask the
 * latter. One instance serves one handshake and records whether the server asked ([requested]).
 */
internal class FixedKeyManager(
    private val key: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    /** Whether the server has asked for our certificate. */
    @Volatile var requested: Boolean = false
        private set

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String> = arrayOf(AdbTls.ALIAS)

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = chosen()

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = chosen()

    private fun chosen(): String {
        requested = true
        return AdbTls.ALIAS
    }

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = key
}

/** Accepts any server certificate ([AdbTls] says why that is the adb protocol) and refuses every client. */
internal object TrustAll : X509ExtendedTrustManager() {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = refuseClients()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = refuseClients()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = refuseClients()

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    /** This context is client-only; it never accepts a client. */
    private fun refuseClients(): Nothing = throw CertificateException("client mode only")
}
