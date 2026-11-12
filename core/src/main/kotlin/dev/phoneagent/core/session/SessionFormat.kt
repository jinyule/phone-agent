package dev.phoneagent.core.session

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Format constants of `session.jsonl`. */
public object SessionFormat {
    /** Version written by this build. Bump it together with a [SessionMigration] from the previous one. */
    public const val CURRENT_VERSION: Int = 1

    /** Value of [SessionHeader.format]. */
    public const val FORMAT_ID: String = "phoneagent.session"
}

/**
 * Line 1 of every `session.jsonl`. Its layout is frozen across versions: migrations rewrite
 * records, never the header (only [version] tells them apart).
 *
 * @property format always [SessionFormat.FORMAT_ID].
 * @property version format version of the records below, >= 1.
 * @property sessionId the session.
 * @property taskId the task it records (one task per session in M1).
 * @property createdAt epoch milliseconds.
 */
@Serializable
public data class SessionHeader(
    val format: String = SessionFormat.FORMAT_ID,
    val version: Int,
    val sessionId: SessionId,
    val taskId: TaskId,
    val createdAt: Long,
) {
    init {
        require(format == SessionFormat.FORMAT_ID) { "not a session log (format '$format')" }
        require(version >= 1) { "format version must be >= 1, got $version" }
    }
}

/**
 * Upgrades one record from [fromVersion] to [fromVersion] + 1 (phase1 §3.9 rule 4). Works on the
 * raw envelope JSON so it does not depend on today's classes. Must be pure and keep `seq`.
 * Committed fixtures of old versions are never rewritten; they are read through migrations.
 */
public interface SessionMigration {
    /** The version this migration reads. */
    public val fromVersion: Int

    /** The record in version [fromVersion] + 1. */
    public fun migrate(envelope: JsonObject): JsonObject
}

/**
 * Result of reading a whole log.
 *
 * @property header the header (with the version the file was written in).
 * @property records known records in seq order, migrated to the current version.
 * @property skipped ignorable records of unknown type that were left out.
 * @property truncatedTail a torn last line that was ignored, or `null` when the file ended cleanly.
 */
public data class SessionLog(
    val header: SessionHeader,
    val records: List<SessionRecord>,
    val skipped: List<SkippedRecord> = emptyList(),
    val truncatedTail: TruncatedTail? = null,
) {
    /** Seq of the last record (known or skipped), 0 when there is none. */
    public val lastSeq: Long get() = maxOf(records.lastOrNull()?.seq ?: 0, skipped.lastOrNull()?.seq ?: 0)
}

/** An ignorable record of unknown [type] at [seq]. */
public data class SkippedRecord(
    /** Its seq. */
    val seq: Long,
    /** Its type. */
    val type: String,
)

/**
 * The bytes after the last newline: an append the process did not finish (killed mid-write).
 * Such an append never returned, so no caller relied on it.
 *
 * @property line the 1-based line number it would have had.
 * @property bytes how many bytes were dropped.
 */
public data class TruncatedTail(
    val line: Int,
    val bytes: Int,
)
