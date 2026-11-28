package dev.phoneagent.core.adb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb's shell protocol (`shell,v2`, AOSP `shell_protocol.h`): a byte stream of packets, each a
 * 1-byte id, a 4-byte little-endian length and the data. Packets may span adb WRTE payloads.
 */
internal object ShellProtocol {
    const val STDIN: Int = 0
    const val STDOUT: Int = 1
    const val STDERR: Int = 2
    const val EXIT: Int = 3
    const val CLOSE_STDIN: Int = 4
    const val HEADER_SIZE: Int = 5

    /** [stdin] as one STDIN packet (none when empty) followed by CLOSE_STDIN. */
    fun stdinStream(stdin: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (stdin.isNotEmpty()) out.write(packet(STDIN, stdin))
        out.write(packet(CLOSE_STDIN, ByteArray(0)))
        return out.toByteArray()
    }

    fun packet(
        id: Int,
        data: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByte())
            .putInt(data.size)
            .put(data)
            .array()
}

/**
 * Collects a command's output: stdout, stderr and (for the shell protocol) the exit status,
 * failing with [OutputTooLargeException] once stdout and stderr together pass [limit] bytes.
 */
internal sealed class OutputCollector(
    private val limit: Int,
) {
    private val stdout = ByteArrayOutputStream()
    private val stderr = ByteArrayOutputStream()

    /** The exit status, once known. */
    abstract val exitCode: Int?

    /** Takes the payload of one WRTE. */
    abstract fun feed(data: ByteArray)

    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

    fun stderrText(): String = stderr.toString(Charsets.UTF_8)

    protected fun append(
        target: Int,
        data: ByteArray,
        from: Int,
        to: Int,
    ) {
        if (stdout.size() + stderr.size() + (to - from) > limit) throw OutputTooLargeException(limit)
        (if (target == ShellProtocol.STDERR) stderr else stdout).write(data, from, to - from)
    }

    /** `exec:`: the stream is the command's raw stdout and stderr; there is no exit status. */
    class Raw(
        limit: Int,
    ) : OutputCollector(limit) {
        override val exitCode: Int? = null

        override fun feed(data: ByteArray) = append(ShellProtocol.STDOUT, data, 0, data.size)
    }

    /** `shell,v2`: packets carry stdout, stderr and finally the exit status. */
    class Shell(
        limit: Int,
    ) : OutputCollector(limit) {
        private val header = ByteArray(ShellProtocol.HEADER_SIZE)
        private var headerRead = 0
        private var id = 0
        private var remaining = 0L
        private val exitBytes = ByteArrayOutputStream()

        override var exitCode: Int? = null
            private set

        override fun feed(data: ByteArray) {
            var at = 0
            while (at < data.size) {
                if (exitCode != null) throw AdbProtocolException("shell data after the exit status")
                at = if (headerRead < ShellProtocol.HEADER_SIZE) readHeader(data, at) else readData(data, at)
            }
        }

        private fun readHeader(
            data: ByteArray,
            from: Int,
        ): Int {
            val n = minOf(ShellProtocol.HEADER_SIZE - headerRead, data.size - from)
            data.copyInto(header, headerRead, from, from + n)
            headerRead += n
            if (headerRead == ShellProtocol.HEADER_SIZE) {
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                id = buffer.get().toInt() and BYTE_MASK
                remaining = buffer.getInt().toUInt().toLong()
                if (id == ShellProtocol.EXIT && remaining != 1L) protocolError("exit packet of $remaining bytes")
                if (remaining == 0L) headerRead = 0
            }
            return from + n
        }

        private fun readData(
            data: ByteArray,
            from: Int,
        ): Int {
            val n = minOf(remaining, (data.size - from).toLong()).toInt()
            when (id) {
                ShellProtocol.STDOUT, ShellProtocol.STDERR -> append(id, data, from, from + n)
                ShellProtocol.EXIT -> exitBytes.write(data, from, n)
                // Other ids (window size, future ones) carry nothing for us.
            }
            remaining -= n
            if (remaining == 0L) {
                headerRead = 0
                if (id == ShellProtocol.EXIT) exitCode = exitBytes.toByteArray()[0].toInt() and BYTE_MASK
            }
            return from + n
        }

        private companion object {
            const val BYTE_MASK = 0xff
        }
    }
}

/** A command printed more than the configured limit. */
internal class OutputTooLargeException(
    limit: Int,
) : AdbProtocolException("command output exceeds $limit bytes")
