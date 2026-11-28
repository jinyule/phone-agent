package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ShellProtocolTest {
    @Test
    fun `packets are an id, a little-endian length and the data`() {
        assertEquals("0103000000616263", ShellProtocol.packet(ShellProtocol.STDOUT, "abc".toByteArray()).toHex())
        assertEquals("0400000000", ShellProtocol.stdinStream(ByteArray(0)).toHex())
        assertEquals("00020000006869" + "0400000000", ShellProtocol.stdinStream("hi".toByteArray()).toHex())
    }

    @Test
    fun `collects stdout, stderr and the exit status`() {
        val stream = shellStream("out\n", "err\n", 3)
        val whole = OutputCollector.Shell(100)
        whole.feed(stream)
        assertEquals(listOf<Any?>("out\n", "err\n", 3), listOf(whole.stdoutText(), whole.stderrText(), whole.exitCode))
    }

    @Test
    fun `packets may be split anywhere across WRTE payloads`() {
        val stream = shellStream("ünïcode out", "and err", 255)
        for (size in 1..stream.size) {
            val collector = OutputCollector.Shell(100)
            stream.toList().chunked(size).forEachIndexed { index, chunk ->
                assertNull(collector.exitCode, "no status before the last piece (piece $index)")
                collector.feed(chunk.toByteArray())
            }
            assertEquals("ünïcode out", collector.stdoutText(), "pieces of $size")
            assertEquals("and err", collector.stderrText())
            assertEquals(255, collector.exitCode)
        }
    }

    @Test
    fun `empty packets and unknown ids carry nothing`() {
        val collector = OutputCollector.Shell(10)
        collector.feed(ShellProtocol.packet(ShellProtocol.STDOUT, ByteArray(0)))
        collector.feed(ShellProtocol.packet(5, ByteArray(40)))
        collector.feed(ShellProtocol.packet(ShellProtocol.STDOUT, "x".toByteArray()) + exit(0))
        assertEquals("x", collector.stdoutText())
        assertEquals(0, collector.exitCode)
    }

    @Test
    fun `a malformed exit status or data after it is a protocol error`() {
        for (length in listOf(0, 2, 4)) {
            assertThrows<AdbProtocolException> {
                OutputCollector.Shell(10).feed(ShellProtocol.packet(ShellProtocol.EXIT, ByteArray(length)))
            }
        }
        val after = OutputCollector.Shell(10)
        assertThrows<AdbProtocolException> {
            after.feed(exit(0) + ShellProtocol.packet(ShellProtocol.STDOUT, byteArrayOf(1)))
        }
        val later = OutputCollector.Shell(10)
        later.feed(exit(1))
        assertThrows<AdbProtocolException> { later.feed(byteArrayOf(1)) }
    }

    @Test
    fun `stdout and stderr together are limited`() {
        val exact = OutputCollector.Shell(6)
        exact.feed(shellStream("abc", "def", 0))
        assertEquals("abcdef", exact.stdoutText() + exact.stderrText())
        val over = OutputCollector.Shell(6)
        assertThrows<OutputTooLargeException> { over.feed(shellStream("abc", "defg", 0)) }
        val raw = OutputCollector.Raw(3)
        raw.feed("ab".toByteArray())
        assertThrows<OutputTooLargeException> { raw.feed("cd".toByteArray()) }
    }

    @Test
    fun `raw output is stdout only and has no exit status`() {
        val raw = OutputCollector.Raw(100)
        raw.feed(ShellProtocol.packet(ShellProtocol.STDERR, ByteArray(0)).copyOf(1))
        raw.feed("plain".toByteArray())
        assertEquals("\u0002plain", raw.stdoutText())
        assertEquals("", raw.stderrText())
        assertNull(raw.exitCode)
    }

    private fun exit(code: Int) = ShellProtocol.packet(ShellProtocol.EXIT, byteArrayOf(code.toByte()))

    private fun shellStream(
        stdout: String,
        stderr: String,
        code: Int,
    ): ByteArray =
        ShellProtocol.packet(ShellProtocol.STDOUT, stdout.toByteArray()) +
            ShellProtocol.packet(ShellProtocol.STDERR, stderr.toByteArray()) +
            exit(code)
}
