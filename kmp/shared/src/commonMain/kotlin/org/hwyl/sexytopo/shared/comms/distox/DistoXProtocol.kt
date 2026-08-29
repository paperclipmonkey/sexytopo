package org.hwyl.sexytopo.shared.comms.distox

import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.asBinaryString
import org.hwyl.sexytopo.shared.comms.putUint16LE
import org.hwyl.sexytopo.shared.comms.uint8
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.roundToInt

/**
 * The packet type, taken from the low six bits of the admin byte.
 *
 * Ported from the nested `DistoXProtocol.PacketType`. The remaining two bits of the admin byte are
 * flags, not type: bit 6 (0x40) is the distance overflow bit and bit 7 (0x80) is the sequence bit,
 * which is why the mask is 0x3F and not 0xFF.
 */
enum class DistoXPacketType(val signature: Int) {
    /** A survey shot: distance, azimuth, inclination, roll. */
    MEASUREMENT(0b00_0001),

    /** Raw accelerometer counts, sent first of each calibration pair. */
    CALIBRATION_ACCELERATION(0b00_0010),

    /** Raw magnetometer counts, sent second of each calibration pair. */
    CALIBRATION_MAGNETIC(0b00_0011),

    /** Reply to a 0x38 read or a 0x39 memory write. */
    READ_REPLY(0b11_1000),

    /** Anything else — including a zero admin byte, which is what the Java falls through to. */
    UNKNOWN(0b00_0000),
    ;

    companion object {
        fun of(adminByte: Byte): DistoXPacketType {
            val signature = adminByte.toInt() and DistoXProtocol.PACKET_TYPE_MASK
            return entries.firstOrNull { it.signature == signature } ?: UNKNOWN
        }

        fun of(packet: ByteArray): DistoXPacketType = of(packet[DistoXProtocol.ADMIN])
    }
}

/**
 * The DistoX wire protocol: an eight-byte inbound packet, a one-byte acknowledgement, a one-byte
 * command vocabulary and a seven-byte memory write.
 *
 * Ported from `comms/distox/DistoXProtocol`, `MeasurementProtocol`, `CalibrationProtocol`,
 * `CommandProtocol` and `WriteCalibrationProtocol`. Everything here is pure: the Java equivalents
 * read from a `DataInputStream`, but the parsing itself never touched Android.
 *
 * ### Packet layout
 * ```
 * byte 0  admin: bits 0-5 packet type, bit 6 distance overflow, bit 7 sequence
 * byte 1  distance,    low byte    | acceleration gx low  | magnetic mx low
 * byte 2  distance,    high byte   | acceleration gx high | magnetic mx high
 * byte 3  azimuth,     low byte    | acceleration gy low  | magnetic my low
 * byte 4  azimuth,     high byte   | acceleration gy high | magnetic my high
 * byte 5  inclination, low byte    | acceleration gz low  | magnetic mz low
 * byte 6  inclination, high byte   | acceleration gz high | magnetic mz high
 * byte 7  roll angle (unused by SexyTopo) | unused
 * ```
 * All 16-bit fields are little-endian (low byte at the lower index).
 */
object DistoXProtocol {

    /** Every inbound packet is exactly eight bytes; the Java uses `readFully(packet, 0, 8)`. */
    const val PACKET_SIZE = 8

    /** Index of the admin byte. */
    const val ADMIN = 0

    /** Low six bits of the admin byte carry the packet type. */
    const val PACKET_TYPE_MASK = 0b0011_1111

    /** Bit 7 of the admin byte: toggles per packet so retransmissions can be told apart. */
    const val SEQUENCE_BIT_MASK = 0b1000_0000

    /** The acknowledgement byte is this ORed with the packet's sequence bit: 0x55 or 0xD5. */
    const val ACKNOWLEDGEMENT_PACKET_BASE = 0b0101_0101

    /** Bit 6 of the admin byte: the 17th bit of the distance, worth 65536 mm. */
    const val DISTANCE_BIT_MASK = 0b0100_0000

    /** The device repeats a packet every 25 ms; the Java paces its own reads at 100 ms. */
    const val INTER_PACKET_DELAY_MS = 100

    /** How long `DistoXThread` waits between RFCOMM connection attempts. */
    const val WAIT_BETWEEN_CONNECTION_ATTEMPTS_MS = 5 * 1000

    const val DISTANCE_LOW_BYTE = 1
    const val DISTANCE_HIGH_BYTE = 2
    const val AZIMUTH_LOW_BYTE = 3
    const val AZIMUTH_HIGH_BYTE = 4
    const val INCLINATION_LOW_BYTE = 5
    const val INCLINATION_HIGH_BYTE = 6

