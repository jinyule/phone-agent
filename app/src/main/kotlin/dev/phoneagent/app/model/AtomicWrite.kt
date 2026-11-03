package dev.phoneagent.app.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces [file] with [bytes] or throws [IOException], leaving the old content in place: the bytes
 * go to `<name>.tmp` next to it, are synced, then moved over it atomically. A crash leaves at most
 * the `.tmp` file, which the next write replaces. Not `android.util.AtomicFile`: its `finishWrite`
 * only logs a failed rename, so a save that did not happen would look done.
 */
internal fun writeAtomically(
    file: File,
    bytes: ByteArray,
) {
    val parent = checkNotNull(file.absoluteFile.parentFile) { "a file to replace has a directory" }
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create ${parent.name}")
    val temp = File(parent, file.name + TEMP_SUFFIX)
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    try {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: AtomicMoveNotSupportedException) {
        // Same directory, so this only happens on exotic file systems: never fall back to a non-atomic copy.
        temp.delete()
        throw e
    }
}

/** What a [writeAtomically] interrupted by a crash leaves next to the file. */
internal const val TEMP_SUFFIX: String = ".tmp"
