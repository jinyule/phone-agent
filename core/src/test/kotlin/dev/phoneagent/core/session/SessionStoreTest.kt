package dev.phoneagent.core.session

import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.FileNotFoundException

class SessionStoreTest {
    private val storage = InMemoryStoragePort()
    private val files = SessionFiles(storage, StoragePath("sessions/session-1"))
    private val logPath = "sessions/session-1/session.jsonl"
    private var now = 100L
    private val clock = Clock { now++ }

    private suspend fun newStore() = SessionStore.create(files, SessionSamples.session, SessionSamples.task, clock)

    @Test
    fun `appends get consecutive seqs and clock times and read back in order`() =
        runTest {
            val store = newStore()
            val first = store.append(TurnStarted(1))
            val second = store.append(StepStarted(1, 1), sources = listOf(1))
            store.flush()
            assertEquals(SessionRecord(1, 101, TurnStarted(1)), first)
            assertEquals(SessionRecord(2, 102, StepStarted(1, 1), sources = listOf(1)), second)
            assertEquals(2, store.lastSeq)
            assertEquals(listOf(first, second), store.records())
            val log = SessionStore.read(files)
            assertEquals(store.header, log.header)
            assertEquals(
                SessionHeader(
                    version = 1,
                    sessionId = SessionSamples.session,
                    taskId = SessionSamples.task,
                    createdAt = 100,
                ),
                log.header,
            )
            assertEquals(listOf(first, second), log.records)
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
            assertThrows<IllegalArgumentException> { store.append(StepStarted(1, 1), sources = listOf(2)) }
            assertThrows<IllegalArgumentException> {
                store.append(
                    CompactionPrune(emptyList()),
                    SurfaceOp.Replace(1, 2),
                )
            }
            assertEquals(1, store.lastSeq)
            assertEquals(2, storage.text(logPath).lines().count { it.isNotEmpty() })
            assertEquals(2, store.append(StepStarted(1, 1)).seq)
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
            assertTrue("flush after seq 1 failed" in append.message.orEmpty(), append.message)
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
            assertEquals(3, tail?.line)
            assertEquals(torn - storage.text(logPath).length, tail?.bytes)
            assertTrue(storage.text(logPath).endsWith("\n"), "torn bytes removed from the file")
            assertEquals(listOf(TurnStarted(1)), opened.log.records.map { it.event })

            val next = opened.store.append(StepStarted(1, 1))
            assertEquals(2, next.seq)
            opened.store.flush()
            assertEquals(listOf(1L, 2L), SessionStore.read(files).records.map { it.seq })
            assertEquals(listOf(1L, 2L), opened.store.records().map { it.seq })
        }

    @Test
    fun `reopening a clean log keeps the file and continues the sequence`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            store.close()
            assertThrows<SessionWriteException> { store.append(TurnStarted(2)) }
            val writesBefore = storage.writes.getValue(logPath)
            val opened = SessionStore.open(files, clock)
            assertNull(opened.log.truncatedTail)
            assertEquals(writesBefore, storage.writes.getValue(logPath), "no rewrite without a torn tail")
            assertEquals(2, opened.store.append(TurnStarted(2)).seq)
        }

    @Test
    fun `reopening continues after skipped ignorable records`() =
        runTest {
            newStore().append(TurnStarted(1))
            storage.files[logPath] =
                (
                    storage.text(
                        logPath,
                    ) + """{"seq":2,"time":0,"type":"future/x","ignorable":true,"data":{}}""" + "\n"
                ).encodeToByteArray()
            val opened = SessionStore.open(files, clock)
            assertEquals(listOf(SkippedRecord(2, "future/x")), opened.log.skipped)
            assertEquals(3, opened.store.append(TurnEnded(1, TurnEndReason.COMPLETED)).seq)
        }

    @Test
    fun `corruption in the middle is never repaired silently`() =
        runTest {
            val store = newStore()
            store.append(TurnStarted(1))
            store.append(StepStarted(1, 1))
            val lines = storage.text(logPath).lines().toMutableList()
            lines[1] = lines[1].dropLast(3)
            storage.files[logPath] = lines.joinToString("\n").encodeToByteArray()
            val error = assertThrows<SessionCorruptedException> { SessionStore.open(files, clock) }
            assertEquals(2, error.line)
        }

    @Test
    fun `open refuses logs of another version and missing logs`() =
        runTest {
            newStore().append(TurnStarted(1))
            val v2 = SessionCodec(listOf(Identity), currentVersion = 2)
            val error = assertThrows<UnsupportedSessionVersionException> { SessionStore.open(files, clock, v2) }
            assertEquals(1, error.version)
            assertEquals(1, SessionStore.read(files, v2).records.size, "reading through migrations still works")
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
            assertThrows<SessionWriteException> { store.append(TurnStarted(2)) }
            storage.failWrite = true
            assertThrows<SessionWriteException> { SessionStore.open(files, clock) }
        }

    @Test
    fun `an append that was started completes even if the caller is cancelled`() =
        runTest {
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
            assertEquals(1, store.lastSeq, "the written record is counted")
            assertEquals(2, store.append(TurnEnded(1, TurnEndReason.STOPPED)).seq)
            assertEquals(listOf(1L, 2L), SessionStore.read(files).records.map { it.seq })
        }

    private object Identity : SessionMigration {
        override val fromVersion = 1

        override fun migrate(envelope: kotlinx.serialization.json.JsonObject) = envelope
    }
}