    /** Roll about the shot axis. Read by the instrument, never used by SexyTopo. */
    const val ROLL_BYTE = 7

    const val ACCELERATION_GX_LOW_BYTE = 1
    const val ACCELERATION_GX_HIGH_BYTE = 2
    const val ACCELERATION_GY_LOW_BYTE = 3
    const val ACCELERATION_GY_HIGH_BYTE = 4
    const val ACCELERATION_GZ_LOW_BYTE = 5
    const val ACCELERATION_GZ_HIGH_BYTE = 6

    const val MAGNETIC_MX_LOW_BYTE = 1
    const val MAGNETIC_MX_HIGH_BYTE = 2
    const val MAGNETIC_MY_LOW_BYTE = 3
    const val MAGNETIC_MY_HIGH_BYTE = 4
    const val MAGNETIC_MZ_LOW_BYTE = 5
    const val MAGNETIC_MZ_HIGH_BYTE = 6

    /** Command byte that writes four bytes to a memory address. */
    const val WRITE_MEMORY_COMMAND: Byte = 0x39

    /** First address of the calibration coefficient block (see `DistoXBleManager.MemoryRange`). */
    const val CALIBRATION_COEFFICIENTS_ADDRESS = 0x8010

    /** Four coefficient bytes go out per 0x39 command. */
    const val CALIBRATION_WRITE_CHUNK = 4

    // ---------------------------------------------------------------------------------------
    // Acknowledgement
    // ---------------------------------------------------------------------------------------

    /**
     * An acknowledgement is a single byte: 0b01010101 with bit 7 copied from the packet being
     * acknowledged. So a packet with the sequence bit clear is acked with 0x55 and one with it set
     * with 0xD5. Until the ack arrives the device keeps resending the same packet every 25 ms.
     */
    fun acknowledgementByteFor(adminByte: Byte): Byte =
        ((adminByte.toInt() and SEQUENCE_BIT_MASK) or ACKNOWLEDGEMENT_PACKET_BASE).toByte()

    /** The one-byte acknowledgement frame for [packet]. Java: `createAcknowledgementPacket`. */
    fun createAcknowledgementPacket(packet: ByteArray): ByteArray =
        byteArrayOf(acknowledgementByteFor(packet[ADMIN]))

    /** Whether [packet]'s sequence bit is set. */
    fun hasSequenceBit(packet: ByteArray): Boolean =
        (packet[ADMIN].toInt() and SEQUENCE_BIT_MASK) != 0

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /**
     * Reads an unsigned 16-bit field from a low/high index pair (0..65535).
     * Java: `DistoXProtocol.readDoubleByte`.
     */
    fun readDoubleByte(packet: ByteArray, lowByteIndex: Int, highByteIndex: Int): Int =
        (packet.uint8(highByteIndex) * 256) + packet.uint8(lowByteIndex)

    /**
     * Reads a *signed* 16-bit calibration field. Java: `CalibrationProtocol.readDoubleByte`.
     *
     * Note the boundary: the Java folds only when the value is strictly greater than 32768, so
     * 0x8000 reads back as +32768 rather than the two's-complement -32768, and the negative range
     * is -1..-32767. This is off by one from a normal signed short and is reproduced deliberately
     * — the calibration solver was tuned against these numbers.
     */
    fun readSignedDoubleByte(packet: ByteArray, lowByteIndex: Int, highByteIndex: Int): Int {
        val combined = readDoubleByte(packet, lowByteIndex, highByteIndex)
        return if (combined > 32768) combined - 65536 else combined
    }

