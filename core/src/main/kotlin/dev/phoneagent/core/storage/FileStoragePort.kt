package dev.phoneagent.core.storage

import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference

/**
 * [StoragePort] on a local directory (the app's private files dir on Android, a temp dir in
 * tests). Blocking I/O runs on [io].
 *
 * Durability: directories this port creates are made durable by fsyncing the parent of each new
 * one, deepest first, up to the first directory that already existed. Atomic writes go through a
 * temp file in the same directory (fsync, rename, fsync of the directory), so a completed write
 * survives power loss; a failed one deletes its temp file and leaves the old content. A file
 * created by [openAppender] gets its directory fsynced before the appender is returned.
 */
public class FileStoragePort internal constructor(
    private val root: Path,
    private val io: CoroutineDispatcher,
    private val ops: FileOps,
) : StoragePort {
    /** A port rooted at [root], running blocking I/O on [io]. */
    public constructor(root: Path, io: CoroutineDispatcher = Dispatchers.IO) : this(root, io, FileOps.SYSTEM)

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
            val dir = createDirectories(file.parent)
            val temp = Files.createTempFile(dir, ".${file.fileName}.", ".tmp")
            try {
                replace(file, temp, bytes)
            } catch (failure: IOException) {
                deleteAfterFailure(temp, failure)
                throw failure
            }
            ops.syncDirectory(dir)
        }

    /**
     * Opens [path] for appending. The handle belongs to this method until it returns: when the
     * caller is cancelled after the file was opened but before the result is delivered, the
     * handle is closed here, because nobody else could.
     */
    override suspend fun openAppender(path: StoragePath): StorageAppender {
        val opened = AtomicReference<FileChannel>()
        try {
            withContext(io) { opened.set(openForAppend(fileOf(path))) }
        } catch (cancelled: CancellationException) {
            opened.get()?.let { closeAfterFailure(it, cancelled) }
            throw cancelled
        }
        return FileAppender(opened.get(), io)
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

    /** Creates [dir] and its missing parents, then fsyncs the parent of each new one, deepest first. */
    private fun createDirectories(dir: Path): Path {
        val missing = generateSequence(dir) { it.parent }.takeWhile { !Files.isDirectory(it) }.toList()
        Files.createDirectories(dir)
        missing.forEach { ops.syncDirectory(it.parent) }
        return dir
    }

    /** Writes [bytes] to [temp], fsyncs it and renames it over [file]. */
    private fun replace(
        file: Path,
        temp: Path,
        bytes: ByteArray,
    ) {
        FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
            writeFully(channel, bytes)
            ops.force(channel)
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Opens [file] for appending; a file created here gets its directory entry fsynced. */
    private fun openForAppend(file: Path): FileChannel {
        val dir = createDirectories(file.parent)
        val created = !Files.exists(file)
        val channel = ops.openAppend(file)
        if (created) {
            try {
                ops.syncDirectory(dir)
            } catch (failure: IOException) {
                closeAfterFailure(channel, failure)
                throw failure
            }
        }
        return channel
    }

    private class FileAppender(
        private val channel: FileChannel,
        private val io: CoroutineDispatcher,
    ) : StorageAppender {
        override suspend fun append(bytes: ByteArray): Unit = withContext(io) { writeFully(channel, bytes) }

        override suspend fun sync(): Unit = withContext(io) { channel.force(false) }

        /** Closes the channel even when the caller is cancelled, so the handle is never leaked. */
        override suspend fun close(): Unit = withContext(io + NonCancellable) { channel.close() }
    }

    private companion object {
        /** Removes the temp file of a failed write; a second failure is attached to the first. */
        fun deleteAfterFailure(
            temp: Path,
            failure: Throwable,
        ) {
            try {
                Files.deleteIfExists(temp)
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            }
        }

        /** Closes a handle that will not be handed out; a close failure is attached to [failure]. */
        fun closeAfterFailure(
            channel: FileChannel,
            failure: Throwable,
        ) {
            try {
                channel.close()
            } catch (closing: IOException) {
                failure.addSuppressed(closing)
            }
        }

        fun writeFully(
            channel: FileChannel,
            bytes: ByteArray,
        ) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}

/** The blocking file calls [FileStoragePort] makes that tests replace to observe or fail them. */
internal interface FileOps {
    /** fsyncs [dir], making the entries created or renamed in it durable. */
    fun syncDirectory(dir: Path)

    /** fsyncs [channel]'s content and metadata. */
    fun force(channel: FileChannel)

    /** Opens [file] for appending, creating it when absent. */
    fun openAppend(file: Path): FileChannel

    companion object {
        /** The real calls. Directories can be opened for reading on Linux and macOS. */
        val SYSTEM: FileOps =
            object : FileOps {
                override fun syncDirectory(dir: Path) {
                    FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
                }

                override fun force(channel: FileChannel) = channel.force(true)

                override fun openAppend(file: Path): FileChannel = FileChannel.open(file, APPEND_OPTIONS)
            }

        private val APPEND_OPTIONS =
            setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }
}
