package dev.phoneagent.core.session

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Append-only writer of one session's `session.jsonl` (phase1 §3.9, D13); see [SessionFiles]
 * for the layout.
 *
 * Durability: [append] hands the line to the OS (it survives a process kill); [flush] forces it
 * to the device. Callers flush before every model request and before every write action, and
 * do neither when [flush] throws [SessionFlushException].
 *
 * Failure: after any [SessionWriteException] or [SessionFlushException] the store is failed and
 * every later [append] / [flush] throws [SessionWriteException]; reopen with [open], which repairs a
 * torn last line. One store per session at a time; methods are safe to call from any coroutine
 * and are serialized internally. Once started, an append or flush runs to completion even if
 * the caller is cancelled, so the file never holds a record the store did not count.
 */
public class SessionStore private constructor(
    private val codec: SessionCodec,
    private val clock: Clock,
    /** The session's header. */
    public val header: SessionHeader,
    private val appender: StorageAppender,
    initial: List<SessionRecord>,
    private var lastSeqValue: Long,
) {
    private val mutex = Mutex()
    private val recordList = initial.toMutableList()
    private var failure: String? = null

    /** Seq of the last record in the log (0 when empty), including skipped ignorable records. */
    public val lastSeq: Long get() = lastSeqValue

    /** Every known record, loaded and appended, in seq order. A copy; safe to keep. */
    public suspend fun records(): List<SessionRecord> = mutex.withLock { recordList.toList() }

    /**
     * Appends [event] as the next record, stamped with the clock. [sources] and a
     * [SurfaceOp.Replace] range must point to earlier records (else [IllegalArgumentException],
     * nothing written). Throws [SessionWriteException] when the store is failed or the write fails.
     */
    public suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp = SurfaceOp.Append,
        sources: List<Long> = emptyList(),
    ): SessionRecord =
        withContext(NonCancellable) {
            mutex.withLock {
                checkUsable()
                val record = SessionRecord(lastSeqValue + 1, clock.epochMillis(), event, surfaceOp, sources)
                val line = (codec.encode(record) + "\n").encodeToByteArray()
                try {
                    appender.append(line)
                } catch (io: IOException) {
                    failure = "append of seq ${record.seq} failed: ${io.message}"
                    throw SessionWriteException("session ${header.sessionId}: $failure", io)
                }
                lastSeqValue = record.seq
                recordList += record
                record
            }
        }

    /** Forces every appended record to the device. Throws [SessionFlushException] on failure. */
    public suspend fun flush(): Unit =
        withContext(NonCancellable) {
            mutex.withLock {
                checkUsable()
                try {
                    appender.sync()
                } catch (io: IOException) {
                    failure = "flush after seq $lastSeqValue failed: ${io.message}"
                    throw SessionFlushException("session ${header.sessionId}: $failure", io)
                }
            }
        }

    /** Closes the file without flushing. Later appends throw [SessionWriteException]. */
    public suspend fun close(): Unit =
        withContext(NonCancellable) {
            mutex.withLock {
                if (failure == null) failure = "store closed"
                appender.close()
            }
        }

    private fun checkUsable() {
        failure?.let { throw SessionWriteException("session ${header.sessionId} is not writable: $it") }
    }

    /**
     * A reopened session: the store to continue in and what was read.
     *
     * @property store appends after the last record.
     * @property log the records as read; [SessionLog.truncatedTail] tells whether a torn line was dropped.
     */
    public class Opened(
        public val store: SessionStore,
        public val log: SessionLog,
    )

    /** Factories. */
    public companion object {
        /**
         * Starts a new session log in [files], writing the header atomically and durably.
         * Throws [SessionWriteException] if a log already exists there or the write fails.
         */
        public suspend fun create(
            files: SessionFiles,
            sessionId: SessionId,
            taskId: TaskId,
            clock: Clock,
            codec: SessionCodec = SessionCodec(),
        ): SessionStore {
            val (storage, path) = files.storage to files.log
            val header =
                SessionHeader(
                    version = codec.currentVersion,
                    sessionId = sessionId,
                    taskId = taskId,
                    createdAt = clock.epochMillis(),
                )
            val headerLine = (codec.encodeHeader(header) + "\n").encodeToByteArray()
            if (writing(path, "create") { storage.exists(path) }) {
                throw SessionWriteException("session log $path already exists")
            }
            writing(path, "create") { storage.writeAtomically(path, headerLine) }
            val appender = writing(path, "create") { storage.openAppender(path) }
            return SessionStore(codec, clock, header, appender, emptyList(), 0)
        }

        /**
         * Reopens the log in [files] for appending. A torn last line is removed from the file
         * (atomic rewrite of the valid prefix) and reported in [Opened.log]. Throws what [read]
         * throws, [UnsupportedSessionVersionException] for a log not in [SessionCodec.currentVersion],
         * and [SessionWriteException] when the repair or opening fails.
         */
        public suspend fun open(
            files: SessionFiles,
            clock: Clock,
            codec: SessionCodec = SessionCodec(),
        ): Opened {
            val (storage, path) = files.storage to files.log
            val bytes = readBytes(storage, path)
            val log = codec.read(bytes)
            if (log.header.version != codec.currentVersion) {
                throw UnsupportedSessionVersionException(log.header.version, codec.currentVersion..codec.currentVersion)
            }
            log.truncatedTail?.let { tail ->
                writing(path, "repair") { storage.writeAtomically(path, bytes.copyOf(bytes.size - tail.bytes)) }
            }
            val appender = writing(path, "reopen") { storage.openAppender(path) }
            return Opened(SessionStore(codec, clock, log.header, appender, log.records, log.lastSeq), log)
        }

        /**
         * Reads the log in [files] without modifying it; older versions are migrated in memory.
         * Throws [SessionCorruptedException], [UnknownEventTypeException],
         * [UnsupportedSessionVersionException], or [IOException] when the file is missing or unreadable.
         */
        public suspend fun read(
            files: SessionFiles,
            codec: SessionCodec = SessionCodec(),
        ): SessionLog = codec.read(readBytes(files.storage, files.log))

        private suspend fun readBytes(
            storage: StoragePort,
            path: StoragePath,
        ): ByteArray = storage.read(path) ?: throw FileNotFoundException("no session log at $path")

        /** Runs one storage call, reporting its [IOException] as a [SessionWriteException]. */
        private inline fun <T> writing(
            path: StoragePath,
            what: String,
            call: () -> T,
        ): T =
            try {
                call()
            } catch (io: IOException) {
                throw SessionWriteException("cannot $what $path: ${io.message}", io)
            }
    }
}
