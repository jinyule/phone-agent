package dev.phoneagent.core.session

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.testing.InMemoryStoragePort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BlobStoreTest {
    private val storage = InMemoryStoragePort()
    private val blobs = SessionFiles(storage, StoragePath("sessions/s1")).blobs

    @Test
    fun `refs are the sha256 of the content`() {
        // SHA-256("abc"), FIPS 180-2 test vector.
        assertEquals(
            BlobRef("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            BlobStore.refOf("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `identical bytes are stored once`() =
        runTest {
            val bytes = "frame".encodeToByteArray()
            val ref = blobs.put(bytes)
            assertEquals(ref, blobs.put(bytes.copyOf()))
            assertEquals(1, storage.writes["sessions/s1/blobs/${ref.value}"])
            assertTrue(blobs.contains(ref))
            assertArrayEquals(bytes, blobs.get(ref))
            assertEquals(StoragePath("sessions/s1/blobs"), blobs.directory)
        }

    @Test
    fun `missing blobs are null and corrupted blobs are detected and replaced on put`() =
        runTest {
            val missing = BlobStore.refOf(byteArrayOf(1))
            assertNull(blobs.get(missing))
            assertFalse(blobs.contains(missing))

            val bytes = "frame".encodeToByteArray()
            val ref = blobs.put(bytes)
            storage.files["sessions/s1/blobs/${ref.value}"] = "garbage".encodeToByteArray()
            assertEquals(ref, assertThrows<BlobCorruptedException> { blobs.get(ref) }.ref)
            blobs.put(bytes)
            assertArrayEquals(bytes, blobs.get(ref))
        }
}
