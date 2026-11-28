/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/setup/BuiltinShellManager.kt and
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/AndroidAdb.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: English documentation; written against PhoneAgent's core.adb;
 * the scan covers loopback only; UNAUTHORIZED is recorded but keeps the pairing; the port is cached
 * only after a handoff; no SystemProperties port lookup and no manual port.
 */
package dev.phoneagent.app.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import dev.phoneagent.core.adb.AdbClient
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.AdbConnectPort
import dev.phoneagent.core.adb.AdbEndpoint
import dev.phoneagent.core.adb.AdbIdentity
import dev.phoneagent.core.adb.AdbService
import dev.phoneagent.core.adb.AdbServiceFinder
import dev.phoneagent.core.adb.AdbSession
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.LocalPortScan
import dev.phoneagent.core.adb.PairingCode
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortSearchConfig
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * This app's built-in wireless adb client (phase1 §7.1 step ④), wired for Android: the identity
 * sealed in app-private storage, the pairing record and the port cache in [record], mDNS through
 * [finder], and the local scan on loopback only (the trust model, ADR 2026-11-03-adb-trust-model).
 * [search] uses `AdbConnectPort`, which already tries an UNAUTHORIZED endpoint a second time; one
 * that is still UNAUTHORIZED is recorded ([lastRefused]) and forgets the cached port, but keeps the
 * pairing: anything on this device can answer so. A launch on a connection caches its port only
 * when a helper handed off ([AdbPairingRecord.connectionEnded]). Needs Android 12, for
 * [ConscryptKeyExporter].
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class BuiltinAdb(
    private val store: AsideKeyStore,
    private val record: AdbPairingRecord,
    private val finder: AdbServiceFinder,
    private val identityName: String,
    /** The last resort of [search]: a scan of loopback only. */
    private val scan: () -> LocalPortScan,
    private val wirelessDebugging: () -> Boolean?,
) : HelperLauncher.Adb {
    private val identityLock = Mutex()
    private var identity: AdbIdentity? = null

    /** Whether a pairing succeeded with the current identity. */
    val isPaired: Boolean get() = record.isPaired

    override val notPaired: NotPairedReason? get() = record.notPaired

    /** Whether adbd refused our key in a search, and no launch connected and no pairing succeeded since. */
    val lastRefused: Boolean get() = record.lastRefused

    /** Whether wireless debugging is on; null when this app cannot read the setting. */
    override fun wirelessDebuggingOn(): Boolean? = wirelessDebugging()

    /**
     * The identity, loaded (or created) once per process. A new identity, or one other than the
     * identity that paired, drops the pairing record: the device does not know its key. One that
     * cannot be read now fails with [IOException] and stays, so a later call may read it.
     */
    suspend fun identity(): IdentityLoad = identity { AdbIdentity.loadOrCreate(store, identityName) }

    private suspend fun identity(load: suspend () -> IdentityLoad): IdentityLoad =
        identityLock.withLock {
            identity?.let { return@withLock IdentityLoad(it, IdentityLoad.Origin.STORED) }
            val loaded = load()
            record.identityLoaded(loaded.origin, loaded.identity.fingerprint)
            identity = loaded.identity
            loaded
        }

    /**
     * For [pair]: the stored identity, or, when it cannot be read now, a new one, with the old file
     * set aside ([AsideKeyStore.setAside]). The user asked to pair, so an identity that may never
     * be read again does not block it.
     */
    private suspend fun loadOrSetAside(): IdentityLoad =
        try {
            AdbIdentity.loadOrCreate(store, identityName)
        } catch (unreadable: IOException) {
            // Only the classes along the causes, each once: the messages may come from the Keystore.
            val classes =
                generateSequence<Throwable>(unreadable) { it.cause }
                    .map { it.javaClass.name }
                    .distinct()
                    .joinToString(" < ")
            Log.w(TAG, "cannot read the adb identity ($classes); pairing sets it aside for a new one")
            store.setAside()
            AdbIdentity.loadOrCreate(store, identityName)
        }

    /** A client for [identity]. */
    suspend fun client(): AdbClient = AdbClient(identity().identity, ConscryptKeyExporter)

    /** The port of Settings' open "Pair device with pairing code" dialog, by mDNS; null when none is announced. */
    suspend fun findPairingPort(): Int? =
        finder.findPorts(AdbService.PAIRING, PortSearchConfig.DEFAULT_MDNS_TIMEOUT_MS).firstOrNull()

    /**
     * Pairs with the dialog at [port] on this device; success records the pairing and the identity's
     * fingerprint. An identity that cannot be read now is set aside and a new one pairs.
     */
    suspend fun pair(
        port: Int,
        code: PairingCode,
    ): PairingResult {
        val identity = identity(::loadOrSetAside).identity
        return AdbClient(identity, ConscryptKeyExporter)
            .pair(AdbEndpoint(PortSearchConfig.LOOPBACK, port), code)
            .also { record.pairingEnded(it, identity.fingerprint) }
    }

    /**
     * Connects to this device's adbd: cached port, mDNS, then the loopback scan. Caches nothing:
     * only a launch that hands off does ([connect]).
     */
    suspend fun search(): PortSearchResult =
        AdbConnectPort(client(), record.portCache, finder, scan()).connect().also(record::searchEnded)

    override suspend fun connect(): HelperLauncher.Connection =
        when (val search = search()) {
            is PortSearchResult.Connected -> OpenSession(search.session, search.source, search.endpoint.port, record)
            is PortSearchResult.Failed -> HelperLauncher.Connection.Failed(search)
        }

    private class OpenSession(
        private val session: AdbSession,
        override val source: PortSource,
        private val port: Int,
        private val record: AdbPairingRecord,
    ) : HelperLauncher.Connection.Open {
        override suspend fun run(
            command: AdbCommand,
            stdin: ByteArray,
        ): CommandResult = session.run(command, stdin)

        override fun ended(outcome: LaunchOutcome) = record.connectionEnded(port, outcome)

        override fun close() = session.close()
    }

    companion object {
        private const val TAG = "PhoneAgentBuiltinAdb"
        private const val PREFS = "builtin_adb"
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"

        /** Settings.Global.ADB_WIFI_ENABLED, which is not public. */
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

        @Volatile
        private var instance: BuiltinAdb? = null

        /** The process-wide client. */
        fun of(context: Context): BuiltinAdb =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): BuiltinAdb {
            val ephemeral = lazy { ephemeralPorts() }
            return BuiltinAdb(
                store = SealedFileKeyStore(File(app.noBackupFilesDir, "adb/adbkey.sealed"), KeystoreKeyWrap()),
                record = AdbPairingRecord(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
                finder = NsdAdbServiceFinder.of(app),
                identityName = "phoneagent@${Build.MODEL}",
                scan = { loopbackScan(ephemeral.value) },
                wirelessDebugging = { wirelessDebugging(app) },
            )
        }

        /**
         * The kernel's ephemeral port range, where adbd's port comes from. Apps may not read it
         * (EACCES on Android 16); the scan then covers [LocalPortScan.FALLBACK_RANGE], which holds
         * Linux's default range.
         */
        private fun ephemeralPorts(): IntRange =
            LocalPortScan.parseRange(
                try {
                    File(PORT_RANGE_FILE).readText()
                } catch (unreadable: IOException) {
                    Log.i(TAG, "cannot read $PORT_RANGE_FILE ($unreadable); scanning ${LocalPortScan.FALLBACK_RANGE}")
                    null
                },
            )

        private fun wirelessDebugging(app: Context): Boolean? =
            try {
                Settings.Global
                    .getInt(app.contentResolver, ADB_WIFI_ENABLED, -1)
                    .takeIf { it >= 0 }
                    ?.let { it == 1 }
            } catch (notReadable: SecurityException) {
                // Apps cannot read every global setting on every release; then try anyway.
                Log.i(TAG, "cannot read $ADB_WIFI_ENABLED", notReadable)
                null
            }
    }
}

/**
 * The scan [BuiltinAdb.search] ends with: [range] on loopback only, so no probe leaves the device
 * (ADR 2026-11-03-adb-trust-model), skipping the ports this app listens on.
 */
internal fun loopbackScan(range: IntRange): LocalPortScan =
    LocalPortScan(
        { listOf(PortSearchConfig.LOOPBACK) },
        range,
        // The app listens on no port yet; ToolServer (phase1 §8) adds its own here.
        ownPorts = { emptySet() },
    )
