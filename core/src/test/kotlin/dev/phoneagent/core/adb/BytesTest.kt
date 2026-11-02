package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class BytesTest {
    @Test
    fun `hex round-trips and rejects an odd number of digits`() {
        assertArrayEquals(byteArrayOf(0, 0x7f, -1, 0x10), hexBytes("007fFF10"))
        assertEquals("007fff10", byteArrayOf(0, 0x7f, -1, 0x10).toHex())
        assertEquals("", ByteArray(0).toHex())
        assertThrows<IllegalArgumentException> { hexBytes("abc") }
    }

    @Test
    fun `little-endian integers are padded to their size and must fit`() {
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0, 0), toLittleEndian(BigInteger.valueOf(0x1234), 4))
        assertArrayEquals(byteArrayOf(-1, -1), toLittleEndian(BigInteger.valueOf(0xffff), 2))
        assertArrayEquals(ByteArray(3), toLittleEndian(BigInteger.ZERO, 3))
        assertEquals(BigInteger.valueOf(0xff1234), fromLittleEndian(byteArrayOf(0x34, 0x12, -1)))
        assertEquals(BigInteger.ZERO, fromLittleEndian(ByteArray(0)))
        assertThrows<IllegalArgumentException> { toLittleEndian(BigInteger.valueOf(0x10000), 2) }
        assertThrows<IllegalArgumentException> { toLittleEndian(BigInteger.valueOf(-1), 2) }
    }
}
