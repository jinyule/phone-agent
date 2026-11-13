package dev.phoneagent.app.adb

import dev.phoneagent.app.helper.FakeHelper
import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.app.helper.HelperState.LaunchReason
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.ConnectResult
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource
import java.util.concurrent.CopyOnWriteArrayList

/** The helper client as [HelperLauncher] sees it; [set] delivers a change to the listeners. */
internal class FakeHelperPort(
    initial: HelperState = HelperState.NotConnected,
) : HelperLauncher.Helper {
    override var state: HelperState = initial
        private set
    val listeners = CopyOnWriteArrayList<(HelperState) -> Unit>()
    var nonces = 0
    var nonceRefusal: String? = null

    /** Every nonce [issueLaunchNonce] gave, in order. */
    val issued = mutableListOf<String>()

    fun set(next: HelperState) {
        state = next
        listeners.forEach { it(next) }
    }

    override fun addListener(listener: (HelperState) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun issueLaunchNonce(): String {
        nonceRefusal?.let { throw IllegalStateException(it) }
        nonces++
        return (NONCE.take(NONCE.length - 1) + nonces).also { issued += it }
    }

    companion object {
        const val NONCE = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

        fun connected(pid: Int = FakeHelper.PID): HelperState.Connected = HelperState.Connected(FakeHelper(), pid)

        fun died(pid: Int = FakeHelper.PID): HelperState.NeedsLaunch =
            HelperState.NeedsLaunch(LaunchReason.HelperDied, pid)
    }
}

/** An adb session that records what ran on it. */
internal class FakeSession(
    override val source: PortSource = PortSource.MDNS,
    var result: CommandResult = CommandResult.Completed(0, "", ""),
    /** Runs inside [run], as adbd runs the command. */
    var onRun: () -> Unit = {},
) : HelperLauncher.Connection.Open {
    val runs = mutableListOf<Pair<AdbCommand, ByteArray>>()
    var closed = false

    /** What [ended] was told, in order. */
    val ended = mutableListOf<LaunchOutcome>()

    override suspend fun run(
        command: AdbCommand,
        stdin: ByteArray,
    ): CommandResult {
        check(!closed) { "run on a closed session" }
        runs += command to stdin
        onRun()
        return result
    }

    override fun ended(outcome: LaunchOutcome) {
        check(!closed) { "ended after close" }
        ended += outcome
    }

    override fun close() {
        closed = true
    }
}

/** The built-in client as [HelperLauncher] sees it. */
internal class FakeAdb(
    var connection: () -> HelperLauncher.Connection = { FakeSession() },
) : HelperLauncher.Adb {
    override var isPaired = true
    var wireless: Boolean? = true
    var connects = 0

    override fun wirelessDebuggingOn(): Boolean? = wireless

    override suspend fun connect(): HelperLauncher.Connection {
        connects++
        return connection()
    }

    companion object {
        fun failed(reason: ConnectFailure?): HelperLauncher.Connection.Failed =
            HelperLauncher.Connection.Failed(
                PortSearchResult.Failed(reason?.let { ConnectResult.Failed(it, "fake") }, emptyList(), null),
            )
    }
}
