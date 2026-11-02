package dev.phoneagent.core.adb

import java.math.BigInteger

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xff
private const val NIBBLE_BITS = 4

/** Bytes of a hex string with an even number of digits (constants and test vectors only). */
internal fun hexBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
}

/** Lowercase hex of these bytes. */
internal fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val v = byte.toInt() and BYTE_MASK
        "${Character.forDigit(v shr NIBBLE_BITS, HEX_RADIX)}${Character.forDigit(v and 0xf, HEX_RADIX)}"
    }

/** The non-negative integer these bytes encode, least significant byte first. */
internal fun fromLittleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

/** [value] (`>= 0`) as exactly [size] bytes, least significant first; fails when it does not fit. */
internal fun toLittleEndian(
    value: BigInteger,
    size: Int,
): ByteArray {
    require(value.signum() >= 0 && value.bitLength() <= size * Byte.SIZE_BITS) { "value does not fit in $size bytes" }
    val bigEndian = value.toByteArray()
    return ByteArray(size) { i -> if (i < bigEndian.size) bigEndian[bigEndian.size - 1 - i] else 0 }
}
