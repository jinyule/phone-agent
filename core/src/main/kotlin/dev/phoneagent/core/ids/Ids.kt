package dev.phoneagent.core.ids

import kotlinx.serialization.Serializable

/*
 * Identifiers that cross a boundary (session log, IPC, UI, model wire). Each one is an opaque,
 * non-blank string serialized as a bare JSON string. Construction validates the shape, so a
 * malformed value read from a file or the network fails where it enters (IllegalArgumentException).
 * Ids carry no meaning beyond equality; never parse them.
 */

/** One user task (one `TaskRuntime`); every tool call carries it. */
@Serializable
@JvmInline
public value class TaskId(
    /** Opaque, non-blank. */
    public val value: String,
) {
    init {
        requireId(value, "TaskId")
    }

    override fun toString(): String = value
}

/** One session log (one `session.jsonl` directory). In M1 a session belongs to exactly one task. */
@Serializable
@JvmInline
public value class SessionId(
    /** Opaque, non-blank, and safe as a single path segment (letters, digits, `-`, `_`, `.`). */
    public val value: String,
) {
    init {
        requireId(value, "SessionId")
        require(SESSION_SEGMENT.matches(value) && value != "." && value != "..") {
            "SessionId must be a single path segment of [A-Za-z0-9._-], got '$value'"
        }
    }

    override fun toString(): String = value
}

/** A tool call as named by the model (provider-assigned or synthesized by the adapter). */
@Serializable
@JvmInline
public value class ToolCallId(
    /** Opaque, non-blank; unique within a session. */
    public val value: String,
) {
    init {
        requireId(value, "ToolCallId")
    }

    override fun toString(): String = value
}

/** One screen observation, e.g. `s12`; actions name the snapshot they were planned on. */
@Serializable
@JvmInline
public value class SnapshotId(
    /** Opaque, non-blank; unique within a task. */
    public val value: String,
) {
    init {
        requireId(value, "SnapshotId")
    }

    override fun toString(): String = value
}

/** A pending question to the user (confirmation, `ask_user`, handoff); answered via `TaskRuntime.answer`. */
@Serializable
@JvmInline
public value class PromptId(
    /** Opaque, non-blank; unique within a task. */
    public val value: String,
) {
    init {
        requireId(value, "PromptId")
    }

    override fun toString(): String = value
}

/**
 * Content address of a blob in a session's `BlobStore`: the lowercase hex SHA-256 of its bytes.
 * The mime type and pixel size live in the event that references the blob, not here.
 */
@Serializable
@JvmInline
public value class BlobRef(
    /** 64 lowercase hex characters. */
    public val value: String,
) {
    init {
        require(SHA256_HEX.matches(value)) { "BlobRef must be 64 lowercase hex chars, got '$value'" }
    }

    override fun toString(): String = value
}

/** A configured model provider (a `ProviderProfile`), e.g. `deepseek` or `anthropic`. */
@Serializable
@JvmInline
public value class ProviderId(
    /** Opaque, non-blank; stable for the lifetime of the profile. */
    public val value: String,
) {
    init {
        requireId(value, "ProviderId")
    }

    override fun toString(): String = value
}

/** A model as the provider names it on the wire, e.g. `claude-opus-5-5`. */
@Serializable
@JvmInline
public value class ModelId(
    /** The provider's model name, verbatim. */
    public val value: String,
) {
    init {
        requireId(value, "ModelId")
    }

    override fun toString(): String = value
}

private fun requireId(
    value: String,
    kind: String,
) {
    require(value.isNotBlank()) { "$kind must not be blank" }
    require(value.length <= MAX_ID_LENGTH) { "$kind longer than $MAX_ID_LENGTH chars" }
    require(value.none { it.isISOControl() }) { "$kind must not contain control characters" }
}

private const val MAX_ID_LENGTH = 256
private val SESSION_SEGMENT = Regex("[A-Za-z0-9._-]{1,128}")
private val SHA256_HEX = Regex("[0-9a-f]{64}")
