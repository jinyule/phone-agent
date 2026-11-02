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
 * result data; the broadcast always finishes, with a `FAIL: …` when the helper or the check threw.
 * Exported only to holders of DUMP (shell): `am` cannot reach unexported receivers.
 */
class HelperSelfCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        thread(name = "helper-selfcheck") {
            HelperSelfCheck.report({ HelperClient.of(context).state }) { summary ->
                pending.resultData = summary
                pending.finish()
            }
        }
    }
}

/** The steps of the self-check; the last line is `PASS` or `FAIL: …`. */
internal object HelperSelfCheck {
    private const val TAG = "PhoneAgentSelfCheck"

    /**
     * Runs the check on what [state] returns, logs every line, then calls [finish] exactly once
     * with the last line: `PASS`, or `FAIL: …`, also when the check itself threw.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever breaks, the broadcast must finish, with a FAIL.
    fun report(
        state: () -> HelperState,
        finish: (String) -> Unit,
    ) {
        var summary = "FAIL: the self-check did not finish"
        try {
            summary = run(state()).onEach { Log.i(TAG, it) }.last()
        } catch (e: RuntimeException) {
            Log.e(TAG, "the self-check threw", e)
            summary = "FAIL: the self-check threw $e"
        } finally {
            finish(summary)
        }
    }

    /** Every step runs, so `releaseAll` is tried even after an earlier call failed. */
    fun run(state: HelperState): List<String> {
        if (state !is HelperState.Connected) return listOf("helper state ${state.name}", "FAIL: no helper connected")
        val core = state.core
        val lines = mutableListOf("helper state CONNECTED pid ${state.pid}")
        val failures = mutableListOf<String>()
        failures.ask("protocolVersion") { core.protocolVersion() }?.let { protocol ->
            lines += "protocolVersion=$protocol (app ${PrivilegedProtocol.VERSION})"
            if (protocol != PrivilegedProtocol.VERSION) failures += "protocol mismatch"
        }
        failures.ask("identity") { core.identity().toList() }?.let { identity ->
            lines += "identity=$identity (app pid ${Process.myPid()})"
            if (identity.take(2) != listOf(HandoffContract.SHELL_UID, state.pid)) {
                failures += "identity is not [2000, ${state.pid}]"
            }
        }
        val probe = probeRefusal { core.probe() }
        lines += "probe -> ${probe ?: PrivilegedError.NOT_IMPLEMENTED}"
        if (probe != null) failures += "probe $probe"
        failures.ask("releaseAll") { core.releaseAll() }?.let { lines += "releaseAll ok" }
        lines += if (failures.isEmpty()) "PASS" else "FAIL: ${failures.joinToString("; ")}"
        return lines
    }

    /**
     * One call into the helper; null after adding to this list how it failed. Parcel.readException
     * rethrows whatever RuntimeException the helper threw, so any of them is a failure to report.
     */
    @Suppress("TooGenericExceptionCaught") // See above: the helper is the thing under test.
    private fun <T : Any> MutableList<String>.ask(
        what: String,
        call: () -> T,
    ): T? =
        try {
            call()
        } catch (e: RemoteException) {
            add("$what failed: $e")
            null
        } catch (e: RuntimeException) {
            add("$what threw: $e")
            null
        }

    /**
     * A-stage contract: `probe()` refuses with [PrivilegedError.NOT_IMPLEMENTED]. Null when it does,
     * otherwise how it broke the contract. Once the B stage implements `probe()`, this check must
     * check the probe result instead.
     */
    @Suppress("TooGenericExceptionCaught") // As in ask: any RuntimeException of the helper is reported.
    private fun probeRefusal(call: () -> Unit): String? =
        try {
            call()
            "returned (expected ${PrivilegedError.NOT_IMPLEMENTED})"
        } catch (e: UnsupportedOperationException) {
            if (PrivilegedError.isNotImplemented(e)) null else "threw an unexpected refusal: ${e.message}"
        } catch (e: RemoteException) {
            "failed: $e"
        } catch (e: RuntimeException) {
            "threw: $e"
        }
}
