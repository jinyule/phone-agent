package dev.phoneagent.core.adb

/**
 * A command line for the device's `sh`, built by app code for one fixed purpose (starting the
 * helper, a self-check). It is never taken from the model, a tool or screen content: this package
 * is outside the tool layer by design (AGENTS.md §3.4, phase1 §2.1), and arguments that come from
 * data must go through [quote]. Must not be blank or contain NUL (which ends adb service names).
 */
@JvmInline
public value class AdbCommand(
    /** The command line, run by `sh -c`. */
    public val text: String,
) {
    init {
        require(text.isNotBlank()) { "an adb command must not be blank" }
        require('\u0000' !in text) { "an adb command must not contain NUL" }
    }

    override fun toString(): String = "AdbCommand($text)"

    /** Building command lines. */
    public companion object {
        /** [argument] as one single-quoted `sh` word, whatever it contains (`'` becomes `'\''`). */
        public fun quote(argument: String): String = "'" + argument.replace("'", "'\\''") + "'"
    }
}

/** Why a command did not finish. */
public enum class CommandFailure {
    /** adbd refused to open the service; the session is still usable. */
    REFUSED,

    /** The session was closed, or the connection or stream ended before the command finished. */
    CLOSED,

    /** adbd broke the adb or shell protocol. */
    PROTOCOL,

    /** stdout and stderr together exceeded [AdbConfig.maxOutputBytes]. */
    OUTPUT_TOO_LARGE,

    /** The command did not finish within [AdbConfig.commandTimeoutMs]. */
    TIMEOUT,

    /**
     * The service name (`shell,v2,raw:` or `exec:`, the command, a NUL) is longer in UTF-8 than
     * adbd takes in one packet; nothing was sent and the session is still usable.
     */
    TOO_LONG,
}

/** Outcome of [AdbSession.run]. */
public sealed interface CommandResult {
    /**
     * The command ran to the end. Output is decoded as UTF-8 (malformed bytes become U+FFFD).
     * With `exec:` (no shell protocol), [stdout] holds stdout and stderr together and [exitCode] is `null`.
     */
    public data class Completed(
        /** The exit status (0..255), or `null` when adbd lacks the shell protocol. */
        val exitCode: Int?,
        /** Standard output. */
        val stdout: String,
        /** Standard error (empty with `exec:`). */
        val stderr: String,
    ) : CommandResult

    /** The command did not finish; [AdbSession] says when the session is closed as a consequence. */
    public data class Failed(
        /** The class of failure. */
        val reason: CommandFailure,
        /** Diagnostic text for logs. */
        val detail: String,
    ) : CommandResult
}
