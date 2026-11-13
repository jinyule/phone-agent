/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/setup/BuiltinShellManager.kt, launchHelper).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: English documentation; typed outcomes; the nonce is issued
 * after connecting and only when no helper is connected; the command must exit with status 0 (or
 * none) before the handoff is awaited.
 */
package dev.phoneagent.app.adb

import dev.phoneagent.app.helper.HelperState
import dev.phoneagent.core.adb.AdbCommand
import dev.phoneagent.core.adb.CommandFailure
import dev.phoneagent.core.adb.CommandResult
import dev.phoneagent.core.adb.ConnectFailure
import dev.phoneagent.core.adb.PortSearchResult
import dev.phoneagent.core.adb.PortSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How one launch through the built-in adb client ended; logged as its `toString`. Nothing here holds
 * the command's output ([CommandFailed]), so `toString` never contains the nonce.
 */
internal sealed interface LaunchOutcome {
    /** A helper handed off after the launch command ran; [pid] is the connected helper's. */
    data class Launched(
        val pid: Int,
        val source: PortSource,
    ) : LaunchOutcome

    /** A helper was already connected when the launch was about to start: nothing was done. */
    data object AlreadyConnected : LaunchOutcome

    /** No pairing on record, for [reason]: the user must pair. */
    data class NotPaired(
        val reason: NotPairedReason,
    ) : LaunchOutcome

    /** Wireless debugging is off; nothing was tried. */
    data object WirelessDebuggingOff : LaunchOutcome

    /**
     * adbd refused our key twice on the same endpoint: the device forgot the pairing, or something
     * on this device posing as adbd answered first. The pairing is kept and the next request tries
     * again; when this persists, the user has to pair again.
     */
    data object Unauthorized : LaunchOutcome

    /** No adbd answered: [search] says what was tried. */
    data class NoConnection(
        val search: PortSearchResult.Failed,
    ) : LaunchOutcome

    /** The app could not issue a launch nonce ([reason], e.g. the boot count is unknown). */
    data class NoNonce(
        val reason: String,
    ) : LaunchOutcome

    /**
     * The launch command did not finish with exit status 0: [exitCode] when it ran to the end,
     * [failure] when it did not. Only this summary is kept, and [outputChars], the length of what
     * the command printed (stdout and stderr) or of the session's failure detail: either may echo
     * the command's stdin, the nonce, so neither is kept, logged or part of `toString`.
     */
    data class CommandFailed(
        val exitCode: Int?,
        val failure: CommandFailure?,
        val outputChars: Int,
    ) : LaunchOutcome {
        companion object {
            /** The summary of [result]. */
            fun of(result: CommandResult): CommandFailed =
                when (result) {
                    is CommandResult.Completed -> {
                        CommandFailed(result.exitCode, null, result.stdout.length + result.stderr.length)
                    }

                    is CommandResult.Failed -> {
                        CommandFailed(null, result.reason, result.detail.length)
                    }
                }
        }
    }

    /** The command ran, but no helper handed off within the wait. */
    data class NoHandoff(
        val waitedMs: Long,
    ) : LaunchOutcome

    /**
     * Automatic launches only: nothing was tried, since each of the last [exits] helpers died within
     * [LaunchConfig.earlyExitWindowMs] of its handoff. No helper is launched by itself until a
     * pairing succeeds again, a helper outlives that window, or the process restarts.
     */
    data class TooManyEarlyExits(
        val exits: Int,
    ) : LaunchOutcome
}

/**
 * Launches the shell-uid helper once through the built-in adb client: connect, issue a launch
 * nonce, run [command] with the nonce on its stdin (never on the command line, never logged), and
 * wait up to [handoffTimeoutMs] for a helper to hand off. The session is closed either way. A
 * helper that hands off meanwhile, launched by us or not, ends the wait.
 */