    /**
     * Decodes a measurement packet into a [Leg]. Java: `MeasurementProtocol.parseDataPacket`.
     *
     * - **distance**: 17 bits in millimetres. Bits 0-15 are bytes 1-2 little-endian; bit 16 lives
     *   in bit 6 of the admin byte. The Java masks that bit *without shifting it down* and then
     *   multiplies by 1024, so the term is 0 or 64 * 1024 = 65536 — correct, but only because
     *   64 * 1024 happens to equal 2^16. Reproduced verbatim.
     * - **azimuth**: an unsigned 16-bit fraction of a full turn, scaled by 180/32768 (equivalently
     *   360/65536), giving 0.0055 degrees per count over 0..359.995.
     * - **inclination**: a signed 16-bit angle scaled by 90/16384 (0.0055 degrees per count).
     *   Values of 32768 and above are negative: the Java computes `(65536 - reading) * -90/16384`.
     *   Note this branch tests the *reading*, so -0.0 is unreachable and reading 32768 maps to
     *   -180 degrees — impossible from real hardware, and [Leg] would reject it.
     */
    fun parseMeasurement(packet: ByteArray): Leg {
        val distanceOverflow = packet[ADMIN].toInt() and DISTANCE_BIT_MASK
        val distanceLow = packet.uint8(DISTANCE_LOW_BYTE)
        val distanceHigh = packet.uint8(DISTANCE_HIGH_BYTE)
        val distance = (distanceOverflow * 1024 + distanceHigh * 256 + distanceLow) / 1000.0f

        val azimuthReading = readDoubleByte(packet, AZIMUTH_LOW_BYTE, AZIMUTH_HIGH_BYTE).toFloat()
        val azimuth = azimuthReading * 180.0f / 32768.0f

        val inclinationReading =
            readDoubleByte(packet, INCLINATION_LOW_BYTE, INCLINATION_HIGH_BYTE).toFloat()
        val inclination =
            if (inclinationReading >= 32768) {
                (65536 - inclinationReading) * -90.0f / 16384.0f
            } else {
                inclinationReading * 90.0f / 16384.0f
            }

        return Leg(distance, azimuth, inclination)
    }

    /** The roll angle byte, which SexyTopo reads but does not use. */
    fun parseRollByte(packet: ByteArray): Int = packet.uint8(ROLL_BYTE)

    /** Java: `CalibrationProtocol.updateAccelerationSensorReading`. */
    fun parseAcceleration(packet: ByteArray): InstrumentPacket.Acceleration =
        InstrumentPacket.Acceleration(
            gx = readSignedDoubleByte(packet, ACCELERATION_GX_LOW_BYTE, ACCELERATION_GX_HIGH_BYTE),
            gy = readSignedDoubleByte(packet, ACCELERATION_GY_LOW_BYTE, ACCELERATION_GY_HIGH_BYTE),
            gz = readSignedDoubleByte(packet, ACCELERATION_GZ_LOW_BYTE, ACCELERATION_GZ_HIGH_BYTE),
        )

    /** Java: `CalibrationProtocol.updateMagneticSensorReading`. */
    fun parseMagnetic(packet: ByteArray): InstrumentPacket.Magnetic =
        InstrumentPacket.Magnetic(
            mx = readSignedDoubleByte(packet, MAGNETIC_MX_LOW_BYTE, MAGNETIC_MX_HIGH_BYTE),
            my = readSignedDoubleByte(packet, MAGNETIC_MY_LOW_BYTE, MAGNETIC_MY_HIGH_BYTE),
            mz = readSignedDoubleByte(packet, MAGNETIC_MZ_LOW_BYTE, MAGNETIC_MZ_HIGH_BYTE),
        )

    /** True for a measurement packet. Java: `DistoXProtocol.isDataPacket`. */
    fun isDataPacket(packet: ByteArray): Boolean =
        DistoXPacketType.of(packet) == DistoXPacketType.MEASUREMENT

    /** Log rendering: admin byte in binary, the rest as signed decimals. Java: `describePacket`. */
    fun describePacket(packet: ByteArray): String = buildString {
        append("[")
        packet.forEachIndexed { index, byte ->
            if (index == ADMIN) append(asBinaryString(byte)) else append(",\t").append(byte)
        }
        append("]")
        append(" (").append(DistoXPacketType.of(packet)).append(")")
    }

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Encodes a [Leg] as an eight-byte measurement packet — the exact inverse of
     * [parseMeasurement]. Nothing in the Android app does this; it exists so that
     * [org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument] and the tests can drive the real
     * decoder with real packets instead of stubbing it out.
     *
     * @param sequenceBit sets admin bit 7, so a simulator can alternate it as a device would.
     * @param rollByte byte 7; the decoder ignores it.
     */
    fun encodeMeasurement(leg: Leg, sequenceBit: Boolean = false, rollByte: Int = 0): ByteArray {
        val millimetres = (leg.distance * 1000f).roundToInt()
        require(millimetres in 0..131071) {
            "A DistoX packet holds 17 bits of millimetres (max 131.071 m); got ${leg.distance} m"
        }
        require(leg.inclination in -90f..90f) {
            "A DistoX reports inclination in -90..90; got ${leg.inclination}"
        }

        val overflow = millimetres >= 65536
        val distanceRemainder = if (overflow) millimetres - 65536 else millimetres

        // Clamped because an azimuth a hair under 360 rounds up to 65536, which will not fit.
        val azimuthReading = (leg.azimuth * 32768f / 180f).roundToInt().coerceIn(0, 65535)
        val inclinationMagnitude =
            (kotlin.math.abs(leg.inclination) * 16384f / 90f).roundToInt().coerceIn(0, 16384)
        val inclinationReading =
            if (leg.inclination < 0f) 65536 - inclinationMagnitude else inclinationMagnitude

        val packet = ByteArray(PACKET_SIZE)
        packet[ADMIN] =
            (
                DistoXPacketType.MEASUREMENT.signature or
                    (if (overflow) DISTANCE_BIT_MASK else 0) or
                    (if (sequenceBit) SEQUENCE_BIT_MASK else 0)
                ).toByte()
        packet.putUint16LE(DISTANCE_LOW_BYTE, distanceRemainder)
        packet.putUint16LE(AZIMUTH_LOW_BYTE, azimuthReading)
        packet.putUint16LE(INCLINATION_LOW_BYTE, inclinationReading and 0xFFFF)
        packet[ROLL_BYTE] = (rollByte and 0xFF).toByte()
        return packet
    }

