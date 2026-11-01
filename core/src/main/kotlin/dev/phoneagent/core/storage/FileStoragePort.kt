package dev.phoneagent.core.storage

import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * [StoragePort] on a local directory (the app's private files dir on Android, a temp dir in
 * tests). Atomic writes go through a temp file in the same directory, fsync, rename, and fsync
 * of the directory, so a completed write survives power loss. Blocking I/O runs on [io].
 */
public class FileStoragePort(
    private val root: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StoragePort {
    override suspend fun read(path: StoragePath): ByteArray? =
        withContext(io) {
            val file = fileOf(path)
            if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
        }

    override suspend fun exists(path: StoragePath): Boolean = withContext(io) { Files.isRegularFile(fileOf(path)) }

    override suspend fun writeAtomically(
        path: StoragePath,
        bytes: ByteArray,
    ): Unit =
        withContext(io) {
            val file = fileOf(path)
            val dir = Files.createDirectories(file.parent)
            val temp = Files.createTempFile(dir, ".${file.fileName}.", ".tmp")
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                writeFully(channel, bytes)
                channel.force(true)
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(dir)
        }

    override suspend fun openAppender(path: StoragePath): StorageAppender =
        withContext(io) {
            val file = fileOf(path)
            Files.createDirectories(file.parent)
            FileAppender(FileChannel.open(file, APPEND_OPTIONS), io)
        }

    override suspend fun list(directory: StoragePath): List<StoragePath> =
        withContext(io) {
            val dir = fileOf(directory)
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.list(dir).use { entries ->
                    entries
                        .filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }
                        .map { directory.resolve(it.fileName.toString()) }
                        .toList()
                        .sortedBy { it.value }
                }
            }
        }

    override suspend fun delete(path: StoragePath): Boolean = withContext(io) { Files.deleteIfExists(fileOf(path)) }

    private fun fileOf(path: StoragePath): Path = root.resolve(path.value)

    private class FileAppender(
        private val channel: FileChannel,
        private val io: CoroutineDispatcher,
    ) : StorageAppender {
        override suspend fun append(bytes: ByteArray): Unit = withContext(io) { writeFully(channel, bytes) }

        override suspend fun sync(): Unit = withContext(io) { channel.force(false) }

        override suspend fun close(): Unit = withContext(io) { channel.close() }
    }

    private companion object {
        val APPEND_OPTIONS = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)

        fun writeFully(
            channel: FileChannel,
            bytes: ByteArray,
        ) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        /** Makes a rename durable. Directories can be opened for reading on Linux and macOS. */
        fun syncDirectory(dir: Path) {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
