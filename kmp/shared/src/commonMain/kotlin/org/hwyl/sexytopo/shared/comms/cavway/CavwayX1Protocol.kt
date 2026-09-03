package org.hwyl.sexytopo.shared.comms.cavway

import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.ShotDetail
import org.hwyl.sexytopo.shared.comms.uint8
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * The Cavway X1 BLE protocol. Ported from `comms/cavwayx1/CavwayX1Manager.DataHandler`.
 *
 * The X1 reuses the Nordic UART service and the `data:` framing of DistoX BLE (see
 * [org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming]) and the same single-byte command
 * vocabulary, but its measurement packet is its own: 24-bit millimetres split across three
 * non-adjacent bytes, and three angles as unsigned 16-bit fractions of a full turn.
 */
object CavwayX1Protocol {

    const val PACKET_TYPE_NORMAL: Byte = 0x01
    const val PACKET_TYPE_CALIBRATION: Byte = 0x02

    /**
     * `360.0 / 0xFFFF`, as the Java writes it — note the denominator is 65535, not 65536, so the
     * top count maps to exactly 360 degrees rather than one count short of it. That differs from
     * the DistoX scaling (180/32768 == 360/65536) and means a reading of 0xFFFF produces an
     * azimuth of 360.0, which [Leg] rejects as out of range.
     */
    const val ANGLE_SCALE: Float = 360.0f / 65535.0f

    const val MIN_PACKET_LENGTH = 64

    const val PACKET_TYPE_INDEX = 0
    const val FLAGS_INDEX = 1
    const val AZIMUTH_INDEX = 5
    const val INCLINATION_INDEX = 7
    const val ROLL_INDEX = 9

    /** Calibration packets are recognised and acknowledged but not decoded, exactly as in the Java. */
    fun decode(packet: ByteArray): InstrumentPacket? {
        if (packet.size < MIN_PACKET_LENGTH) return null

        return when (packet[PACKET_TYPE_INDEX]) {
            PACKET_TYPE_NORMAL ->
                InstrumentPacket.Measurement(
                    parseMeasurement(packet),
                    ShotDetail(roll = parseAngle(packet, ROLL_INDEX)),
                )

            PACKET_TYPE_CALIBRATION -> null

            else -> InstrumentPacket.Unrecognised(packet)
        }
    }

    /**
     * Distance is 24 bits of millimetres, but stored out of order: byte 2 holds bits 16-23,
     * byte 3 bits 0-7 and byte 4 bits 8-15. Reproduced exactly, oddity and all.
     */
    fun parseMeasurement(packet: ByteArray): Leg {
        val high = packet.uint8(2) shl 16
        val mid = packet.uint8(4) shl 8
        val low = packet.uint8(3)
        val distance = (high or mid or low) / 1000.0f
        return Leg(
            distance,
            parseAngle(packet, AZIMUTH_INDEX),
            parseAngle(packet, INCLINATION_INDEX),
        )
    }

    fun parseAngle(packet: ByteArray, startIndex: Int): Float {
        val combined = (packet.uint8(startIndex + 1) shl 8) or packet.uint8(startIndex)
        return combined * ANGLE_SCALE
    }

    /**
     * The acknowledgement byte, from the flags byte at index 1.
     *
     * The Java is `(byte) (flags | 0x55)` — it ORs the *whole* flags byte, not just its sequence
     * bit, so anything else set in the flags leaks into the acknowledgement. DistoX BLE, which
     * this protocol otherwise copies, masks with 0x80 first. Preserved as written.
     */
    fun acknowledgementByte(flags: Byte): Byte = (flags.toInt() or 0x55).toByte()
}
