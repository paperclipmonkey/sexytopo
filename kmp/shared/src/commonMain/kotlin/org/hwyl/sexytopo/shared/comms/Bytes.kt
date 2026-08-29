package org.hwyl.sexytopo.shared.comms

/**
 * Byte-level primitives for the instrument protocols, ported from
 * `org.hwyl.sexytopo.control.util.NumberTools` and the protected readers on
 * `org.hwyl.sexytopo.comms.distox.DistoXProtocol`.
 *
 * Instrument packets are little-endian throughout: every multi-byte field in every protocol
 * SexyTopo speaks (DistoX, DistoX BLE, Cavway X1, BRIC4, SAP6, FCL) puts its low byte first.
 *
 * Two faithfulness notes carried over from the Java:
 *  - `NumberTools.getUint16` and `getUint32` are misnamed: they call `ByteBuffer.getShort()` and
 *    `getInt()`, so they return **signed** values (0xFFFF reads back as -1, and `NumberToolsTest`
 *    asserts exactly that). [int16LE] and [int32LE] keep that behaviour; [uint16LE] is the
 *    genuinely unsigned reader that some call sites want.
 *  - [copyOfRangePadded] reproduces `java.util.Arrays.copyOfRange`, which zero-pads when the
 *    requested end runs past the source. `DistoXBleManager` relies on that when it slices a
 *    16-byte window out of a shorter BLE notification.
 */

/** Reads one byte as an unsigned 0..255 value. Java: `bytes[index] & 0xFF`. */
fun ByteArray.uint8(index: Int): Int = this[index].toInt() and 0xFF

/** Reads an unsigned little-endian 16-bit value (0..65535). */
fun ByteArray.uint16LE(offset: Int): Int = uint8(offset) or (uint8(offset + 1) shl 8)

/** Reads a signed little-endian 16-bit value (-32768..32767). Java: `NumberTools.getUint16`. */
fun ByteArray.int16LE(offset: Int): Int = uint16LE(offset).toShort().toInt()

/** Reads a signed little-endian 32-bit value. Java: `NumberTools.getUint32`. */
fun ByteArray.int32LE(offset: Int): Int =
    uint8(offset) or
        (uint8(offset + 1) shl 8) or
        (uint8(offset + 2) shl 16) or
        (uint8(offset + 3) shl 24)

/** Reads an IEEE-754 little-endian float. Java: `NumberTools.getFloat`. */
fun ByteArray.floatLE(offset: Int): Float = Float.fromBits(int32LE(offset))

/** Writes the low 16 bits of [value], little-endian. */
fun ByteArray.putUint16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

/** Writes [value] as 32 bits, little-endian. */
fun ByteArray.putInt32LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

/** Writes [value] as an IEEE-754 little-endian float. */
fun ByteArray.putFloatLE(offset: Int, value: Float) = putInt32LE(offset, value.toRawBits())

/**
 * Slices `[fromIndex, toIndex)`, zero-padding anything past the end of this array — the semantics
 * of `java.util.Arrays.copyOfRange`, which Kotlin's own `copyOfRange` does not share (it throws).
 */
fun ByteArray.copyOfRangePadded(fromIndex: Int, toIndex: Int): ByteArray {
    require(fromIndex in 0..size) { "from index $fromIndex out of bounds for size $size" }
    require(fromIndex <= toIndex) { "from index $fromIndex > to index $toIndex" }
    val result = ByteArray(toIndex - fromIndex)
    val available = minOf(toIndex, size) - fromIndex
    if (available > 0) {
        copyInto(result, destinationOffset = 0, startIndex = fromIndex, endIndex = fromIndex + available)
    }
    return result
}

/** Renders a packet as `[aa bb cc]` hex, for logs and test failure messages. */
fun ByteArray.toHex(): String = joinToString(separator = " ", prefix = "[", postfix = "]") { byte ->
    val value = byte.toInt() and 0xFF
    val digits = "0123456789abcdef"
    "${digits[value shr 4]}${digits[value and 0xF]}"
}

/**
 * Renders one byte as eight binary digits, as `DistoXProtocol.asBinaryString` does for device logs.
 * Hand-rolled because `String.format` is a JVM-only API.
 */
fun asBinaryString(byte: Byte): String {
    val value = byte.toInt() and 0xFF
    return buildString(8) {
        for (bit in 7 downTo 0) append(if ((value shr bit) and 1 == 1) '1' else '0')
    }
}
