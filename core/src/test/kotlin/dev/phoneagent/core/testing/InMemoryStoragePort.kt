package dev.phoneagent.core.testing

import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import java.io.IOException

/**
 * In-memory [StoragePort] for tests, with fault injection. Shared by all core tests.
 *
 * Set [failSync] / [failAppend] / [failWrite] to make the next calls throw; [beforeAppend] runs
 * inside every append (e.g. to suspend on a barrier). [writes] counts atomic writes per path.
 */
class InMemoryStoragePort : StoragePort {
    val files = linkedMapOf<String, ByteArray>()
    val writes = mutableMapOf<String, Int>()
    var failSync = false
    var failAppend = false
    var failWrite = false
    var beforeAppend: suspend () -> Unit = {}
    var syncCount = 0
        private set

    fun text(path: String): String = files.getValue(path).decodeToString()

    override suspend fun read(path: StoragePath): ByteArray? = files[path.value]?.copyOf()

    override suspend fun exists(path: StoragePath): Boolean = path.value in files

    override suspend fun writeAtomically(
        path: StoragePath,
        bytes: ByteArray,
    ) {
        if (failWrite) throw IOException("injected write failure")
        files[path.value] = bytes.copyOf()
        writes.merge(path.value, 1, Int::plus)
    }

    override suspend fun openAppender(path: StoragePath): StorageAppender {
        files.putIfAbsent(path.value, ByteArray(0))
        return object : StorageAppender {
            override suspend fun append(bytes: ByteArray) {
                beforeAppend()
                if (failAppend) {
                    // A torn write: half of the bytes reach the file before the failure.
                    files[path.value] = files.getValue(path.value) + bytes.copyOf(bytes.size / 2)
                    throw IOException("injected append failure")
                }
                files[path.value] = files.getValue(path.value) + bytes
            }

            override suspend fun sync() {
                if (failSync) throw IOException("injected sync failure")
                syncCount++
            }

            override suspend fun close() = Unit
        }
    }

    override suspend fun list(directory: StoragePath): List<StoragePath> =
        files.keys
            .filter { it.substringBeforeLast('/', "") == directory.value }
            .sorted()
            .map(::StoragePath)

    override suspend fun delete(path: StoragePath): Boolean = files.remove(path.value) != null
}
