package org.hwyl.sexytopo.shared.comms.distox

import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.copyOfRangePadded
import org.hwyl.sexytopo.shared.comms.uint8

/**
 * Named regions of DistoX memory, from `DistoXBleManager.MemoryRange`.
 *
 * Only the start address is ever put on the wire; `end` is documentation. Note that
 * [DATA_STORE]'s end is `0x7FFFF` in the Java — five hex digits, and so outside the 16-bit
 * address space every other member sits in. It is almost certainly a typo for 0x7FFF, but since
 * nothing reads it the value is carried across unchanged rather than silently corrected.
 */
enum class DistoXMemoryRange(val start: Int, val end: Int) {
    DATA_STORE(0x0000, 0x7FFFF),
    CALIBRATION_COEFFICIENTS(0x8010, 0x8043),
    FIRMWARE_VERSION(0xE000, 0xE003),
    HARDWARE_VERSION(0xE004, 0xE007),
    RAM(0xC000, 0xDFFF),
    ;

    val addressBytes: ByteArray
        get() = byteArrayOf((start and 0xFF).toByte(), ((start shr 8) and 0xFF).toByte())

    val sizeBytes: Int get() = end - start + 1
}

/**
 * Outbound framing for DistoX BLE (and Cavway X1, which copied it verbatim).
 *
 * Where the classic DistoX writes bare bytes down an RFCOMM socket, the BLE models expect every
 * write wrapped in a length-delimited frame on the Nordic UART write characteristic
 * (`6e400002-...`):
 * ```
 * "data:"  0x64 0x61 0x74 0x61 0x3a   five ASCII bytes
 * length   one byte, the payload length
 * payload  length bytes
 * CRLF     0x0d 0x0a
 * ```
 * so a frame is always `payload.length + 8` bytes long — which is what `DistoXBleManagerTest`
 * asserts for a single command byte (9).
 *
 * Ported from `comms/distoxble/DistoXBleManager`.
 */
object DistoXBleFraming {

    /** ASCII "data:". */
    val WRITE_HEADER = byteArrayOf(0x64, 0x61, 0x74, 0x61, 0x3a)

    /** CRLF. */
    val WRITE_FOOTER = byteArrayOf(0x0d, 0x0a)

    /** ASCII '>', which introduces a memory-write payload. */
    const val WRITE_MEMORY_PAYLOAD_HEADER: Byte = 0x3e

    /** Header + length byte + footer. */
    const val FRAME_OVERHEAD = 8

    fun createWritePacket(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) {
            "The length field is one byte; got a ${payload.size}-byte payload"
        }
        val packet = ByteArray(payload.size + FRAME_OVERHEAD)
        WRITE_HEADER.copyInto(packet, 0)
        packet[5] = payload.size.toByte()
        payload.copyInto(packet, 6)
        WRITE_FOOTER.copyInto(packet, 6 + payload.size)
        return packet
    }

    fun createWriteCommandPacket(command: Byte): ByteArray = createWritePacket(byteArrayOf(command))

    /**
     * A framed memory write. Java: `createWriteMemoryPacket` / `createWriteMemoryPayload`.
     *
     * The inner payload is `['>', addressLow, addressHigh, payloadLength, payload...]`, so the
     * length appears twice in the finished frame — once for the `data:` frame and once inside the
     * memory-write payload, four bytes further in.
     *
     * Unlike the classic DistoX, which dribbles calibration coefficients out four bytes at a time
     * and checks a reply after each (see [DistoXProtocol.createWriteCalibrationCommands]), the BLE
     * model takes the whole 52-byte coefficient block in one frame and sends no reply at all.
     */
    fun createWriteMemoryPacket(range: DistoXMemoryRange, payload: ByteArray): ByteArray =
        createWritePacket(createWriteMemoryPayload(range, payload))

    fun createWriteMemoryPayload(range: DistoXMemoryRange, payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) {
            "The length field is one byte; got a ${payload.size}-byte payload"
        }
        val result = ByteArray(payload.size + 4)
        result[0] = WRITE_MEMORY_PAYLOAD_HEADER
        range.addressBytes.copyInto(result, 1)
        result[3] = payload.size.toByte()
        payload.copyInto(result, 4)
        return result
    }

    /**
     * Unwraps a `data:` frame, or returns null if [frame] is not one. Not in the Java — the app
     * only ever writes these frames, never reads them — but a simulator has to understand what it
     * is being sent.
     */
    fun payloadOrNull(frame: ByteArray): ByteArray? {
        if (frame.size < FRAME_OVERHEAD) return null
        for (index in WRITE_HEADER.indices) {
            if (frame[index] != WRITE_HEADER[index]) return null
        }
        val length = frame.uint8(5)
        if (frame.size != length + FRAME_OVERHEAD) return null
        if (frame[6 + length] != WRITE_FOOTER[0] || frame[7 + length] != WRITE_FOOTER[1]) return null
        return frame.copyOfRange(6, 6 + length)
    }
}