internal class HelperLauncher(
    private val adb: Adb,
    private val helper: Helper,
    private val command: AdbCommand,
    private val handoffTimeoutMs: Long,
) {
    /** What the launcher needs of [BuiltinAdb]. */
    interface Adb {
        /** Why no pairing is on record, or `null` when one is. */
        val notPaired: NotPairedReason?

        fun wirelessDebuggingOn(): Boolean?

        /** Connects to this device's adbd (with the retry and the pairing bookkeeping of [BuiltinAdb.search]). */
        suspend fun connect(): Connection
    }

    /** What [Adb.connect] gave. */
    sealed interface Connection {
        /** A connected adb session; closing it ends the session. */
        interface Open :
            Connection,
            AutoCloseable {
            /** Where the port came from. */
            val source: PortSource

            suspend fun run(
                command: AdbCommand,
                stdin: ByteArray,
            ): CommandResult

            /** How the launch on this session ended; called once, before [close]. */
            fun ended(outcome: LaunchOutcome)
        }

        /** No adbd took us: [search] says what was tried. */
        data class Failed(
            val search: PortSearchResult.Failed,
        ) : Connection
    }

    /** What the launcher needs of the app's helper client. */
    interface Helper {
        val state: HelperState

        fun addListener(listener: (HelperState) -> Unit): AutoCloseable

        /** A one-time launch nonce; [IllegalStateException] with the reason when none can be issued. */
        fun issueLaunchNonce(): String
    }

    init {
        require(handoffTimeoutMs > 0) { "handoffTimeoutMs must be > 0, got $handoffTimeoutMs" }
    }

    suspend fun launch(): LaunchOutcome {
        val notPaired = adb.notPaired
        val refusal =
            when {
                helper.state is HelperState.Connected -> LaunchOutcome.AlreadyConnected
                notPaired != null -> LaunchOutcome.NotPaired(notPaired)
                adb.wirelessDebuggingOn() == false -> LaunchOutcome.WirelessDebuggingOff
                else -> null
            }
        if (refusal != null) return refusal
        return when (val connection = adb.connect()) {
            is Connection.Open -> connection.use { run(it).also(it::ended) }
            is Connection.Failed -> failed(connection.search)
        }
    }

    /** A failed search; NotPaired when connecting loaded a new identity, which no device knows. */
    private fun failed(search: PortSearchResult.Failed): LaunchOutcome {
        val notPaired = adb.notPaired
        return when {
            notPaired != null -> LaunchOutcome.NotPaired(notPaired)
            search.lastFailure?.reason == ConnectFailure.UNAUTHORIZED -> LaunchOutcome.Unauthorized
            else -> LaunchOutcome.NoConnection(search)
        }
    }

    private suspend fun run(session: Connection.Open): LaunchOutcome {
        val handoff = CompletableDeferred<Int>()
        val listening = helper.addListener { state -> if (state is HelperState.Connected) handoff.complete(state.pid) }
        return listening.use {
            // Connected since the check in launch: a second helper would only replace it.
            if (helper.state is HelperState.Connected) {
                LaunchOutcome.AlreadyConnected
            } else {
                runWithNonce(
                    session,
                    handoff,
                )
            }
        }
    }

    private suspend fun runWithNonce(
        session: Connection.Open,
        handoff: CompletableDeferred<Int>,
    ): LaunchOutcome {
        val nonce =
            try {
                helper.issueLaunchNonce()
            } catch (refused: IllegalStateException) {
                // HandoffNonces refuses without a boot count; its reason names no nonce.
                return LaunchOutcome.NoNonce(refused.message.orEmpty())
            }
        val result = session.run(command, HelperLaunchCommand.stdin(nonce))
        val ran = result is CommandResult.Completed && (result.exitCode ?: 0) == 0
        return if (!ran) {
            LaunchOutcome.CommandFailed.of(result)
        } else {
            withTimeoutOrNull(handoffTimeoutMs) { handoff.await() }?.let { pid ->
                LaunchOutcome.Launched(pid, session.source)
            }
                ?: LaunchOutcome.NoHandoff(handoffTimeoutMs)
        }
    }
}
