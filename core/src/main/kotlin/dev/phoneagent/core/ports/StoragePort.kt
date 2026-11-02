package dev.phoneagent.core.ports

import java.io.IOException

/**
 * Byte storage rooted at an app-private directory (phase1 §3.9 rule 6). Paths are relative
 * [StoragePath]s; implementations map them below their root and never outside it.
 *
 * Every method may throw [IOException]; nothing else is part of the contract. Methods are
 * main-safe: implementations move blocking I/O off the caller's thread.
 */
public interface StoragePort {
    /** The whole content of [path], or `null` when it does not exist. */
    public suspend fun read(path: StoragePath): ByteArray?

    /** Whether a file exists at [path]. */
    public suspend fun exists(path: StoragePath): Boolean

    /**
     * Replaces [path] with [bytes] atomically: readers see either the old or the new content,
     * never a mix. The content is durable (fsync) when this returns. Missing parent directories are
     * created, and durable too. When it throws, either content may be in place: a failure after
     * the new content became visible only leaves its durability unknown, so a caller never
     * concludes from the exception that the old content is still there.
     */
    public suspend fun writeAtomically(
        path: StoragePath,
        bytes: ByteArray,
    )

    /**
     * Opens [path] for appending, creating it and its parents if absent (durably, like
     * [writeAtomically]). At most one appender per path may be open at a time; the caller closes
     * it. When the caller is cancelled before it receives the handle, the implementation closes
     * the handle: nobody else can.
     */
    public suspend fun openAppender(path: StoragePath): StorageAppender

    /** Files directly inside [directory], sorted by name; empty when the directory does not exist. */
    public suspend fun list(directory: StoragePath): List<StoragePath>

    /** Deletes the file at [path]; returns whether it existed. */
    public suspend fun delete(path: StoragePath): Boolean
}

/**
 * Append-only handle from [StoragePort.openAppender]. Not thread-safe: one writer at a time.
 * After any [IOException] the handle's state is unknown and it must not be used again except to [close].
 */
public interface StorageAppender {
    /**
     * Appends [bytes] at the end of the file, all or nothing from the caller's view. On return the
     * bytes survive the death of this process but not necessarily a power loss; see [sync].
     */
    public suspend fun append(bytes: ByteArray)

    /** Forces everything appended so far to the storage device (fsync). */
    public suspend fun sync()

    /** Releases the handle. Idempotent. Does not imply [sync]. */
    public suspend fun close()
}

/**
 * A relative path below a [StoragePort] root: `/`-separated segments of `[A-Za-z0-9._-]`, never
 * `.` or `..`, no leading or trailing `/`. Construction rejects anything else, so a path can
 * never escape the root.
 */
@JvmInline
public value class StoragePath(
    /** The path, e.g. `sessions/s-1/session.jsonl`. */
    public val value: String,
) {
    init {
        val segments = value.split('/')
        require(value.isNotEmpty() && segments.all { SEGMENT.matches(it) && it != "." && it != ".." }) {
            "Invalid storage path '$value': expected relative segments of [A-Za-z0-9._-]"
        }
    }

    /** The last segment. */
    public val name: String get() = value.substringAfterLast('/')

    /** The containing directory, or `null` for a top-level path. */
    public val parent: StoragePath? get() = value.substringBeforeLast('/', "").ifEmpty { null }?.let(::StoragePath)

    /** The path of [child] (one or more segments) below this one. */
    public fun resolve(child: String): StoragePath = StoragePath("$value/$child")

    override fun toString(): String = value
}

private val SEGMENT = Regex("[A-Za-z0-9._-]{1,255}")
