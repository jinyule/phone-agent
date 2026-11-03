package dev.phoneagent.core.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb's transport and shell framing as AOSP writes and reads it, for the fakes, written apart from
 * the client's AdbMessage and ShellProtocol so that a fake cannot share a mistake with them:
 * - `adb.h`: the `amessage` header and the A_* constants;
 * - `transport.cpp`: `send_packet` fills `data_check` only below A_VERSION_SKIP_CHECKSUM, with the
 *   version negotiated by the last CNXN; `read_packet` and `check_header` check the magic and the
 *   length against the max payload, never the checksum;
 * - `shell_protocol.h`: 1-byte id, 4-byte little-endian length, data.
 */
object Aosp {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453
    const val A_VERSION_MIN = 0x01000000
    const val A_VERSION_SKIP_CHECKSUM = 0x01000001
    const val A_VERSION = 0x01000001
    const val A_STLS_VERSION = 0x01000000
    const val MAX_PAYLOAD = 1024 * 1024
    const val MAX_PAYLOAD_V1 = 4 * 1024

    const val ID_STDIN = 0
    const val ID_STDOUT = 1
    const val ID_STDERR = 2
    const val ID_EXIT = 3
    const val ID_CLOSE_STDIN = 4
    const val SHELL_HEADER_SIZE = 5

    private const val HEADER_SIZE = 24

    /** A packet as `read_packet` hands it on, with the `data_check` it carried. */
    class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
        val dataCheck: Int,
    ) {
        override fun toString(): String =
            "Packet(${command.toUInt().toString(16)}, $arg0, $arg1, ${payload.size} bytes)"
    }

    /** The sum of the payload bytes (`calculate_apacket_checksum`). */
    fun sum(payload: ByteArray): Int = payload.sumOf { it.toInt() and 0xff }

    /** The bytes `send_packet` writes on a transport at [protocolVersion]. */
    fun encode(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray = ByteArray(0),
        protocolVersion: Int,
    ): ByteArray {
        val check = if (protocolVersion >= A_VERSION_SKIP_CHECKSUM) 0 else sum(payload)
        return frame(command, arg0, arg1, payload, check)
    }

    /** A packet with the given [dataCheck], right or wrong. */
    fun frame(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
        dataCheck: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(dataCheck)
            .putInt(command xor -1)
            .put(payload)
            .array()

    /**
     * The next packet, as `read_packet` reads it: `null` at the end of the stream before a header;
     * [IOException] for a packet cut short, a bad magic or a payload over [maxPayload].
     */
    fun read(
        input: InputStream,
        maxPayload: Int,
    ): Packet? {
        val data = DataInputStream(input)
        val first = data.read()
        if (first < 0) return null
        val header = ByteArray(HEADER_SIZE)
        header[0] = first.toByte()
        readFully(data, header, 1, "packet header")
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.getInt()
        val arg0 = fields.getInt()
        val arg1 = fields.getInt()
        val length = fields.getInt()
        val dataCheck = fields.getInt()
        val magic = fields.getInt()
        if (magic != command xor -1) broken("bad magic")
        if (length < 0 || length > maxPayload) broken("payload of ${length.toUInt()} bytes")
        val payload = ByteArray(length)
        readFully(data, payload, 0, "payload")
        return Packet(command, arg0, arg1, payload, dataCheck)
    }

    private fun readFully(
        data: DataInputStream,
        buffer: ByteArray,
        offset: Int,
        what: String,
    ) {
        try {
            data.readFully(buffer, offset, buffer.size - offset)
        } catch (cut: EOFException) {
            throw IOException("$what cut short", cut)
        }
    }

    private fun broken(message: String): Nothing = throw IOException(message)

    /** One shell protocol packet. */
    fun shellPacket(
        id: Int,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id)
        out.write(
            ByteBuffer
                .allocate(Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.size)
                .array(),
        )
        out.write(data)
        return out.toByteArray()
    }

    /**
     * The `features` value of a CNXN banner, parsed as `parse_banner` does: the whole payload as a
     * string (a NUL stays part of it), split on `:`, the third piece split on `;` and `=`.
     */
    fun bannerFeatures(payload: ByteArray): String? {
        val pieces = payload.toString(Charsets.ISO_8859_1).split(':')
        if (pieces.size <= 2) return null
        return pieces[2]
            .split(';')
            .map { it.split('=') }
            .lastOrNull { it.size == 2 && it[0] == "features" }
            ?.get(1)
    }
}
