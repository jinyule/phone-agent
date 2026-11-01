package dev.phoneagent.core.storage

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ports.Clock
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.SessionStore
import dev.phoneagent.core.session.TurnStarted
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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
