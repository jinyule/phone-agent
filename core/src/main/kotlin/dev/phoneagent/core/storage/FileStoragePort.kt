package dev.phoneagent.core.storage

import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
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
 * The root may be relative and need not exist: it is resolved against the working directory when
 * the port is built, and created on the first write.
 *
 * Durability: every [writeAtomically] and every [openAppender] fsyncs the whole directory chain
 * this port controls, every time: the file's directory, each directory above it up to the root,
 * and the root's parent. Whether a directory is new is never what decides, so a retry after a
 * failed sync makes the chain durable even though an earlier attempt already created it.
 *
 * Atomic writes go through a temp file in the same directory (write, fsync, rename), then the
 * chain is fsynced. A failure:
 * - before the rename deletes the temp file and leaves the old content;
 * - after the rename (fsync of a directory) leaves the new content in place but not known to be
 *   durable: the caller cannot conclude from the exception that the content is unchanged.
 */
public class FileStoragePort internal constructor(
    root: Path,
    private val io: CoroutineDispatcher,
    private val ops: FileOps,
) : StoragePort {
    private val root: Path = root.toAbsolutePath().normalize()

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
            val dir = Files.createDirectories(file.parent)
            val temp = Files.createTempFile(dir, ".${file.fileName}.", ".tmp")
            try {
                replace(file, temp, bytes)
            } catch (failure: IOException) {
                ops.deleteAfterFailure(temp, failure)
                throw failure
            }
            syncChain(dir)
        }

    /**
     * Opens [path] for appending. The handle belongs to this method until it returns: when the
     * caller is cancelled after the file was opened but before the result is delivered, the
     * handle is closed here, because nobody else could.
     */
    override suspend fun openAppender(path: StoragePath): StorageAppender {
        val opened = AtomicReference<FileChannel>()
        var delivered = false
        try {
            withContext(io) { opened.set(openForAppend(fileOf(path))) }
            delivered = true
        } finally {
            // Cancelled after the file was opened: the result is dropped, and nobody else has the handle.
            if (!delivered) opened.get()?.let(ops::closeUndelivered)
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

    /** fsyncs [dir], each directory above it up to [root], and the root's parent, deepest first. */
    private fun syncChain(dir: Path) {
        generateSequence(dir) { it.parent }.takeWhile { it.startsWith(root) }.forEach(ops::syncDirectory)
        root.parent?.let(ops::syncDirectory)
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

    /**
     * Opens [file] for appending and fsyncs its directory chain, also when the file existed: it
     * may come from an earlier attempt whose sync failed.
     */
    private fun openForAppend(file: Path): FileChannel {
        val dir = Files.createDirectories(file.parent)
        val channel = ops.openAppend(file)
        try {
            syncChain(dir)
        } catch (failure: IOException) {
            ops.closeAfterFailure(channel, failure)
            throw failure
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
        fun writeFully(
            channel: FileChannel,
            bytes: ByteArray,
        ) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}

/** Removes the temp file of a failed write; a second failure is attached to the first. */
private fun FileOps.deleteAfterFailure(
    temp: Path,
    failure: Throwable,
) {
    try {
        deleteIfExists(temp)
    } catch (cleanup: IOException) {
        failure.addSuppressed(cleanup)
    }
}

/**
 * Closes a handle that was opened for a caller who was cancelled before receiving it.
 * A close failure is dropped: the caller already ends with its cancellation.
 */
private fun FileOps.closeUndelivered(channel: FileChannel) {
    try {
        close(channel)
    } catch (ignored: IOException) {
        // Nobody holds the handle and nobody waits for a result to report this to.
    }
}

/** Closes a handle that will not be handed out; a close failure is attached to [failure]. */
private fun FileOps.closeAfterFailure(
    channel: FileChannel,
    failure: Throwable,
) {
    try {
        close(channel)
    } catch (closing: IOException) {
        failure.addSuppressed(closing)
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

    /** Closes [channel]. */
    fun close(channel: FileChannel)

    /** Deletes [file] if it exists. */
    fun deleteIfExists(file: Path)

    companion object {
        /** The real calls. Directories can be opened for reading on Linux and macOS. */
        val SYSTEM: FileOps =
            object : FileOps {
                override fun syncDirectory(dir: Path) {
                    FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
                }

                override fun force(channel: FileChannel) = channel.force(true)

                override fun openAppend(file: Path): FileChannel = FileChannel.open(file, APPEND_OPTIONS)

                override fun close(channel: FileChannel) = channel.close()

                override fun deleteIfExists(file: Path) {
                    Files.deleteIfExists(file)
                }
            }

        private val APPEND_OPTIONS =
            setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }
}
