package dev.phoneagent.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import dev.phoneagent.app.adb.BuiltinAdb
import dev.phoneagent.app.adb.ConscryptKeyExporter
import dev.phoneagent.app.adb.HelperAutoLaunch
import dev.phoneagent.app.adb.HelperLaunchCommand
import dev.phoneagent.app.adb.HelperLauncher
import dev.phoneagent.app.adb.LaunchConfig
import dev.phoneagent.app.adb.LaunchOutcome
import dev.phoneagent.app.adb.NsdAdbServiceFinder
import dev.phoneagent.app.adb.loopbackScan
import dev.phoneagent.app.helper.HelperClient
import dev.phoneagent.core.adb.AdbClient
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.AdbIdentity
import dev.phoneagent.core.adb.AdbKeyStore
import dev.phoneagent.core.adb.AdbService
import dev.phoneagent.core.adb.AdbSession
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.ConnectResult
import dev.phoneagent.core.adb.LocalPortScan
import dev.phoneagent.core.adb.PairingCode
import dev.phoneagent.core.adb.PairingResult
import dev.phoneagent.core.adb.PortSearchConfig
import dev.phoneagent.core.adb.PortSearchResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Debug-only check of the built-in adb client on a device, triggered from a computer:
 *
 * ```
 * adb -s <serial> shell am broadcast -n dev.phoneagent/dev.phoneagent.app.debug.AdbSelfCheckReceiver \
 *     --es scenario pair --es code 123456 [--ei port 37000]
 * adb -s <serial> shell am broadcast -n dev.phoneagent/… --es scenario connect
 * ```
 *
 * Scenarios: `identity` (load or create the key; `--ez generate true` also times a fresh RSA-2048
 * key), `mdns` (the announced ports), `pair` (the code read from Settings; without `port`, the
 * pairing port comes from mDNS), `connect` (cached port, mDNS, scan; then `id`), `stdin` (`cat`
 * over the shell protocol: stdin must arrive and be closed), `unpaired` (a fresh key must get
 * UNAUTHORIZED), `scan` (the app's loopback scan alone) and `launch` (the helper, as the app launches it).
 * Logs each line under `PhoneAgentSelfCheck` and returns the verdict (`PASS` or `FAIL: …`) as the
 * broadcast's result data. The pairing code and the launch nonce are never logged.
 */
class AdbSelfCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val request =
            AdbSelfCheck.Request(
                scenario = intent.getStringExtra("scenario") ?: "connect",
                code = intent.getStringExtra("code"),
                port = intent.getIntExtra("port", 0),
                generate = intent.getBooleanExtra("generate", false),
            )
        val app = context.applicationContext
        thread(name = "adb-selfcheck") {
            HelperSelfCheck.report({ AdbSelfCheck.run(app, request) }) {
                pending.resultData = it
                pending.finish()
            }
        }
    }
}

/** The scenarios of [AdbSelfCheckReceiver]. */
internal object AdbSelfCheck {
    /** What the broadcast asked for; [code] is never printed. */
    data class Request(
        val scenario: String,
        val code: String? = null,
        val port: Int = 0,
        val generate: Boolean = false,
    ) {
        override fun toString(): String = "Request(scenario=$scenario, port=$port, generate=$generate)"
    }

