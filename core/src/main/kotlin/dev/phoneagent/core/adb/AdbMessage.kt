/*
 * Copyright 2026 wangzan101 (Mika), licensed under the Apache License, Version 2.0.
 * Origin: Mika commit 9cef375, core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbMessage.kt.
 * Modified by PhoneAgent contributors: English documentation; truncation, oversize and length
 * overflow are typed protocol errors; payloads never appear in toString; the checksum is checked
 * by the sender's version as AOSP's send_packet fills it (Mika checks it on request only).
 */
package dev.phoneagent.core.adb

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The peer broke the adb or pairing wire format; the connection cannot be used further. */
internal open class AdbProtocolException(
    message: String,
) : IOException(message)

/** The stream ended inside a frame (the peer went away mid-write). */
internal class TruncatedFrameException(
    message: String,
) : AdbProtocolException(message)

/**
 * One adb transport packet: a 24-byte little-endian header (command, arg0, arg1, payload length,
 * payload checksum, command XOR 0xffffffff) and the payload. The checksum is the sum of the payload
 * bytes; AOSP's `send_packet` fills it only below [VERSION_SKIP_CHECKSUM] and writes 0 from that
 * version on, CNXN included ([read] says how it is checked). [toString] never shows the payload,
 * which may carry a command or its input.
 */
internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    fun encode(): ByteArray =
        ByteBuffer
            .allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(checksum(payload))
            .putInt(command.inv())
            .put(payload)
            .array()

    override fun toString(): String = "${name(command)}($arg0, $arg1, ${payload.size} bytes)"

    companion object {
        const val CNXN: Int = 0x4e584e43
        const val AUTH: Int = 0x48545541
        const val OPEN: Int = 0x4e45504f
        const val OKAY: Int = 0x59414b4f
        const val CLSE: Int = 0x45534c43
        const val WRTE: Int = 0x45545257
        const val STLS: Int = 0x534c5453

        /** Protocol version we speak; from this version on, checksums are not verified. */
        const val VERSION: Int = 0x01000001
        const val VERSION_SKIP_CHECKSUM: Int = 0x01000001
        const val VERSION_MIN: Int = 0x01000000
        const val STLS_VERSION: Int = 0x01000000

        /** Largest payload we accept and announce (adb's MAX_PAYLOAD). */
        const val MAX_PAYLOAD: Int = 1024 * 1024

        /** Payload limit before the peer's CNXN tells us its own (adb's MAX_PAYLOAD_V1). */
        const val MAX_PAYLOAD_V1: Int = 4 * 1024
        const val HEADER_SIZE: Int = 24
        private const val BYTE_MASK = 0xff
        private const val COMMAND_BYTES = 4
        private const val HEX_RADIX = 16
        private val EMPTY = ByteArray(0)

        fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and BYTE_MASK }

        /** The four ASCII letters of [command], or its hex value when it is not one of those. */
        fun name(command: Int): String {
            val bytes = ByteArray(COMMAND_BYTES) { (command ushr (Byte.SIZE_BITS * it)).toByte() }
            val letters = bytes.toString(Charsets.US_ASCII)
            return if (letters.all { it in 'A'..'Z' }) letters else "0x" + command.toUInt().toString(HEX_RADIX)
        }

        /**
         * The next packet from [input], or `null` when the stream ends before a header starts.
         * [senderVersion] is the protocol version the peer sends at, `null` before its CNXN told
         * us. The checksum must match when that version is below [VERSION_SKIP_CHECKSUM] and is
         * not checked from it on. A CNXN tells the version itself: its sender used the lower of its
         * own version (the CNXN's arg0) and ours. While the version is unknown, a checksum is
         * accepted when it is 0 (a current sender) or matches (an old one). Throws
         * [TruncatedFrameException] when the stream ends inside a packet, [AdbProtocolException]
         * for a bad magic, a payload over [maxPayload] or a checksum mismatch, and [IOException]
         * when reading fails.
         */
        @Throws(IOException::class)
        fun read(
            input: InputStream,
            maxPayload: Int,
            senderVersion: Int?,
        ): AdbMessage? {
            val header = readFully(input, HEADER_SIZE, "header") ?: return null
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.getInt()
            val arg0 = buffer.getInt()
            val arg1 = buffer.getInt()
            val length = buffer.getInt().toUInt().toLong()
            val sum = buffer.getInt()
            when {
                buffer.getInt() != command.inv() -> protocolError("bad magic for ${name(command)}")
                length > maxPayload -> protocolError("${name(command)} payload of $length bytes exceeds $maxPayload")
            }
            val payload =
                if (length == 0L) {
                    EMPTY
                } else {
                    readFully(input, length.toInt(), "payload")
                        ?: throw TruncatedFrameException("stream ended before the ${name(command)} payload")
                }
            val version = if (command == CNXN) minOf(arg0, VERSION) else senderVersion
            val valid =
                when {
                    version == null -> sum == 0 || sum == checksum(payload)
                    version < VERSION_SKIP_CHECKSUM -> sum == checksum(payload)
                    else -> true
                }
            if (!valid) protocolError("bad checksum for ${name(command)}")
            return AdbMessage(command, arg0, arg1, payload)
        }
    }
}

/** Throws [AdbProtocolException] with [message]. */
internal fun protocolError(message: String): Nothing = throw AdbProtocolException(message)

/**
 * Exactly [size] bytes from [input]; `null` when the stream ends before the first byte, and
 * [TruncatedFrameException] (naming [what]) when it ends after some of them.
 */
@Throws(IOException::class)
internal fun readFully(
    input: InputStream,
    size: Int,
    what: String,
): ByteArray? {
    val out = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = input.read(out, read, size - read)
        if (n < 0) {
            if (read == 0) return null
            throw TruncatedFrameException("stream ended inside the $what ($read of $size bytes)")
        }
        read += n
    }
    return out
}
