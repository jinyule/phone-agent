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
 * identity is paired, and the last port a connection worked on ([PortCache]). Both are written
 * with `apply`: a lost write only makes the next search longer, or asks for one pairing more.
 */
internal class AdbPairingRecord(
    private val prefs: SharedPreferences,
) : PortCache {
    /** Whether a pairing succeeded with the current identity and adbd has not refused it since. */
    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    /** A created or replaced identity is one the device does not know. */
    fun identityLoaded(origin: IdentityLoad.Origin) {
        if (origin != IdentityLoad.Origin.STORED) setPaired(false)
    }

    fun pairingEnded(result: PairingResult) {
        if (result is PairingResult.Paired) setPaired(true)
    }

    /**
     * A search that ended on UNAUTHORIZED (already tried twice by `AdbConnectPort`) means adbd no
     * longer knows our key: the record is dropped, so nothing launches until the user pairs again.
     */
    fun searchEnded(result: PortSearchResult) {
        if ((result as? PortSearchResult.Failed)?.lastFailure?.reason == ConnectFailure.UNAUTHORIZED) {
            Log.w(TAG, "adbd refused our key twice; pair again")
            setPaired(false)
        }
    }

    override fun load(): Int? = prefs.getInt(KEY_PORT, 0).takeIf { it in 1..MAX_PORT }

    override fun save(port: Int) {
        prefs.edit { putInt(KEY_PORT, port) }
    }

    private fun setPaired(paired: Boolean) {
        prefs.edit { putBoolean(KEY_PAIRED, paired) }
    }
}

private const val TAG = "PhoneAgentBuiltinAdb"
private const val KEY_PAIRED = "paired"
private const val KEY_PORT = "port"
private const val MAX_PORT = 65_535