    fun run(
        context: Context,
        request: Request,
    ): List<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            listOf("SDK ${Build.VERSION.SDK_INT}", "FAIL: the built-in adb client needs Android 12")
        } else {
            runSupported(context, request)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun runSupported(
        context: Context,
        request: Request,
    ): List<String> {
        val adb = BuiltinAdb.of(context)
        val steps = SelfCheckSteps()
        steps.note(
            "SDK ${Build.VERSION.SDK_INT}, paired=${adb.isPaired}, wirelessDebugging=${adb.wirelessDebuggingOn()}",
        )
        runBlocking { Scenarios(context, adb, steps).run(request) }
        return steps.lines()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Scenarios(
        private val context: Context,
        private val adb: BuiltinAdb,
        private val steps: SelfCheckSteps,
    ) {
        suspend fun run(request: Request) {
            when (request.scenario) {
                "identity" -> identity(request.generate)
                "mdns" -> mdns()
                "pair" -> pair(request)
                "connect" -> connected { session -> id(session) }
                "stdin" -> connected { session -> stdin(session) }
                "unpaired" -> unpaired()
                "scan" -> scan()
                "launch" -> launch()
                else -> steps.fail("scenario", "unknown '${request.scenario}'")
            }
        }

        private suspend fun identity(generate: Boolean) {
            val (load, ms) = timed { adb.identity() }
            steps.check("identity", true, "${load.origin} in $ms ms, ${load.identity}")
            if (generate) {
                val (fresh, genMs) = timed { AdbIdentity.loadOrCreate(MemoryKeyStore(), "phoneagent-selfcheck") }
                steps.check("generate", fresh.origin.name == "CREATED", "RSA-2048 created in $genMs ms")
            }
        }

        private suspend fun mdns() {
            // The app's own finder: before Android 14 one resolve at a time holds for the whole process.
            val finder = NsdAdbServiceFinder.of(context)
            for (service in AdbService.entries) {
                val (ports, ms) = timed { finder.findPorts(service, PortSearchConfig.DEFAULT_MDNS_TIMEOUT_MS) }
                steps.note("mdns ${service.type}: $ports in $ms ms")
            }
        }

        private suspend fun pair(request: Request) {
            val code =
                request.code?.let { pairingCodeOrNull(it, steps) }
                    ?: return run { steps.fail("pair", "no valid --es code") }
            val port = request.port.takeIf { it > 0 } ?: adb.findPairingPort()
            if (port == null) {
                steps.fail("pair", "no pairing port given or announced (is the pairing dialog open?)")
                return
            }
            val (result, ms) = timed { adb.pair(port, code) }
            steps.check("pair", result is PairingResult.Paired, "port $port in $ms ms: $result")
            if (adb.isPaired) HelperAutoLaunch.startIfPaired(context)
        }

        private suspend fun connected(block: suspend (AdbSession) -> Unit) {
            val (search, ms) = timed { adb.search() }
            when (search) {
                is PortSearchResult.Connected -> {
                    steps.check("connect", true, "${search.endpoint} from ${search.source} in $ms ms")
                    steps.note("features ${search.session.features.sorted()}")
                    search.session.use { block(it) }
                }

                is PortSearchResult.Failed -> {
                    steps.fail("connect", "in $ms ms: $search")
                }
            }
        }

        private suspend fun id(session: AdbSession) {
            val result = session.run(AdbCommand("id"))
            val uid = (result as? CommandResult.Completed)?.stdout?.trim()
            steps.check("exec id", uid?.startsWith("uid=2000(shell)") == true, "$result")
        }

        private suspend fun stdin(session: AdbSession) {
            val text = "phoneagent stdin check\n"
            val result = session.run(AdbCommand("cat"), text.toByteArray())
            val passed = result is CommandResult.Completed && result.stdout == text && result.exitCode == 0
            steps.check("stdin over ${if ("shell_v2" in session.features) "shell,v2" else "exec"}", passed, "$result")
        }

        /** A key the device has never seen must be refused as UNAUTHORIZED, not as a TLS failure. */
        private suspend fun unpaired() {
            val endpoint =
                when (val search = adb.search()) {
                    is PortSearchResult.Connected -> search.session.use { search.endpoint }
                    is PortSearchResult.Failed -> return run { steps.fail("connect", "$search") }
                }
            val stranger = AdbIdentity.loadOrCreate(MemoryKeyStore(), "phoneagent-selfcheck-unpaired").identity
            val result = AdbClient(stranger, ConscryptKeyExporter).connect(endpoint)
            // Accepted after all (the check fails): the session is ours to close.
            (result as? ConnectResult.Connected)?.session?.close()
            val reason = (result as? ConnectResult.Failed)?.reason
            steps.check("unpaired key", reason?.name == "UNAUTHORIZED", "at $endpoint: $result")
        }

        private suspend fun scan() {
            val range = LocalPortScan.parseRange(readOrNull(File("/proc/sys/net/ipv4/ip_local_port_range"), steps))
            // The app's own scan, as BuiltinAdb.search ends with.
            val (result, ms) = timed { loopbackScan(range).scan(emptySet()) }
            steps.check("scan", result.found.isNotEmpty(), "$range in $ms ms: $result")
        }

        private suspend fun launch() {
            val config = LaunchConfig()
            val helper = HelperAutoLaunch.helperOf(HelperClient.of(context))
            val command = HelperLaunchCommand.of(context.applicationInfo.sourceDir, context.packageName)
            steps.note("command $command")
            val (outcome, ms) = timed { HelperLauncher(adb, helper, command, config.handoffTimeoutMs).launch() }
            steps.check("launch", outcome is LaunchOutcome.Launched, "in $ms ms: $outcome")
        }
    }

    /** A key store in memory, for keys the device must not know. */
    private class MemoryKeyStore : AdbKeyStore {
        private var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes

        override fun write(pkcs8: ByteArray) {
            bytes = pkcs8
        }
    }
}

private fun pairingCodeOrNull(
    text: String,
    steps: SelfCheckSteps,
): PairingCode? =
    try {
        PairingCode(text)
    } catch (malformed: IllegalArgumentException) {
        steps.note("code: ${malformed.message}")
        null
    }

private fun readOrNull(
    file: File,
    steps: SelfCheckSteps,
): String? =
    try {
        file.readText()
    } catch (unreadable: IOException) {
        steps.note("cannot read $file: $unreadable")
        null
    }

private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
    val start = SystemClock.elapsedRealtime()
    val value = block()
    return value to SystemClock.elapsedRealtime() - start
}