    /**
     * Encodes a calibration packet pair. Signed counts are folded back with plain two's
     * complement, which round-trips through [readSignedDoubleByte] for every value except exactly
     * -32768 (see that function's note).
     */
    fun encodeCalibration(
        acceleration: InstrumentPacket.Acceleration,
        magnetic: InstrumentPacket.Magnetic,
        sequenceBit: Boolean = false,
    ): Pair<ByteArray, ByteArray> {
        fun packetOf(type: DistoXPacketType, x: Int, y: Int, z: Int): ByteArray {
            val packet = ByteArray(PACKET_SIZE)
            packet[ADMIN] =
                (type.signature or (if (sequenceBit) SEQUENCE_BIT_MASK else 0)).toByte()
            packet.putUint16LE(1, x and 0xFFFF)
            packet.putUint16LE(3, y and 0xFFFF)
            packet.putUint16LE(5, z and 0xFFFF)
            return packet
        }
        return packetOf(
            DistoXPacketType.CALIBRATION_ACCELERATION,
            acceleration.gx,
            acceleration.gy,
            acceleration.gz,
        ) to
            packetOf(
                DistoXPacketType.CALIBRATION_MAGNETIC,
                magnetic.mx,
                magnetic.my,
                magnetic.mz,
            )
    }

    /**
     * Builds the sequence of seven-byte memory writes that store calibration coefficients on a
     * classic DistoX. Java: `WriteCalibrationProtocol.go`.
     *
     * Each command is `[0x39, addressLow, addressHigh, b0, b1, b2, b3]` and the address advances
     * by four. The address is written low byte first even though it is a memory address rather
     * than a measurement, and the Java writes `(byte) address` for the low byte without masking —
     * the narrowing cast has the same effect.
     *
     * The Java loops `i += 4` with no length check, so a coefficient array whose length is not a
     * multiple of four dies with an out-of-bounds exception inside its own try/catch and reports
     * failure. Here it is rejected up front instead.
     */
    fun createWriteCalibrationCommands(
        coefficients: ByteArray,
        startAddress: Int = CALIBRATION_COEFFICIENTS_ADDRESS,
    ): List<ByteArray> {
        require(coefficients.size % CALIBRATION_WRITE_CHUNK == 0) {
            "Calibration coefficients are written four at a time; got ${coefficients.size}"
        }
        return coefficients.indices.step(CALIBRATION_WRITE_CHUNK).map { offset ->
            val address = startAddress + offset
            byteArrayOf(
                WRITE_MEMORY_COMMAND,
                (address and 0xFF).toByte(),
                ((address shr 8) and 0xFF).toByte(),
                coefficients[offset],
                coefficients[offset + 1],
                coefficients[offset + 2],
                coefficients[offset + 3],
            )
        }
    }

    /** The address a write command in [createWriteCalibrationCommands] targets. */
    fun addressOfWriteCommand(command: ByteArray): Int =
        command.uint8(1) or (command.uint8(2) shl 8)

    /**
     * Whether the device accepted a calibration write. Java:
     * `WriteCalibrationProtocol.checkCalibrationReply`.
     *
     * The reply is a full eight-byte packet whose admin byte is 0x38 (READ_REPLY) and whose next
     * two bytes echo the address just written. Everything after that is ignored.
     */
    fun isCalibrationWriteReplyValid(address: Int, reply: ByteArray): Boolean =
        reply.size >= 3 &&
            reply[0] == DistoXPacketType.READ_REPLY.signature.toByte() &&
            reply[1] == (address and 0xFF).toByte() &&
            reply[2] == ((address shr 8) and 0xFF).toByte()
}
