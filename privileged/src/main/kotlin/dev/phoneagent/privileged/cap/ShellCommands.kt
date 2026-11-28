/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt, `sh`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the output is bounded, and the allow-list of programs is
 * checked here instead of at the call sites.
 */
package dev.phoneagent.privileged.cap

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exit code and (bounded) output of one command; [code] is -1 when it could not run. */
internal data class CommandResult(
    val code: Int,
    val output: String,
) {
    val ok: Boolean get() = code == 0
}

/** Runs one of the [ShellCommands] programs; replaced in tests by one that records the command. */
internal fun interface CommandRunner {
    fun run(command: List<String>): CommandResult

    fun run(vararg command: String): CommandResult = run(command.toList())
}

/**
 * The fixed set of platform commands the helper runs itself, with arguments it builds.
 *
 * There is deliberately no way to run an arbitrary command (phase1-architecture §3.5, the lesson
 * from Mika's `phone_system_command`): the capabilities the setup needs are methods on
 * `IPrivilegedCore`, and each of them uses one of the programs listed in [ALLOWED] with arguments
 * this process assembles. A command is only a fallback for a hidden API that is missing.
 */
internal object ShellCommands {
    /** Programs the helper may run. */
    private val ALLOWED = setOf("am", "pm", "appops", "input")

    private const val TAG = "PhoneAgentHelper"
    private const val TIMEOUT_S = 10L
    private const val OUTPUT_MAX = 16 * 1024
    private const val READER_JOIN_MS = 1_000L

    /** [run] as a [CommandRunner]. */
    val runner: CommandRunner = CommandRunner { run(it) }

    /**
     * Runs [command] (program first). Throws [IllegalArgumentException] when the program is not in
     * [ALLOWED] — that is a programming error, not something a caller can trigger.
     */
    fun run(vararg command: String): CommandResult = run(command.toList())

    /** Runs [command] (program first); see [run]. */
    fun run(command: List<String>): CommandResult {
        val program = command.firstOrNull()
        require(program in ALLOWED) { "$program is not a command the helper may run" }
        val process =
            try {
                ProcessBuilder(command).redirectErrorStream(true).start()
            } catch (e: IOException) {
                Log.w(TAG, "cannot start ${command.joinToString(" ")}", e)
                return CommandResult(-1, "")
            }
        val output = AtomicReference("")
        // Read on another thread: a command that never finishes must not hold a Binder thread.
        val reader =
            Thread {
                output.set(
                    runCatching {
                        process.inputStream.bufferedReader().use { it.readText().take(OUTPUT_MAX) }
                    }.getOrDefault(""),
                )
            }.apply {
                isDaemon = true
                start()
            }
        return collect(process, command, reader, output)
    }

    private fun collect(
        process: Process,
        command: List<String>,
        reader: Thread,
        output: AtomicReference<String>,
    ): CommandResult {
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Log.w(TAG, "${command.joinToString(" ")} timed out after $TIMEOUT_S s")
            return CommandResult(-1, "")
        }
        reader.join(READER_JOIN_MS)
        val result = CommandResult(process.exitValue(), output.get())
        if (!result.ok) Log.w(TAG, "${command.joinToString(" ")} -> ${result.code}: ${result.output.trim()}")
        return result
    }
}
