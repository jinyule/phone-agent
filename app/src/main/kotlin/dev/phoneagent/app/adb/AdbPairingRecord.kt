package dev.phoneagent.app.adb

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortCache
import dev.phoneagent.core.adb.PortSearchResult

/**
 * What the built-in adb client remembers between processes, in [prefs]: whether the current
 * identity is paired, whether adbd refused it the last time, and the port a helper last handed off
 * through. All are written with `apply`: a lost write only makes the next search longer, or asks for
 * one pairing more.
 *
 * Anything on this device can pose as adbd (ADR 2026-11-03-adb-trust-model), so nothing it can
 * answer is taken as final: a refusal keeps the pairing, and a port is cached only after a helper,
 * which the handoff checks, came through it.
 */
internal class AdbPairingRecord(
    private val prefs: SharedPreferences,
) {
    /** Whether a pairing succeeded with the current identity. */
    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    /**
     * Whether a search ended on UNAUTHORIZED, with no launch connecting and no pairing succeeding
     * since. Searches that fail otherwise say nothing about our key and leave it.
     */
    val lastRefused: Boolean get() = prefs.getBoolean(KEY_REFUSED, false)

    /**
     * The cache `AdbConnectPort` reads; what it saves there (its first connection) is dropped, since
     * [connectionEnded] caches a port only once a helper handed off through it.
     */
    val portCache: PortCache =
        object : PortCache {
            override fun load(): Int? = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..MAX_PORT }

            override fun save(port: Int) = Unit
        }

    /** A created or replaced identity is one the device does not know. */
    fun identityLoaded(origin: IdentityLoad.Origin) {
        if (origin != IdentityLoad.Origin.STORED) prefs.edit { putBoolean(KEY_PAIRED, false) }
    }

    fun pairingEnded(result: PairingResult) {
        if (result is PairingResult.Paired) {
            prefs.edit {
                putBoolean(KEY_PAIRED, true)
                putBoolean(KEY_REFUSED, false)
            }
        }
    }

    /**
     * A search that ended on UNAUTHORIZED (already tried twice by `AdbConnectPort`): adbd no longer
     * knows our key, or something posing as adbd answered first. The pairing is kept and the next
     * request tries again; the refusal is recorded, and the cached port is forgotten, so the next
     * search asks mDNS and the scan.
     */
    fun searchEnded(result: PortSearchResult) {
        if ((result as? PortSearchResult.Failed)?.lastFailure?.reason == ConnectFailure.UNAUTHORIZED) {
            Log.w(TAG, "adbd refused our key twice; the pairing is kept")
            prefs.edit {
                putBoolean(KEY_REFUSED, true)
                remove(KEY_PORT)
            }
        }
    }

    /**
     * A launch on a connection to [port] ended with [outcome]: something took our key, so the
     * refusal is cleared. A handoff caches [port]; no handoff, or a command that failed, forgets the
     * cached port (something posing as adbd may hold it), so the next search asks mDNS and the
     * scan. Other outcomes leave the cache.
     */
    fun connectionEnded(
        port: Int,
        outcome: LaunchOutcome,
    ) {
        prefs.edit {
            putBoolean(KEY_REFUSED, false)
            when (outcome) {
                is LaunchOutcome.Launched -> putInt(KEY_PORT, port)

                is LaunchOutcome.NoHandoff, is LaunchOutcome.CommandFailed -> remove(KEY_PORT)

                // The first two handed nothing to the endpoint; the rest never come from a connection.
                LaunchOutcome.AlreadyConnected, is LaunchOutcome.NoNonce, LaunchOutcome.NotPaired,
                LaunchOutcome.WirelessDebuggingOff, LaunchOutcome.Unauthorized, is LaunchOutcome.NoConnection,
                is LaunchOutcome.TooManyEarlyExits,
                -> Unit
            }
        }
    }
}

private const val TAG = "PhoneAgentBuiltinAdb"
private const val KEY_PAIRED = "paired"
private const val KEY_REFUSED = "refused"
private const val KEY_PORT = "port"
private const val MAX_PORT = 65_535
