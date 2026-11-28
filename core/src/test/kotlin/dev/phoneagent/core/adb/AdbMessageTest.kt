package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessageTest {
    @Test
    fun `protocol constants are AOSP's, from adb_h and shell_protocol_h`() {
        val commands =
            listOf(
                AdbMessage.CNXN,
                AdbMessage.OPEN,
                AdbMessage.OKAY,
                AdbMessage.CLSE,
                AdbMessage.WRTE,
                AdbMessage.AUTH,
                AdbMessage.STLS,
            )
        assertEquals(
            listOf(0x4e584e43, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257, 0x48545541, 0x534C5453),
            commands,
        )
        val versions =
            listOf(
                AdbMessage.VERSION,
                AdbMessage.VERSION_MIN,
                AdbMessage.VERSION_SKIP_CHECKSUM,
                AdbMessage.STLS_VERSION,
            )
        assertEquals(listOf(0x01000001, 0x01000000, 0x01000001, 0x01000000), versions)
        assertEquals(
            listOf(1024 * 1024, 4 * 1024, 24),
            listOf(AdbMessage.MAX_PAYLOAD, AdbMessage.MAX_PAYLOAD_V1, AdbMessage.HEADER_SIZE),
        )
        val ids =
            listOf(
                ShellProtocol.STDIN,
                ShellProtocol.STDOUT,
                ShellProtocol.STDERR,
                ShellProtocol.EXIT,
                ShellProtocol.CLOSE_STDIN,
            )
        assertEquals(listOf(0, 1, 2, 3, 4), ids)
    }

    @Test
    fun `encodes the little-endian header with checksum and magic`() {
        val message = AdbMessage(AdbMessage.WRTE, 7, 9, byteArrayOf(1, 2, -1))
        val bytes = message.encode()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbMessage.WRTE, header.getInt())
        assertEquals(7, header.getInt())
        assertEquals(9, header.getInt())
        assertEquals(3, header.getInt())
        assertEquals(1 + 2 + 255, header.getInt())
        assertEquals(AdbMessage.WRTE.inv(), header.getInt())
        assertEquals("57525445", bytes.copyOf(4).toHex(), "command letters on the wire")
        assertArrayEquals(byteArrayOf(1, 2, -1), bytes.copyOfRange(24, 27))
    }

    @Test
    fun `reads back what it writes, also one byte at a time`() {
        val messages =
            listOf(
                AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::\u0000".toByteArray()),
                AdbMessage(AdbMessage.OKAY, 1, 2),
                AdbMessage(AdbMessage.WRTE, 3, 4, ByteArray(4096) { it.toByte() }),
            )
        val stream = ByteArrayOutputStream()
        messages.forEach { stream.write(it.encode()) }
        for (input in listOf(ByteArrayInputStream(stream.toByteArray()), Trickle(stream.toByteArray()))) {
            for (expected in messages) {
                val read = checkNotNull(AdbMessage.read(input, AdbMessage.MAX_PAYLOAD_V1, AdbMessage.VERSION_MIN))
                assertEquals(expected.toString(), read.toString())
                assertArrayEquals(expected.payload, read.payload)
            }
            assertNull(AdbMessage.read(input, AdbMessage.MAX_PAYLOAD_V1, AdbMessage.VERSION_MIN))
        }
    }

    @Test
    fun `a stream ending inside a packet is truncated`() {
        val bytes = AdbMessage(AdbMessage.WRTE, 1, 1, ByteArray(10)).encode()
        for (cut in listOf(1, 23, 24, 33)) {
            val failure =
                assertThrows<TruncatedFrameException> {
                    AdbMessage.read(ByteArrayInputStream(bytes.copyOf(cut)), 100, AdbMessage.VERSION)
                }
            assertFalse(failure.message.orEmpty().isEmpty())
        }
    }

    @Test
    fun `oversized payloads, bad magic and bad checksums are protocol errors`() {
        val big = AdbMessage(AdbMessage.WRTE, 1, 1, ByteArray(101)).encode()
        assertProtocolError("exceeds 100") { AdbMessage.read(ByteArrayInputStream(big), 100, AdbMessage.VERSION) }

        val huge = AdbMessage(AdbMessage.WRTE, 1, 1).encode()
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1)
        assertProtocolError("4294967295 bytes") {
            AdbMessage.read(ByteArrayInputStream(huge), AdbMessage.MAX_PAYLOAD, AdbMessage.VERSION)
        }

        val magic = AdbMessage(AdbMessage.OKAY, 1, 1).encode()
        magic[20] = 0
        assertProtocolError(
            "bad magic for OKAY",
        ) { AdbMessage.read(ByteArrayInputStream(magic), 100, AdbMessage.VERSION) }
    }

    @Test
    fun `the checksum is checked by the sender's version, as AOSP's send_packet fills it`() {
        val payload = byteArrayOf(5, 7)
        val right = Aosp.sum(payload)
        // Not a CNXN: the version negotiated for the connection decides; unknown takes 0 or the sum.
        val cases =
            listOf(
                Triple(Aosp.A_VERSION_MIN, right, true),
                Triple(Aosp.A_VERSION_MIN, 0, false),
                Triple(Aosp.A_VERSION_MIN, right + 1, false),
                Triple(Aosp.A_VERSION_SKIP_CHECKSUM, 0, true),
                Triple(Aosp.A_VERSION_SKIP_CHECKSUM, right + 1, true),
                Triple(null, 0, true),
                Triple(null, right, true),
                Triple(null, right + 1, false),
            )
        for ((version, dataCheck, accepted) in cases) {
            val bytes = Aosp.frame(Aosp.A_WRTE, 1, 2, payload, dataCheck)
            assertEquals(accepted, readsBack(bytes, version), "WRTE with data_check $dataCheck at version $version")
        }
    }

    @Test
    fun `a CNXN is checked by the version it carries, whatever was negotiated before`() {
        val banner = "device::features=shell_v2".toByteArray()
        val right = Aosp.sum(banner)
        val cases =
            listOf(
                Triple(Aosp.A_VERSION, 0, true),
                Triple(Aosp.A_VERSION, right + 1, true),
                Triple(0x02000000, 0, true),
                Triple(Aosp.A_VERSION_MIN, right, true),
                Triple(Aosp.A_VERSION_MIN, 0, false),
                Triple(Aosp.A_VERSION_MIN, right + 1, false),
            )
        for ((cnxnVersion, dataCheck, accepted) in cases) {
            val bytes = Aosp.frame(Aosp.A_CNXN, cnxnVersion, Aosp.MAX_PAYLOAD, banner, dataCheck)
            for (sender in listOf(null, Aosp.A_VERSION_MIN, Aosp.A_VERSION)) {
                assertEquals(accepted, readsBack(bytes, sender), "CNXN $cnxnVersion with $dataCheck after $sender")
            }
        }
    }

    /** Whether [bytes] read back as one packet at [senderVersion]; `false` for a bad checksum. */
    private fun readsBack(
        bytes: ByteArray,
        senderVersion: Int?,
    ): Boolean {
        val failure =
            runCatching { AdbMessage.read(ByteArrayInputStream(bytes), 100, senderVersion) }.exceptionOrNull()
        if (failure != null) assertEquals("bad checksum", failure.message?.substringBefore(" for "))
        return failure == null
    }

    @Test
    fun `names are the command letters or hex, and never show the payload`() {
        val names =
            listOf(
                AdbMessage.CNXN,
                AdbMessage.AUTH,
                AdbMessage.OPEN,
                AdbMessage.OKAY,
                AdbMessage.CLSE,
                AdbMessage.WRTE,
                AdbMessage.STLS,
            ).map(AdbMessage::name)
        assertEquals(listOf("CNXN", "AUTH", "OPEN", "OKAY", "CLSE", "WRTE", "STLS"), names)
        assertEquals("0xdeadbeef", AdbMessage.name(0xdeadbeef.toInt()))
        assertEquals(
            "OPEN(1, 0, 11 bytes)",
            AdbMessage(AdbMessage.OPEN, 1, 0, "exec:id\u0000abc".toByteArray()).toString(),
        )
    }

    private fun assertProtocolError(
        fragment: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows<AdbProtocolException>(block)
        assertEquals(AdbProtocolException::class.java, failure.javaClass)
        assertTrue(failure.message.orEmpty().contains(fragment)) { "${failure.message} lacks $fragment" }
    }

    /** Returns at most one byte per read, as a slow socket may. */
    private class Trickle(
        bytes: ByteArray,
    ) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)

        override fun read(): Int = inner.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = if (len == 0) 0 else inner.read(b, off, 1)
    }
}