/**
 * Inbound decoding for DistoX BLE. Ported from `DistoXBleManager.DataHandler`.
 *
 * A notification is one identifier byte followed by embedded classic-DistoX packets:
 * ```
 * measurement  0x01, then the eight-byte measurement packet at offset 1
 * calibration  0x02, then the acceleration packet at offset 1 and the magnetic packet at offset 9
 * ```
 * The BLE model therefore delivers a whole calibration reading in a single notification, where the
 * classic DistoX needs two round trips. Unlike the classic protocol there is no duplicate
 * suppression here at all.
 */
object DistoXBlePackets {

    const val MEASUREMENT_IDENTIFIER: Byte = 0x01
    const val CALIBRATION_IDENTIFIER: Byte = 0x02

    /** Where the embedded DistoX admin byte sits — one past the identifier. */
    const val EMBEDDED_ADMIN = 1

    /**
     * Decodes one notification, or returns [InstrumentPacket.Unrecognised] for an identifier this
     * protocol does not know. Empty frames return null, as the Java's two guard clauses do.
     *
     * The Java slices with `Arrays.copyOfRange(packet, 1, 16)`, which zero-pads a short
     * notification rather than throwing — so a truncated measurement decodes to a leg full of
     * zeros instead of failing. [copyOfRangePadded] keeps that behaviour.
     */
    fun decode(frame: ByteArray): InstrumentPacket? {
        if (frame.isEmpty()) return null

        return when (frame[0]) {
            MEASUREMENT_IDENTIFIER -> {
                val embedded = frame.copyOfRangePadded(1, 16)
                InstrumentPacket.Measurement(DistoXProtocol.parseMeasurement(embedded))
            }

            CALIBRATION_IDENTIFIER -> {
                val acceleration = DistoXProtocol.parseAcceleration(frame.copyOfRangePadded(1, 8))
                val magnetic = DistoXProtocol.parseMagnetic(frame.copyOfRangePadded(9, 16))
                InstrumentPacket.CalibrationReading(
                    acceleration.gx,
                    acceleration.gy,
                    acceleration.gz,
                    magnetic.mx,
                    magnetic.my,
                    magnetic.mz,
                )
            }

            else -> InstrumentPacket.Unrecognised(frame)
        }
    }

    /**
     * The acknowledgement to write back for [frame], already `data:`-framed.
     *
     * Java: `(byte) (packet[1] & 0x80 | 0x55)` — index 1, because the DistoX admin byte sits one
     * past the BLE identifier. `&` binds tighter than `|` in Java, so this is
     * `(packet[1] & 0x80) | 0x55`, the same 0x55/0xD5 pair as the classic protocol.
     */
    fun acknowledgementFor(frame: ByteArray): ByteArray =
        DistoXBleFraming.createWriteCommandPacket(
            DistoXProtocol.acknowledgementByteFor(frame[EMBEDDED_ADMIN]),
        )
}
