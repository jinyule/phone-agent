package dev.phoneagent.core.session

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Failure: after any [SessionWriteException] or [SessionFlushException], or any other exception
 * from the storage while writing, the store is failed and every later [append] / [flush] throws
 * [SessionWriteException]; reopen with [open], which repairs a torn last line. Arguments that
 * break a rule ([append]'s [IllegalArgumentException]) are rejected before anything is written
 * and do not fail the store. One store per session at a time; methods are safe to call from any
 * coroutine and are serialized internally.
 *
 * Cancellation: an [append] or [flush] that is cancelled while it waits for the store, or that
 * is called from an already cancelled coroutine, writes nothing and throws
 * `CancellationException`. Once the write itself has started it runs to completion and the
 * record is counted, so the file never holds a record the store did not count; the caller may
 * still see `CancellationException` afterwards (the coroutine was cancelled meanwhile). Cleanup
 * code therefore decides from [records] or [lastSeq] whether a record was written, never from
 * the exception, and writes after cancellation (stop handling) inside
 * `withContext(NonCancellable)`.
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
    private val invariants = SessionInvariants.after(initial)
    private var failure: String? = null

    /** Seq of the last record in the log, including skipped ignorable records; 1 (`task/start`) after [create]. */
    public val lastSeq: Long get() = lastSeqValue

    /** Every known record, loaded and appended, in seq order. A copy; safe to keep. */
    public suspend fun records(): List<SessionRecord> = mutex.withLock { recordList.toList() }

    /**
     * Appends [event] as the next record, stamped with the clock. [sources] and a
     * [SurfaceOp.Replace] range must point to earlier records, and the record must keep the
     * rules of [SessionInvariants] (else [IllegalArgumentException], nothing written). Throws
     * [SessionWriteException] when the store is failed or the write fails. See the class
     * comment for cancellation.
     */
    public suspend fun append(
        event: SessionEvent,
        surfaceOp: SurfaceOp = SurfaceOp.Append,
        sources: List<Long> = emptyList(),
    ): SessionRecord =
        locked {
            val record = SessionRecord(lastSeqValue + 1, clock.epochMillis(), event, surfaceOp, sources)
            invariants.violation(record)?.let { throw IllegalArgumentException("seq ${record.seq}: $it") }
            val line = (codec.encode(record) + "\n").encodeToByteArray()
            withContext(NonCancellable) {
                writing("append of seq ${record.seq}", ::SessionWriteException) { appender.append(line) }
                lastSeqValue = record.seq
                recordList += record
                invariants.accept(record)
            }
            record
        }

    /** Forces every appended record to the device. Throws [SessionFlushException] on failure. */
    public suspend fun flush(): Unit =
        locked {
            withContext(NonCancellable) {
                writing("flush after seq $lastSeqValue", ::SessionFlushException) { appender.sync() }
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

    /**
     * Runs [block] holding the lock, if the store is usable. Waiting for the lock is cancellable,
     * and a caller cancelled by the time it holds the lock does not run [block].
     */
    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            failure?.let { throw SessionWriteException("session ${header.sessionId} is not writable: $it") }
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs one storage write. Any exception fails the store and propagates: an [IOException] wrapped
     * by [wrap], anything else (a storage bug, cancellation) unchanged.
     */
    private suspend fun writing(
        what: String,
        wrap: (String, IOException) -> SessionLogException,
        write: suspend () -> Unit,
    ) {
        var done = false
        try {
            write()
            done = true
        } catch (io: IOException) {
            failure = "$what failed: ${io.message}"
            throw wrap("session ${header.sessionId}: $failure", io)
        } finally {
            if (!done && failure == null) failure = "$what did not complete"
        }
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
         * Starts a new session log in [files], in the current format version: the header and
         * [start] as record 1, in one atomic and durable write, so every log names its task
         * settings from the start. Acquiring the appender is the last step, so no later failure
         * can leak it. Throws [SessionWriteException] if a log already exists there or the write
         * fails.
         */
        public suspend fun create(
            files: SessionFiles,
            sessionId: SessionId,
            taskId: TaskId,
            start: TaskStarted,
            clock: Clock,
        ): SessionStore {
            val codec = SessionCodec()
            val (storage, path) = files.storage to files.log
            val now = clock.epochMillis()
            val header =
                SessionHeader(version = codec.currentVersion, sessionId = sessionId, taskId = taskId, createdAt = now)
            val first = SessionRecord(1, now, start)
            val lines = (codec.encodeHeader(header) + "\n" + codec.encode(first) + "\n").encodeToByteArray()
            if (writing(path, "create") { storage.exists(path) }) {
                throw SessionWriteException("session log $path already exists")
            }
            writing(path, "create") { storage.writeAtomically(path, lines) }
            val appender = writing(path, "create") { storage.openAppender(path) }
            return SessionStore(codec, clock, header, appender, listOf(first), 1)
        }

        /**
         * Reopens the log in [files] for appending. A torn last line is removed from the file
         * (atomic rewrite of the valid prefix) and reported in [Opened.log]. Acquiring the appender
         * is the last step. Throws what [read]
         * throws, [UnsupportedSessionVersionException] for a log not in [SessionCodec.currentVersion],
         * and [SessionWriteException] when the repair or opening fails. A log that ends with a final
         * `task/stopped` (USER, LIMIT) still opens and reads, but every append is refused, so such a
         * task is never resumed.
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
