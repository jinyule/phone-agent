package dev.phoneagent.core.session

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.StoragePort
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Content-addressed blob storage of one session (phase1 §3.9 rule 2): `blobs/<sha256>`, no
 * extension (the referencing event records the mime type). Identical bytes are stored once.
 * Every method may throw [IOException] from the storage port.
 */
public class BlobStore(
    private val storage: StoragePort,
    /** Directory holding the blobs, normally `<session dir>/blobs`. */
    public val directory: StoragePath,
) {
    /**
     * Stores [bytes] durably and returns their reference. Writes nothing when an intact copy
     * exists; replaces a corrupted one.
     */
    public suspend fun put(bytes: ByteArray): BlobRef {
        val ref = refOf(bytes)
        val path = pathOf(ref)
        val existing = storage.read(path)
        if (existing == null || refOf(existing) != ref) storage.writeAtomically(path, bytes)
        return ref
    }

    /** The bytes of [ref], or `null` when absent. Throws [BlobCorruptedException] when they no longer hash to [ref]. */
    public suspend fun get(ref: BlobRef): ByteArray? {
        val bytes = storage.read(pathOf(ref)) ?: return null
        if (refOf(bytes) != ref) throw BlobCorruptedException(ref)
        return bytes
    }

    /** Whether a blob file for [ref] exists (not verified). */
    public suspend fun contains(ref: BlobRef): Boolean = storage.exists(pathOf(ref))

    private fun pathOf(ref: BlobRef): StoragePath = directory.resolve(ref.value)

    /** Hashing. */
    public companion object {
        /** The reference [bytes] would get. */
        public fun refOf(bytes: ByteArray): BlobRef {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return BlobRef(digest.joinToString("") { "%02x".format(Locale.ROOT, it) })
        }
    }
}

/** A stored blob no longer matches its hash. */
public class BlobCorruptedException(
    /** The affected blob. */
    public val ref: BlobRef,
) : IOException("blob $ref is corrupted")
