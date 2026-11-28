package dev.phoneagent.core.testing

import dev.phoneagent.core.ports.StorageAppender
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import java.io.IOException

/**
 * In-memory [StoragePort] for tests, with fault injection. Shared by all core tests.
 *
 * Set [failSync] / [failAppend] / [failWrite] to make the next calls throw, or [syncFailures] /
 * [appendFailures] to make only the next n calls throw; [beforeAppend] runs inside every append
 * and [beforeSync] inside every sync (e.g. to suspend on a barrier, or to take virtual time).
 * [writes] counts atomic writes per path, [openAppenders] the appenders not closed yet.
 */
class InMemoryStoragePort : StoragePort {
    val files = linkedMapOf<String, ByteArray>()
    val writes = mutableMapOf<String, Int>()
    var failSync = false
    var failAppend = false
    var failWrite = false
    var syncFailures = 0
    var appendFailures = 0
    var beforeAppend: suspend () -> Unit = {}
    var beforeSync: suspend () -> Unit = {}
    var syncCount = 0
        private set

    /** Appenders opened and not closed yet. */
    var openAppenders = 0
        private set

    fun text(path: String): String = files.getValue(path).decodeToString()

    private fun consume(counter: kotlin.reflect.KMutableProperty0<Int>): Boolean {
        val left = counter.get()
        if (left > 0) counter.set(left - 1)
        return left > 0
    }

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
        openAppenders++
        return object : StorageAppender {
            override suspend fun append(bytes: ByteArray) {
                beforeAppend()
                if (failAppend || consume(::appendFailures)) {
                    // A torn write: half of the bytes reach the file before the failure.
                    files[path.value] = files.getValue(path.value) + bytes.copyOf(bytes.size / 2)
                    throw IOException("injected append failure")
                }
                files[path.value] = files.getValue(path.value) + bytes
            }

            override suspend fun sync() {
                beforeSync()
                if (failSync || consume(::syncFailures)) throw IOException("injected sync failure")
                syncCount++
            }

            override suspend fun close() {
                openAppenders--
            }
        }
    }

    override suspend fun list(directory: StoragePath): List<StoragePath> =
        files.keys
            .filter { it.substringBeforeLast('/', "") == directory.value }
            .sorted()
            .map(::StoragePath)

    override suspend fun delete(path: StoragePath): Boolean = files.remove(path.value) != null
}
