package dev.phoneagent.core.session

import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort

/**
 * Where one session lives: `<dir>/session.jsonl` and `<dir>/blobs/` (the same layout as replay
 * fixtures, engineering-baseline §3.2).
 *
 * @property storage the storage port.
 * @property dir the session directory.
 */
public class SessionFiles(
    public val storage: StoragePort,
    public val dir: StoragePath,
) {
    /** The log file. */
    public val log: StoragePath = dir.resolve(LOG_FILE)

    /** The session's blob store. */
    public val blobs: BlobStore = BlobStore(storage, dir.resolve(BLOBS_DIR))

    /** File names. */
    public companion object {
        /** Name of the log file inside the session directory. */
        public const val LOG_FILE: String = "session.jsonl"

        /** Name of the blob directory inside the session directory. */
        public const val BLOBS_DIR: String = "blobs"
    }
}
