package dev.phoneagent.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.phoneagent.app.helper.HelperClient
import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.privileged.PrivilegedProtocol
import dev.phoneagent.privileged.handoff.HandoffContract
import kotlin.concurrent.thread

/**
 * Debug-only check of the helper, triggered from a computer:
 *
 * ```
 * adb shell am broadcast -n dev.phoneagent/dev.phoneagent.app.debug.HelperSelfCheckReceiver
 * adb shell am broadcast -n dev.phoneagent/… --es scenario display
 * ```
 *
 * `handoff` (the default) checks the connection; `display` walks through the display and input
 * capabilities ([DisplaySelfCheck]). Logs each step under the `PhoneAgentSelfCheck` tag and
 * returns the verdict as the broadcast's result data; the broadcast always finishes, with a
 * `FAIL: …` when the helper or the check threw. Exported only to holders of DUMP (shell): `am`
 * cannot reach unexported receivers.
 */
class HelperSelfCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val scenario = intent.getStringExtra("scenario") ?: SCENARIO_HANDOFF
        val request =
            HelperSelfCheck.Request(
                scenario = scenario,
                expect = intent.getIntExtra("expect", 0),
                cold = intent.getBooleanExtra("cold", false),
            )
        thread(name = "helper-selfcheck") {
            HelperSelfCheck.report({ HelperSelfCheck.run(context, HelperClient.of(context).state, request) }) {
                pending.resultData = it
                pending.finish()
            }
        }
    }

    private companion object {
        const val SCENARIO_HANDOFF = "handoff"
    }
}

/** The steps of the self-check; the last line is `PASS` or `FAIL: …`. */
internal object HelperSelfCheck {
    private const val TAG = "PhoneAgentSelfCheck"

    /** What the broadcast asked for. */
    data class Request(
        val scenario: String,
        val expect: Int = 0,
        val cold: Boolean = false,
    )

    /**
     * Runs [check], logs every line, then calls [finish] exactly once with the last line: `PASS`,
     * or `FAIL: …`, also when the check itself threw.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever breaks, the broadcast must finish, with a FAIL.
    fun report(
        check: () -> List<String>,
        finish: (String) -> Unit,
    ) {
        var summary = "FAIL: the self-check did not finish"
        try {
            summary = check().onEach { Log.i(TAG, it) }.last()
        } catch (e: RuntimeException) {
            Log.e(TAG, "the self-check threw", e)
            summary = "FAIL: the self-check threw $e"
        } finally {
            finish(summary)
        }
    }

    /** Runs [request] against the currently connected helper. */
    fun run(
        context: Context,
        state: HelperState,
        request: Request,
    ): List<String> {
        if (state !is HelperState.Connected) return listOf("helper state ${state.name}", "FAIL: no helper connected")
        val header = "helper state CONNECTED pid ${state.pid}"
        return when (request.scenario) {
            "handoff" -> {
                handoff(state)
            }

            "display" -> {
                listOf(header) + capabilities(state) { DisplaySelfCheck(context, state.core).run() }
            }

            "launch" -> {
                listOf(header) +
                    capabilities(state) { LaunchGateSelfCheck(state.core, request.expect, request.cold).run() }
            }

            else -> {
                listOf("unknown scenario '${request.scenario}'", "FAIL: scenario must be handoff, display or launch")
            }
        }
    }

    /** A capability walk; a helper call it lets through fails the walk rather than the broadcast. */
    @Suppress("TooGenericExceptionCaught") // Parcel.readException rethrows any RuntimeException of the helper.
    private fun capabilities(
        state: HelperState.Connected,
        check: () -> List<String>,
    ): List<String> =
        try {
            check()
        } catch (e: RemoteException) {
            listOf("helper ${state.pid} call failed: $e", "FAIL: the helper went away")
        } catch (e: RuntimeException) {
            listOf("helper ${state.pid} call threw: $e", "FAIL: a helper call threw")
        }

    /** Every step runs, so `releaseAll` is tried even after an earlier call failed. */
    private fun handoff(state: HelperState.Connected): List<String> {
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
}
