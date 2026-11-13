package dev.phoneagent.app.adb

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.IdentityLoad
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortCache
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource

/** Why no pairing is on record ([AdbPairingRecord.notPaired]). */
internal enum class NotPairedReason {
    /** No pairing has succeeded since the app's data was created. */
    NEVER_PAIRED,

    /**
     * The paired identity was replaced: its key could not be opened (the keystore key was lost, or
     * the file was damaged) or was gone. The device does not know the new one.
     */
    IDENTITY_REPLACED,
}

/**
 * What the built-in adb client remembers between processes, in [prefs]: whether the current
 * identity is paired and, when not, why, and the fingerprint of the identity that paired; whether
 * adbd refused it the last time; and the port a helper last handed off through. All are written
 * with `apply`: a lost write only makes the next search longer, or asks for one pairing more.
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
     * Why no pairing is on record, or `null` when one is. A reason this version does not know (one
     * a later version stored) reads as [NotPairedReason.NEVER_PAIRED].
     */
    val notPaired: NotPairedReason?
        get() {
            if (isPaired) return null
            val stored = prefs.getString(KEY_NOT_PAIRED, null)
            return NotPairedReason.entries.firstOrNull { it.name == stored } ?: NotPairedReason.NEVER_PAIRED
        }

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

    /**
     * The identity loaded as [origin], with [fingerprint] (`AdbIdentity.fingerprint`). A created or
     * replaced identity, or a stored one other than the identity that paired, is one the device does
     * not know: a pairing on record is dropped, as [NotPairedReason.IDENTITY_REPLACED]. The
     * fingerprint covers a process that died after the identity file was replaced and before this
     * call: the next process loads the new identity as stored. A pairing recorded without a
     * fingerprint (by a build before it was kept) is judged by [origin] alone. Without a pairing,
     * the reason stays as it was.
     */
    fun identityLoaded(
        origin: IdentityLoad.Origin,
        fingerprint: String,
    ) {
        if (!isPaired) return
        val paired = prefs.getString(KEY_FINGERPRINT, null)
        val other = paired != null && paired != fingerprint
        if (origin == IdentityLoad.Origin.STORED && !other) return
        Log.w(TAG, "the paired adb identity was replaced (${if (other) "another key" else "$origin"}); pair again")
        prefs.edit {
            putBoolean(KEY_PAIRED, false)
            putString(KEY_NOT_PAIRED, NotPairedReason.IDENTITY_REPLACED.name)
        }
    }

    /** A pairing with the identity of [fingerprint] ended as [result]; success records it. */
    fun pairingEnded(
        result: PairingResult,
        fingerprint: String,
    ) {
        if (result is PairingResult.Paired) {
            prefs.edit {
                putBoolean(KEY_PAIRED, true)
                putString(KEY_FINGERPRINT, fingerprint)
                remove(KEY_NOT_PAIRED)
                putBoolean(KEY_REFUSED, false)
            }
        }
    }

    /**
     * A search ended with [result]. A search that tried the cached port and did not connect on it
     * forgets it, whatever the failure there (refused, no answer, TLS, unreachable): something posing
     * as adbd may hold it, and each later search would wait on it first. The next search then asks
     * mDNS and the scan; a port connected to is cached only by a handoff ([connectionEnded]).
     */
    fun searchEnded(result: PortSearchResult) =
        when (result) {
            is PortSearchResult.Connected -> connectedThrough(result.source)
            is PortSearchResult.Failed -> failed(result)
        }

    /**
     * A search connected through [source]. The cached port, when there is one, is always tried
     * first (`AdbConnectPort`): unless [source] is the cache, it did not take us, and is forgotten.
     */
    fun connectedThrough(source: PortSource) {
        if (source != PortSource.CACHE && portCache.load() != null) prefs.edit { remove(KEY_PORT) }
    }

    /**
     * A failed search: one that tried the cached port forgets it. One that ended on UNAUTHORIZED
     * (already tried twice by `AdbConnectPort`): adbd no longer knows our key, or something posing
     * as adbd answered first. The pairing is kept and the next request tries again; the refusal is
     * recorded, and the cached port is forgotten too.
     */
    private fun failed(failed: PortSearchResult.Failed) {
        val cached = portCache.load()
        val triedCached = failed.tried.any { it.port == cached }
        val refused = failed.lastFailure?.reason == ConnectFailure.UNAUTHORIZED
        if (refused) Log.w(TAG, "adbd refused our key twice; the pairing is kept")
        if (triedCached || refused) {
            prefs.edit {
                if (refused) putBoolean(KEY_REFUSED, true)
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
                LaunchOutcome.AlreadyConnected, is LaunchOutcome.NoNonce, is LaunchOutcome.NotPaired,
                LaunchOutcome.WirelessDebuggingOff, LaunchOutcome.Unauthorized, is LaunchOutcome.NoConnection,
                is LaunchOutcome.TooManyEarlyExits,
                -> Unit
            }
        }
    }
}

private const val TAG = "PhoneAgentBuiltinAdb"
private const val KEY_PAIRED = "paired"
private const val KEY_NOT_PAIRED = "not_paired"
private const val KEY_FINGERPRINT = "fingerprint"
private const val KEY_REFUSED = "refused"
private const val KEY_PORT = "port"
private const val MAX_PORT = 65_535
