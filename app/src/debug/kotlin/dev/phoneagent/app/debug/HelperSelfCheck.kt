package dev.phoneagent.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.phoneagent.app.helper.HelperClient
import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.privileged.PrivilegedError
import dev.phoneagent.privileged.PrivilegedProtocol
import dev.phoneagent.privileged.handoff.HandoffContract
import kotlin.concurrent.thread

/**
 * Debug-only check of the helper connection, triggered from a computer:
 *
 * ```
 * adb shell am broadcast -n dev.phoneagent/dev.phoneagent.app.debug.HelperSelfCheckReceiver
 * ```
 *
 * Logs each step under the `PhoneAgentSelfCheck` tag and returns the summary as the broadcast's
 * result data. Exported only to holders of DUMP (shell): `am` cannot reach unexported receivers.
 */
class HelperSelfCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val client = HelperClient.of(context)
        thread(name = "helper-selfcheck") {
            val lines = HelperSelfCheck.run(client.state)
            lines.forEach { Log.i(TAG, it) }
            pending.resultData = lines.last()
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "PhoneAgentSelfCheck"
    }
}

/** The steps of the self-check; the last line is `PASS` or `FAIL: …`. */
internal object HelperSelfCheck {
    fun run(state: HelperState): List<String> {
        if (state !is HelperState.Connected) return listOf("helper state ${state.name}", "FAIL: no helper connected")
        val core = state.core
        val lines = mutableListOf("helper state CONNECTED pid ${state.pid}")
        val failures = mutableListOf<String>()
        try {
            val protocol = core.protocolVersion()
            lines += "protocolVersion=$protocol (app ${PrivilegedProtocol.VERSION})"
            if (protocol != PrivilegedProtocol.VERSION) failures += "protocol mismatch"

            val identity = core.identity().toList()
            lines += "identity=$identity (app pid ${Process.myPid()})"
            if (identity.take(2) !=
                listOf(HandoffContract.SHELL_UID, state.pid)
            ) {
                failures += "identity is not [2000, ${state.pid}]"
            }

            val probe = probeRefusal { core.probe() }
            lines += "probe -> ${probe ?: PrivilegedError.NOT_IMPLEMENTED}"
            if (probe != null) failures += "probe $probe"
            core.releaseAll()
            lines += "releaseAll ok"
        } catch (e: RemoteException) {
            failures += "helper call failed: $e"
        }
        lines += if (failures.isEmpty()) "PASS" else "FAIL: ${failures.joinToString("; ")}"
        return lines
    }

    /**
     * A-stage contract: `probe()` refuses with [PrivilegedError.NOT_IMPLEMENTED]. Null when it does,
     * otherwise how it broke the contract. Once the B stage implements `probe()`, this check must
     * check the probe result instead.
     */
    private fun probeRefusal(call: () -> Unit): String? =
        try {
            call()
            "returned (expected ${PrivilegedError.NOT_IMPLEMENTED})"
        } catch (e: UnsupportedOperationException) {
            if (PrivilegedError.isNotImplemented(e)) null else "threw an unexpected refusal: ${e.message}"
        }
}
