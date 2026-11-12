package dev.phoneagent.core.session

import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Every test is bounded: a broken lock or a non-cancellable wait (e.g. a mutation of [SessionStore])
 * must fail the test, not hang the build. The JUnit timeout runs the test on its own thread, so it
 * also ends waits inside `NonCancellable` that the `runTest` timeout cannot cancel.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SessionStoreTest {
    private val storage = InMemoryStoragePort()
    private val files = SessionFiles(storage, StoragePath("sessions/session-1"))
    private val logPath = "sessions/session-1/session.jsonl"
    private var now = 100L
    private val clock = Clock { now++ }

    private suspend fun newStore() =
        SessionStore.create(files, SessionSamples.session, SessionSamples.task, SessionSamples.start, clock)

    @Test
    fun `appends get consecutive seqs and clock times and read back in order`() =
        runTest {
            val store = newStore()
            val start = SessionRecord(1, 100, SessionSamples.start)
            val first = store.append(TurnStarted(1))
            val second = store.append(StepStarted(1, 1), sources = listOf(2))
            store.flush()
            assertEquals(SessionRecord(2, 101, TurnStarted(1)), first)
            assertEquals(SessionRecord(3, 102, StepStarted(1, 1), sources = listOf(2)), second)
            assertEquals(3, store.lastSeq)
            assertEquals(listOf(start, first, second), store.records())
            val log = SessionStore.read(files)
            assertEquals(store.header, log.header)
            assertEquals(
                SessionHeader(
                    version = SessionFormat.CURRENT_VERSION,
                    sessionId = SessionSamples.session,
                    taskId = SessionSamples.task,
                    createdAt = 100,
                ),
                log.header,
            )
            assertEquals(listOf(start, first, second), log.records)
            assertEquals(1, storage.syncCount)
        }

    @Test
    fun `create refuses an existing log and reports storage failures as write errors`() =
        runTest {
            newStore()
            assertThrows<SessionWriteException> { newStore() }
            val failing = InMemoryStoragePort().apply { failWrite = true }
            val error =
                assertThrows<SessionWriteException> {
                    SessionStore.create(
                        SessionFiles(failing, StoragePath("s")),
                        SessionSamples.session,
                        SessionSamples.task,
                        SessionSamples.start,
                        clock,
                    )
                }
            assertTrue(error.cause is java.io.IOException)
        }

    @Test
    fun `references to later records are rejected before anything is written`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            assertThrows<IllegalArgumentException> { store.append(StepStarted(1, 1), sources = listOf(3)) }
            assertThrows<IllegalArgumentException> {
                store.append(
                    CompactionPrune(emptyList()),
                    SurfaceOp.Replace(2, 3),
                )
            }
            assertEquals(2, store.lastSeq)
            assertEquals(3, storage.text(logPath).lines().count { it.isNotEmpty() })
            assertEquals(3, store.append(StepStarted(1, 1)).seq)
        }

    @Test
    fun `a failed flush is reported as SessionFlushException and the store refuses further writes`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            storage.failSync = true
            assertThrows<SessionFlushException> { store.flush() }
            storage.failSync = false
            val append = assertThrows<SessionWriteException> { store.append(StepStarted(1, 1)) }
            assertTrue("flush after seq 2 failed" in append.message.orEmpty(), append.message)
            assertThrows<SessionWriteException> { store.flush() }
        }

    @Test
    fun `a failed append leaves a torn line that reopening drops and reports`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            storage.failAppend = true
            assertThrows<SessionWriteException> { store.append(StepStarted(1, 1)) }
            storage.failAppend = false
            assertThrows<SessionWriteException> { store.append(StepStarted(1, 1)) }

            val torn = storage.files.getValue(logPath).size
            val opened = SessionStore.open(files, clock)
            val tail = opened.log.truncatedTail
            assertEquals(4, tail?.line)
            assertEquals(torn - storage.text(logPath).length, tail?.bytes)
            assertTrue(storage.text(logPath).endsWith("\n"), "torn bytes removed from the file")
            assertEquals(listOf(SessionSamples.start, TurnStarted(1)), opened.log.records.map { it.event })

            val next = opened.store.append(StepStarted(1, 1))
            assertEquals(3, next.seq)
            opened.store.flush()
            assertEquals(listOf(1L, 2L, 3L), SessionStore.read(files).records.map { it.seq })
            assertEquals(listOf(1L, 2L, 3L), opened.store.records().map { it.seq })
        }

    @Test
    fun `reopening a clean log keeps the file and continues the sequence`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            store.close()
            assertThrows<SessionWriteException> { store.append(StepStarted(1, 1)) }
            val writesBefore = storage.writes.getValue(logPath)
            val opened = SessionStore.open(files, clock)
            assertNull(opened.log.truncatedTail)
            assertEquals(writesBefore, storage.writes.getValue(logPath), "no rewrite without a torn tail")
            assertEquals(3, opened.store.append(StepStarted(1, 1)).seq)
        }

    @Test
    fun `reopening continues after skipped ignorable records`() =
        runTest {
            newStore().append(TurnStarted(1))
            storage.files[logPath] =
                (
                    storage.text(
                        logPath,
                    ) + """{"seq":3,"time":0,"type":"future/x","ignorable":true,"data":{}}""" + "\n"
                ).encodeToByteArray()
            val opened = SessionStore.open(files, clock)
            assertEquals(listOf(SkippedRecord(3, "future/x")), opened.log.skipped)
            assertEquals(4, opened.store.append(TurnEnded(1, TurnEndReason.COMPLETED)).seq)
        }

    @Test
    fun `corruption in the middle is never repaired silently`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            store.append(StepStarted(1, 1))
            val lines = storage.text(logPath).lines().toMutableList()
            lines[2] = lines[2].dropLast(3)
            storage.files[logPath] = lines.joinToString("\n").encodeToByteArray()
            val error = assertThrows<SessionCorruptedException> { SessionStore.open(files, clock) }
            assertEquals(3, error.line)
        }

    @Test
    fun `open refuses logs of another version and missing logs`() =
        runTest {
            newStore().append(TurnStarted(1))
            val next = SessionCodec(SessionFormat.MIGRATIONS + Identity, SessionFormat.CURRENT_VERSION + 1)
            val error = assertThrows<UnsupportedSessionVersionException> { SessionStore.open(files, clock, next) }
            assertEquals(SessionFormat.CURRENT_VERSION, error.version)
            assertEquals(2, SessionStore.read(files, next).records.size, "reading through migrations still works")
            assertThrows<FileNotFoundException> {
                SessionStore.open(
                    SessionFiles(storage, StoragePath("missing")),
                    clock,
                )
            }
        }

    @Test
    fun `repair or reopen failures are write errors`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            storage.failAppend = true
            assertThrows<SessionWriteException> { store.append(StepStarted(1, 1)) }
            storage.failWrite = true
            assertThrows<SessionWriteException> { SessionStore.open(files, clock) }
        }

    @Test
    fun `an append that was started completes even if the caller is cancelled`() =
        runTest(timeout = CANCELLATION_TIMEOUT) {
            val store = newStore()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            storage.beforeAppend = {
                entered.complete(Unit)
                release.await()
            }
            val append = async(start = CoroutineStart.UNDISPATCHED) { store.append(TurnStarted(1)) }
            entered.await()
            append.cancel()
            release.complete(Unit)
            append.join()
            storage.beforeAppend = {}
            assertEquals(2, store.lastSeq, "the written record is counted")
            assertEquals(3, store.append(TurnEnded(1, TurnEndReason.STOPPED)).seq)
            assertEquals(listOf(1L, 2L, 3L), SessionStore.read(files).records.map { it.seq })
        }

    @Test
    fun `an append cancelled while it waits for the lock writes nothing`() =
        runTest(timeout = CANCELLATION_TIMEOUT) {
            val store = newStore()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            storage.beforeAppend = {
                entered.complete(Unit)
                release.await()
            }
            val holder = async(start = CoroutineStart.UNDISPATCHED) { store.append(TurnStarted(1)) }
            entered.await()
            storage.beforeAppend = {}
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { store.append(StepStarted(1, 99)) }
            waiter.cancel()
            try {
                testScheduler.runCurrent()
                assertTrue(waiter.isCompleted, "cancellation does not wait for the lock holder")
            } finally {
                release.complete(Unit)
            }
            holder.await()
            assertThrows<CancellationException> { waiter.await() }
            assertFalse(StepStarted(1, 99) in store.records().map { it.event }, "the waiting append was not written")
            assertFalse("\"step\":99" in storage.text(logPath))
        }

    @Test
    fun `an append from an already cancelled coroutine writes nothing`() =
        runTest(timeout = CANCELLATION_TIMEOUT) {
            val store = newStore()
            val before = store.records()
            val append =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coroutineContext.cancel()
                    store.append(TurnStarted(1))
                }
            assertThrows<CancellationException> { append.await() }
            assertEquals(before, store.records())
            assertFalse("turn/start" in storage.text(logPath))
        }

    @Test
    fun `cleanup code appends from a cancelled coroutine inside NonCancellable`() =
        runTest(timeout = CANCELLATION_TIMEOUT) {
            val store = newStore()
            store.append(TurnStarted(1))
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { store.append(TurnEnded(1, TurnEndReason.STOPPED)) }
                    }
                }
            job.cancel()
            job.join()
            assertEquals(TurnEnded(1, TurnEndReason.STOPPED), store.records().last().event)
        }

    @Test
    fun `a write cancelled midway commits and returns the record while the job reports cancellation`() =
        runTest(timeout = CANCELLATION_TIMEOUT) {
            val store = newStore()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            storage.beforeAppend = {
                entered.complete(Unit)
                release.await()
            }
            var returned: SessionRecord? = null
            val append =
                async(start = CoroutineStart.UNDISPATCHED) { store.append(TurnStarted(1)).also { returned = it } }
            entered.await()
            append.cancel()
            release.complete(Unit)
            assertThrows<CancellationException>("job result") { append.await() }
            val record = checkNotNull(returned) { "the method returned the record" }
            assertEquals(record, store.records().last(), "committed: callers check records(), not the exception")
            assertEquals(record.seq, store.lastSeq)
        }

    @Test
    fun `any failure of the write itself fails the store and is rethrown as it is`() =
        runTest {
            val store = newStore()
            storage.beforeAppend = { throw IllegalStateException("bug in a storage implementation") }
            assertThrows<IllegalStateException> { store.append(TurnStarted(1)) }
            storage.beforeAppend = {}
            val error = assertThrows<SessionWriteException> { store.append(TurnStarted(1)) }
            assertTrue("append of seq" in error.message.orEmpty(), error.message)
            assertThrows<SessionWriteException> { store.flush() }
        }

    @Test
    fun `argument errors before encoding leave the store writable`() =
        runTest {
            val store = newStore()
            val last = store.append(TurnStarted(1)).seq
            assertThrows<IllegalArgumentException> { store.append(StepStarted(1, 1), sources = listOf(last + 1)) }
            assertEquals(last + 1, store.append(StepStarted(1, 1)).seq)
        }

    @Test
    fun `create and open acquire the appender as their last storage step`() =
        runTest {
            val calls = mutableListOf<String>()
            val recording =
                object : StoragePort by storage {
                    override suspend fun exists(path: StoragePath) = storage.exists(path).also { calls += "exists" }

                    override suspend fun read(path: StoragePath) = storage.read(path).also { calls += "read" }

                    override suspend fun writeAtomically(
                        path: StoragePath,
                        bytes: ByteArray,
                    ) = storage.writeAtomically(path, bytes).also { calls += "write" }

                    override suspend fun openAppender(path: StoragePath) =
                        storage.openAppender(path).also { calls += "openAppender" }
                }
            val recordingFiles = SessionFiles(recording, StoragePath("sessions/session-1"))
            SessionStore
                .create(
                    recordingFiles,
                    SessionSamples.session,
                    SessionSamples.task,
                    SessionSamples.start,
                    clock,
                ).close()
            assertEquals("openAppender", calls.last(), "nothing after the handle is taken can fail and leak it")
            calls.clear()
            SessionStore.open(recordingFiles, clock).store.close()
            assertEquals("openAppender", calls.last())
        }

    private companion object {
        /** Wall-clock bound of the cancellation tests (their waits use barriers, never real time). */
        val CANCELLATION_TIMEOUT = 10.seconds
    }

    private object Identity : SessionMigration {
        override val fromVersion = SessionFormat.CURRENT_VERSION

        override fun migrate(envelope: kotlinx.serialization.json.JsonObject) = envelope
    }
}
