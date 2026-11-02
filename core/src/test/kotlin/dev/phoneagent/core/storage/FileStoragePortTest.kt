package dev.phoneagent.core.storage

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/** Bounded like `SessionStoreTest`: a mutation that blocks a wait fails the test instead of hanging. */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileStoragePortTest {
    @TempDir
    lateinit var root: Path

    private fun port(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        FileStoragePort(root, StandardTestDispatcher(scheduler))

    @Test
    fun `atomic writes create parents, replace content and leave no temp files`() =
        runTest {
            val storage = port(testScheduler)
            val path = StoragePath("a/b/c.bin")
            assertNull(storage.read(path))
            assertFalse(storage.exists(path))
            storage.writeAtomically(path, byteArrayOf(1, 2))
            storage.writeAtomically(path, byteArrayOf(3))
            assertArrayEquals(byteArrayOf(3), storage.read(path))
            assertTrue(storage.exists(path))
            assertEquals(listOf("c.bin"), Files.list(root.resolve("a/b")).map { it.fileName.toString() }.toList())
        }

    @Test
    fun `appender appends, syncs and closes`() =
        runTest {
            val storage = port(testScheduler)
            val path = StoragePath("log/x.jsonl")
            val appender = storage.openAppender(path)
            appender.append("one\n".encodeToByteArray())
            appender.append("two\n".encodeToByteArray())
            appender.sync()
            appender.close()
            appender.close()
            val again = storage.openAppender(path)
            again.append("three\n".encodeToByteArray())
            again.close()
            assertEquals("one\ntwo\nthree\n", storage.read(path)?.decodeToString())
        }

    @Test
    fun `list returns sorted files only, and delete reports existence`() =
        runTest {
            val storage = port(testScheduler)
            val dir = StoragePath("d")
            assertEquals(emptyList<StoragePath>(), storage.list(dir))
            storage.writeAtomically(dir.resolve("b"), byteArrayOf())
            storage.writeAtomically(dir.resolve("a"), byteArrayOf())
            storage.writeAtomically(dir.resolve("sub/c"), byteArrayOf())
            Files.writeString(root.resolve("d/.hidden.tmp"), "x")
            assertEquals(listOf(dir.resolve("a"), dir.resolve("b")), storage.list(dir))
            assertTrue(storage.delete(dir.resolve("a")))
            assertFalse(storage.delete(dir.resolve("a")))
        }

    /** Real file calls that record directory syncs and opened channels, and can fail `force`. */
    private class RecordingOps : FileOps {
        val synced = mutableListOf<Path>()
        val opened = mutableListOf<FileChannel>()
        var failForce = false
        var failSync = false

        /** Fails the next sync of this directory, once. */
        var failSyncOnce: Path? = null
        var failClose = false
        var failDelete = false

        override fun syncDirectory(dir: Path) {
            if (failSync) throw IOException("injected sync failure")
            if (dir == failSyncOnce) {
                failSyncOnce = null
                throw IOException("injected sync failure of $dir")
            }
            synced.add(dir)
            FileOps.SYSTEM.syncDirectory(dir)
        }

        override fun force(channel: FileChannel) {
            if (failForce) throw IOException("injected force failure")
            FileOps.SYSTEM.force(channel)
        }

        override fun openAppend(file: Path): FileChannel = FileOps.SYSTEM.openAppend(file).also { opened += it }

        /** Closes for real even when failing, so the test leaks nothing. */
        override fun close(channel: FileChannel) {
            FileOps.SYSTEM.close(channel)
            if (failClose) throw IOException("injected close failure")
        }

        override fun deleteIfExists(file: Path) {
            if (failDelete) throw IOException("injected delete failure")
            FileOps.SYSTEM.deleteIfExists(file)
        }
    }

    /** Runs dispatched blocks only when asked, so a test can act between the I/O and the caller's resumption. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue += block
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** The directories a new file at [dir]/<name> makes durable: [dir], each one above it, the root, its parent. */
    private fun chain(dir: String): List<Path> =
        generateSequence(root.resolve(dir)) { it.parent }.takeWhile { it.startsWith(root) }.toList() +
            listOf(root.parent)

    @Test
    fun `every new file makes its whole directory chain durable, up to the root's parent`() =
        runTest {
            val ops = RecordingOps()
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            storage.writeAtomically(StoragePath("a/b/c/f.bin"), byteArrayOf(1))
            assertEquals(
                listOf(root.resolve("a/b/c"), root.resolve("a/b"), root.resolve("a"), root, root.parent),
                ops.synced,
                "the file's directory, each one above it, the root and the root's parent",
            )
            ops.synced.clear()
            storage.writeAtomically(StoragePath("a/b/c/g.bin"), byteArrayOf(2))
            assertEquals(chain("a/b/c"), ops.synced, "also when every directory already existed")

            ops.synced.clear()
            storage.openAppender(StoragePath("a/d/log.jsonl")).close()
            assertEquals(chain("a/d"), ops.synced, "a file created by openAppender")
            ops.synced.clear()
            storage.openAppender(StoragePath("a/d/log.jsonl")).close()
            assertEquals(chain("a/d"), ops.synced, "reopening: the file may be left by an attempt whose sync failed")
        }

    @Test
    fun `a retry after a failed directory sync makes the whole chain durable, on the same port or a new one`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val operations =
                mapOf<String, suspend (FileStoragePort, StoragePath) -> Unit>(
                    "writeAtomically" to { storage, path -> storage.writeAtomically(path, byteArrayOf(1)) },
                    "openAppender" to { storage, path -> storage.openAppender(path).close() },
                )
            val incomplete = mutableListOf<String>()
            var run = 0
            operations.forEach { (name, operation) ->
                listOf(false, true).forEach { newPort ->
                    val dir = "r${run++}/b/c"
                    chain(dir).forEach { failing ->
                        val ops = RecordingOps().apply { failSyncOnce = failing }
                        val first = FileStoragePort(root, dispatcher, ops)
                        val path = StoragePath("$dir/f${chain(dir).indexOf(failing)}.bin")
                        assertThrows<IOException>("$name fails at $failing") { operation(first, path) }
                        ops.synced.clear()
                        operation(if (newPort) FileStoragePort(root, dispatcher, ops) else first, path)
                        if (ops.synced !=
                            chain(dir)
                        ) {
                            incomplete += "$name, new port $newPort, failed at $failing: ${ops.synced}"
                        }
                    }
                }
            }
            assertEquals(emptyList<String>(), incomplete, "retries that skipped part of the chain")
        }

    @Test
    fun `a directory sync failing after the rename keeps the new content but still fails the write`() =
        runTest {
            val ops = RecordingOps()
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            val path = StoragePath("d/f.bin")
            storage.writeAtomically(path, byteArrayOf(1))
            ops.failSync = true
            assertThrows<IOException> { storage.writeAtomically(path, byteArrayOf(2)) }
            ops.failSync = false
            assertArrayEquals(byteArrayOf(2), storage.read(path), "the rename took effect; only durability is unknown")
        }

    @Test
    fun `a relative root that does not exist yet works for atomic writes`() =
        runTest {
            withRelativeRoot { relative ->
                val storage = FileStoragePort(relative, StandardTestDispatcher(testScheduler))
                storage.writeAtomically(StoragePath("f.bin"), byteArrayOf(1))
                storage.writeAtomically(StoragePath("d/g.bin"), byteArrayOf(2))
                assertArrayEquals(byteArrayOf(1), storage.read(StoragePath("f.bin")))
                assertArrayEquals(byteArrayOf(2), Files.readAllBytes(relative.resolve("d/g.bin")))
            }
        }

    @Test
    fun `a relative root that does not exist yet works for appenders`() =
        runTest {
            withRelativeRoot { relative ->
                val storage = FileStoragePort(relative, StandardTestDispatcher(testScheduler))
                val appender = storage.openAppender(StoragePath("log.jsonl"))
                appender.append("x\n".encodeToByteArray())
                appender.close()
                assertEquals("x\n", storage.read(StoragePath("log.jsonl"))?.decodeToString())
            }
        }

    /** Runs [block] with a one-segment relative root that does not exist yet, and removes it afterwards. */
    private suspend fun withRelativeRoot(block: suspend (Path) -> Unit) {
        val relative = Path.of(".file-storage-port-test-${System.nanoTime()}")
        assertFalse(relative.isAbsolute)
        assertFalse(Files.exists(relative))
        try {
            block(relative)
        } finally {
            relative.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a failed atomic write leaves no temp file and keeps the old content`() =
        runTest {
            val ops = RecordingOps()
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            val path = StoragePath("d/f.bin")
            storage.writeAtomically(path, byteArrayOf(1))
            ops.failForce = true
            assertThrows<IOException> { storage.writeAtomically(path, byteArrayOf(2)) }
            ops.failForce = false
            assertArrayEquals(byteArrayOf(1), storage.read(path))

            Files.createDirectories(root.resolve("d/dir/inner"))
            assertThrows<IOException>("rename onto a non-empty directory") {
                storage.writeAtomically(StoragePath("d/dir"), byteArrayOf(3))
            }
            assertEquals(
                listOf("dir", "f.bin"),
                Files
                    .list(root.resolve("d"))
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList(),
            )
        }

    @Test
    fun `a caller cancelled before the appender is handed over does not leak the handle`() =
        runTest(timeout = 10.seconds) {
            val io = QueueDispatcher()
            val ops = RecordingOps()
            val storage = FileStoragePort(root, io, ops)
            val open = async(start = CoroutineStart.UNDISPATCHED) { storage.openAppender(StoragePath("x/log.jsonl")) }
            io.runAll()
            assertEquals(1, ops.opened.size, "the channel was opened on the I/O dispatcher")
            open.cancel()
            assertThrows<CancellationException> { open.await() }
            assertFalse(ops.opened.single().isOpen, "closed because nobody else can")
        }

    @Test
    fun `a failed directory sync after opening closes the new handle`() =
        runTest {
            val ops = RecordingOps().apply { failSync = true }
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            assertThrows<IOException> { storage.openAppender(StoragePath("log.jsonl")) }
            assertFalse(ops.opened.single().isOpen)
        }

    @Test
    fun `cleanup failures are attached to the error the caller sees`() =
        runTest {
            val ops = RecordingOps()
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            ops.failForce = true
            ops.failDelete = true
            val write = assertThrows<IOException> { storage.writeAtomically(StoragePath("f.bin"), byteArrayOf(1)) }
            assertEquals("injected force failure", write.message)
            assertEquals(listOf("injected delete failure"), write.suppressedInChain())

            ops.failSync = true
            ops.failClose = true
            val open = assertThrows<IOException> { storage.openAppender(StoragePath("log.jsonl")) }
            assertEquals("injected sync failure", open.message)
            assertEquals(listOf("injected close failure"), open.suppressedInChain())
        }

    /** Coroutines may rethrow a copy for stack-trace recovery, with the original as its cause. */
    private fun Throwable.suppressedInChain(): List<String?> =
        generateSequence(this) { it.cause }.flatMap { it.suppressed.asSequence() }.map { it.message }.toList()

    @Test
    fun `a close failure of an undelivered handle does not replace the cancellation`() =
        runTest(timeout = 10.seconds) {
            val io = QueueDispatcher()
            val ops = RecordingOps().apply { failClose = true }
            val storage = FileStoragePort(root, io, ops)
            val open = async(start = CoroutineStart.UNDISPATCHED) { storage.openAppender(StoragePath("x/log.jsonl")) }
            io.runAll()
            open.cancel()
            assertThrows<CancellationException> { open.await() }
            assertFalse(ops.opened.single().isOpen)
        }

    @Test
    fun `a session create cancelled while its appender opens leaves no handle open`() =
        runTest(timeout = 10.seconds) {
            val io = QueueDispatcher()
            val ops = RecordingOps()
            val files = SessionFiles(FileStoragePort(root, io, ops), StoragePath("sessions/s1"))
            val create =
                async(start = CoroutineStart.UNDISPATCHED) {
                    SessionStore.create(
                        files,
                        SessionId("s1"),
                        TaskId("t1"),
                        TaskStarted(SecurityLevel.STANDARD, false),
                        Clock { 0 },
                    )
                }
            // Bounded: run the I/O and test dispatchers until the appender is opened, and stop right
            // there, before the caller resumes with it.
            var rounds = 0
            io.runAll()
            while (ops.opened.isEmpty() && rounds++ < MAX_ROUNDS) {
                testScheduler.runCurrent()
                io.runAll()
            }
            assertEquals(1, ops.opened.size, "the appender was opened within $MAX_ROUNDS rounds")
            create.cancel()
            assertThrows<CancellationException> { create.await() }
            assertFalse(ops.opened.single().isOpen)
        }

    @Test
    fun `a session log round-trips through real files`() =
        runTest {
            val files = SessionFiles(port(testScheduler), StoragePath("sessions/s1"))
            val store =
                SessionStore.create(
                    files,
                    SessionId(
                        "s1",
                    ),
                    TaskId("t1"),
                    TaskStarted(SecurityLevel.STANDARD, false),
                    Clock {
                        0
                    },
                )
            store.append(TurnStarted(1))
            store.flush()
            store.close()
            assertEquals(
                listOf(TaskStarted(SecurityLevel.STANDARD, false), TurnStarted(1)),
                SessionStore.read(files).records.map { it.event },
            )
            assertTrue(Files.isRegularFile(root.resolve("sessions/s1/session.jsonl")))
        }

    private companion object {
        /** Dispatcher rounds a create needs to reach the open; far more than the few it takes. */
        const val MAX_ROUNDS = 100
    }
}
