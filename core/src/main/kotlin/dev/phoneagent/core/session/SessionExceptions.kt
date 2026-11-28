package dev.phoneagent.core.session

import java.io.IOException

/**
 * Failures of the session log. All are [IOException]s so storage callers handle one family;
 * catch the specific subclass to react (e.g. [SessionFlushException]: do not send, do not act).
 */
public sealed class SessionLogException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A line other than a torn last line is unreadable: not UTF-8, not JSON, not a valid envelope or
 * payload, or out of sequence. The log is refused as a whole; nothing is skipped silently.
 *
 * @property line 1-based line number (line 1 is the header).
 */
public class SessionCorruptedException(
    public val line: Int,
    detail: String,
    cause: Throwable? = null,
) : SessionLogException("session log line $line: $detail", cause)

/**
 * A record has a type this build does not know and is not marked ignorable, so the model view
 * cannot be derived safely (written by a newer app version).
 *
 * @property seq the record.
 * @property type its type.
 */
public class UnknownEventTypeException(
    public val seq: Long,
    public val type: String,
) : SessionLogException("unknown event type '$type' at seq $seq (not ignorable)")

/**
 * The log's format version cannot be handled here: newer than this build, older than the oldest
 * migration, or (for appending) not the current version.
 *
 * @property version the log's version.
 * @property supported versions accepted for this operation.
 */
public class UnsupportedSessionVersionException(
    public val version: Int,
    public val supported: IntRange,
) : SessionLogException("session format version $version not supported (supported: $supported)")

/** Appending a record failed; the store is failed and refuses further writes. Reopen to repair. */
public class SessionWriteException(
    message: String,
    cause: Throwable? = null,
) : SessionLogException(message, cause)

/**
 * Forcing the log to the device failed (phase1 §3.9 rule 3): the caller must not send the model
 * request or run the write action it was about to. The store is failed; reopen to continue.
 */
public class SessionFlushException(
    message: String,
    cause: Throwable? = null,
) : SessionLogException(message, cause)
