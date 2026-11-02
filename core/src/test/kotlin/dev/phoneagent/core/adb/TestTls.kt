/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/test/kotlin/com/wangzan101/mika/core/adb/TestTls.kt (TestTls).
 * Modified by PhoneAgent contributors: the JDK 25 exporter instead of Conscrypt (no test
 * dependency); a server context that checks the client's key as adbd does; adb key decoding;
 * real-time, barrier and virtual-time helpers.
 */
package dev.phoneagent.core.adb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.time.Duration.Companion.seconds

/**
 * TLS for JVM tests. The exporter is JDK 25's `ExtendedSSLSession.exportKeyingMaterialData`
 * (RFC 8446 §7.5, label as UTF-8, as BoringSSL takes it); it is called reflectively because core
 * compiles against the JDK 17 API (`-Xjdk-release=17`) while tests run on the build's JDK 25.
 */
internal object TestTls {
    val exporter: TlsKeyExporter = TlsKeyExporter { socket, label, length -> export(socket, label, length) }

    /** Shared keys: generating RSA-2048 takes a while, once per JVM is enough. */
    val client: AdbIdentity by lazy { AdbIdentity.generate("phoneagent@test") }
    val device: AdbIdentity by lazy { AdbIdentity.generate("device") }
    val stranger: AdbIdentity by lazy { AdbIdentity.generate("stranger") }

    private val exportMethod =
        ExtendedSSLSession::class.java.getMethod(
            "exportKeyingMaterialData",
            String::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )

    fun export(
        socket: SSLSocket,
        label: String,
        length: Int,
    ): ByteArray =
        try {
            exportMethod.invoke(socket.session, label, null, length) as ByteArray
        } catch (failure: InvocationTargetException) {
            throw IOException("exporter failed: ${failure.cause}", failure)
        }

    /**
     * A server-side context presenting [identity]; [accept] decides on the client's certificate
     * (throwing [CertificateException] refuses it), as adbd's verify callback does.
     */
    fun serverContext(
        identity: AdbIdentity,
        accept: (X509Certificate) -> Unit,
    ): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(ServerKeys(identity.privateKey, identity.certificate)), arrayOf(ClientCheck(accept)), null)
        }

    /** Wraps an accepted [plain] socket as a TLS 1.3 server that asks for a client certificate. */
    fun serve(
        context: SSLContext,
        plain: Socket,
    ): SSLSocket {
        val socket = context.socketFactory.createSocket(plain, "127.0.0.1", plain.port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = false
        socket.needClientAuth = true
        socket.startHandshake()
        return socket
    }

    /** The RSA key of an adb public key line, decoded the way adbd's `android_pubkey_decode` does. */
    fun decodeAdbKey(line: String): RSAPublicKey {
        val raw = Base64.getDecoder().decode(line.substringBefore(' '))
        require(raw.size == AdbPublicKey.SIZE) { "adb key structure has ${raw.size} bytes" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt() == 64) { "modulus size in words" }
        val modulus = fromLittleEndian(raw.copyOfRange(8, 264))
        val exponent = ByteBuffer.wrap(raw, 520, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
        val spec = RSAPublicKeySpec(modulus, BigInteger.valueOf(exponent.toLong()))
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private class ServerKeys(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?,
        ): Array<String>? = null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = null

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out Principal>?,
        ): Array<String> = arrayOf("device")

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = "device"

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = key
    }

    private class ClientCheck(
        private val accept: (X509Certificate) -> Unit,
    ) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String?,
        ) = accept(chain[0])

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String?,
            socket: Socket?,
        ) = accept(chain[0])

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String?,
            engine: SSLEngine?,
        ) = accept(chain[0])

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = throw CertificateException("server only")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket?,
        ) = throw CertificateException("server only")

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine?,
        ) = throw CertificateException("server only")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/**
 * Runs [block] in real time, for tests whose sockets must actually answer: under `runTest`,
 * virtual time would jump to a timeout while a real thread is still working. The outer limit
 * only turns a hang into a failure.
 */
internal fun realTime(block: suspend CoroutineScope.() -> Unit): Unit = inRealTime(block)

/** [realTime] with a result, for the real-time setup of a virtual-time test. */
internal fun <T> inRealTime(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(60.seconds, block) }

/**
 * Real threads for blocking socket I/O in tests, handed to the code under test as the app hands it
 * `Dispatchers.IO`. Daemon threads, so a stuck test cannot keep the JVM alive.
 */
internal val testIo: CoroutineDispatcher =
    Executors
        .newCachedThreadPool { task -> Thread(task, "test-io").apply { isDaemon = true } }
        .asCoroutineDispatcher()

/** Waits for a barrier of a fake on an IO thread (real-time tests). */
internal suspend fun <T> CompletableFuture<T>.awaitBarrier(): T = withContext(testIo) { get(10, TimeUnit.SECONDS) }

/**
 * Starts what was launched, then blocks the test thread until [barrier] completes, so that virtual
 * time stands still meanwhile; once the test suspends again, `runTest` jumps to the next timeout.
 */
internal fun <T> TestScope.holdTimeUntil(barrier: CompletableFuture<T>): T {
    testScheduler.runCurrent()
    return barrier.get(10, TimeUnit.SECONDS)
}

/** Virtual time of the test, in milliseconds. */
@OptIn(ExperimentalCoroutinesApi::class)
internal val TestScope.virtualTime: Long get() = testScheduler.currentTime
