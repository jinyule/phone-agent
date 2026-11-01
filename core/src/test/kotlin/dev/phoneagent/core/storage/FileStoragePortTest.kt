package dev.phoneagent.core.storage

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionStore
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

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

        override fun syncDirectory(dir: Path) {
            synced.add(dir)
            FileOps.SYSTEM.syncDirectory(dir)
        }

        override fun force(channel: FileChannel) {
            if (failForce) throw IOException("injected force failure")
            FileOps.SYSTEM.force(channel)
        }

        override fun openAppend(file: Path): FileChannel = FileOps.SYSTEM.openAppend(file).also { opened += it }
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

    @Test
    fun `new directories are made durable from the deepest up to the first existing one`() =
        runTest {
            val ops = RecordingOps()
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            storage.writeAtomically(StoragePath("a/b/c/f.bin"), byteArrayOf(1))
            assertEquals(
                listOf(root.resolve("a/b"), root.resolve("a"), root, root.resolve("a/b/c")),
                ops.synced,
                "parents of c, b and a, then the directory holding the renamed file",
            )
            ops.synced.clear()
            storage.writeAtomically(StoragePath("a/b/c/g.bin"), byteArrayOf(2))
            assertEquals(listOf(root.resolve("a/b/c")), ops.synced, "existing directories are not synced again")

            ops.synced.clear()
            storage.openAppender(StoragePath("a/d/e/log.jsonl")).close()
            assertEquals(
                listOf(root.resolve("a/d"), root.resolve("a"), root.resolve("a/d/e")),
                ops.synced,
                "parents of e and d, then the directory holding the new file",
            )
            ops.synced.clear()
            storage.openAppender(StoragePath("a/d/e/log.jsonl")).close()
            assertEquals(emptyList<Path>(), ops.synced, "reopening an existing file changes no directory")
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
        runTest {
            val io = QueueDispatcher()
            val ops = RecordingOps()
            val storage = FileStoragePort(root, io, ops)
            val open = async(start = CoroutineStart.UNDISPATCHED) { storage.openAppender(StoragePath("x/log.jsonl")) }
            io.runAll()
            assertEquals(1, ops.opened.size, "the channel was opened on the I/O dispatcher")
            open.cancel()
            assertTrue(runCatching { open.await() }.exceptionOrNull() is CancellationException)
            assertFalse(ops.opened.single().isOpen, "closed because nobody else can")
        }

    @Test
    fun `a failed directory sync after opening closes the new handle`() =
        runTest {
            val ops =
                object : FileOps by RecordingOps() {
                    val opened = mutableListOf<FileChannel>()

                    override fun openAppend(file: Path): FileChannel =
                        FileOps.SYSTEM.openAppend(file).also {
                            opened +=
                                it
                        }

                    override fun syncDirectory(dir: Path): Unit = throw IOException("injected sync failure")
                }
            val storage = FileStoragePort(root, StandardTestDispatcher(testScheduler), ops)
            assertThrows<IOException> { storage.openAppender(StoragePath("log.jsonl")) }
            assertFalse(ops.opened.single().isOpen)
        }

    @Test
    fun `a session create cancelled while its appender opens leaves no handle open`() =
        runTest {
            val io = QueueDispatcher()
            val ops = RecordingOps()
            val files = SessionFiles(FileStoragePort(root, io, ops), StoragePath("sessions/s1"))
            val create =
                async(start = CoroutineStart.UNDISPATCHED) {
                    SessionStore.create(files, SessionId("s1"), TaskId("t1"), Clock { 0 })
                }
            while (true) {
                io.runAll()
                if (ops.opened.isNotEmpty()) break
                testScheduler.runCurrent()
            }
            create.cancel()
            assertTrue(runCatching { create.await() }.exceptionOrNull() is CancellationException)
            assertFalse(ops.opened.single().isOpen)
        }

    @Test
    fun `a session log round-trips through real files`() =
        runTest {
            val files = SessionFiles(port(testScheduler), StoragePath("sessions/s1"))
            val store = SessionStore.create(files, SessionId("s1"), TaskId("t1"), Clock { 0 })
            store.append(TurnStarted(1))
            store.flush()
            store.close()
            assertEquals(listOf(TurnStarted(1)), SessionStore.read(files).records.map { it.event })
            assertTrue(Files.isRegularFile(root.resolve("sessions/s1/session.jsonl")))
        }
}
