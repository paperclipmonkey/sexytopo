package org.hwyl.sexytopo.shared.comms.fcl

import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.ShotDetail
import org.hwyl.sexytopo.shared.comms.floatLE
import org.hwyl.sexytopo.shared.comms.int16LE
import org.hwyl.sexytopo.shared.comms.putFloatLE
import org.hwyl.sexytopo.shared.comms.putUint16LE
import org.hwyl.sexytopo.shared.comms.uint16LE
import org.hwyl.sexytopo.shared.comms.uint8
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.abs

/** Status bits in byte 2 of an FCL primary packet. */
object FclStatusFlags {
    const val VERTICAL_SHOT = 0x01
    const val HIGH_INTERFERENCE = 0x02
    const val LOW_BATTERY = 0x04
    const val TEMPERATURE_WARNING = 0x08
    const val POOR_SHOT_QUALITY = 0x10
    const val CALIBRATION_OLD = 0x20
    const val EXTENDED_DATA = 0x40
}

/** The 20-byte primary half of an FCL measurement. */
data class FclPrimaryPacket(
    val sequenceNumber: Int,
    val statusFlags: Int,
    val batteryLevel: Int,
    val azimuth: Float,
    val inclination: Float,
    val distance: Float,
    /** 0.0..1.0; transmitted as thousandths. */
    val shotQuality: Float,
    val protocolVersion: Int,
    /** CRC matched *and* all four measurements were in range. */
    val isValid: Boolean,
)

/** The 14-byte extended half: environment and identity. */
data class FclExtendedPacket(
    val currentMagneticField: Float,
    val expectedMagneticField: Float,
    val currentMagneticDip: Float,
    val expectedMagneticDip: Float,
    val temperature: Float,
    val rollAngle: Float,
    val measurementId: Int,
)

/** The two halves joined. Ported from `comms/fcl/FCLBLE.EnhancedLegData`. */
data class FclEnhancedLeg(
    val azimuth: Float,
    val inclination: Float,
    val distance: Float,
    /** 0.0 - 1.0 */
    val shotQuality: Float,
    /** microtesla, measured */
    val currentMagneticField: Float,
    /** microtesla, from the instrument's configuration */
    val expectedMagneticField: Float,
    /** degrees, measured */
    val currentMagneticDip: Float,
    /** degrees, from the instrument's configuration */
    val expectedMagneticDip: Float,
    /** degrees Celsius */
    val temperature: Float,
    /** degrees of rotation about the measurement axis */
    val rollAngle: Float,
    /** 0-100 percent */
    val batteryLevel: Int,
    /** the instrument's own sequential counter */
    val measurementId: Int,
    val statusFlags: Int,
    val sequenceNumber: Int,
    val protocolVersion: Int,
    val isValid: Boolean,
) {
    fun hasVerticalWarning(): Boolean = (statusFlags and FclStatusFlags.VERTICAL_SHOT) != 0

    fun hasInterferenceWarning(): Boolean = (statusFlags and FclStatusFlags.HIGH_INTERFERENCE) != 0

    fun hasLowBattery(): Boolean = (statusFlags and FclStatusFlags.LOW_BATTERY) != 0

    fun hasTemperatureWarning(): Boolean = (statusFlags and FclStatusFlags.TEMPERATURE_WARNING) != 0

    fun hasPoorQuality(): Boolean = (statusFlags and FclStatusFlags.POOR_SHOT_QUALITY) != 0

    fun hasOldCalibration(): Boolean = (statusFlags and FclStatusFlags.CALIBRATION_OLD) != 0

    fun hasExtendedData(): Boolean = (statusFlags and FclStatusFlags.EXTENDED_DATA) != 0

    fun qualityDescription(): String = when {
        shotQuality >= 0.9f -> "Excellent"
        shotQuality >= 0.8f -> "Good"
        shotQuality >= 0.7f -> "Fair"
        shotQuality >= 0.5f -> "Poor"
        else -> "Very Poor"
    }

    /**
     * How far the measured field is from what this part of the world should read. A large
     * deviation usually means iron nearby — a survey tripod, a scaffold bar, or ore in the rock —
     * and the azimuth from that shot should not be trusted.
     */
    fun magneticFieldDeviation(): Float = currentMagneticField - expectedMagneticField

    fun magneticFieldDescription(): String = describeAnomaly(magneticFieldDeviation())

    fun magneticDipDeviation(): Float = currentMagneticDip - expectedMagneticDip

    fun magneticDipDescription(): String = describeAnomaly(magneticDipDeviation())

    fun statusDescription(): String = when {
        hasLowBattery() -> "Low Battery"
        hasTemperatureWarning() -> "Temperature Warning"
        hasInterferenceWarning() -> "Magnetic Interference"
        hasVerticalWarning() -> "Vertical Shot"
        hasPoorQuality() -> "Poor Quality"
        hasOldCalibration() -> "Calibration Old"
        else -> "OK"
    }

    /** The survey leg, or null if the instrument reported something out of range. */
    fun toLegOrNull(): Leg? =
        try {
            Leg(distance, azimuth, inclination)
        } catch (exception: IllegalArgumentException) {
            null
        }

    /** The generic packet, carrying the quality and environment data as [ShotDetail]. */
    fun toPacketOrNull(): InstrumentPacket.Measurement? {
        val leg = toLegOrNull() ?: return null
        return InstrumentPacket.Measurement(
            leg,
            ShotDetail(
                reference = measurementId.toString(),
                roll = rollAngle,
                shotQuality = shotQuality,
                batteryPercent = batteryLevel,
                temperatureCelsius = temperature,
            ),
        )
    }

    private fun describeAnomaly(deviation: Float): String = when {
        abs(deviation) < 2.0f -> "Normal"
        abs(deviation) < 5.0f -> "Slight anomaly"
        abs(deviation) < 10.0f -> "Moderate anomaly"
        else -> "Significant anomaly"
    }
}

/**
 * The FCL "Enhanced Split Packet" protocol v2.0. Ported from `comms/fcl/FCLBLE.kt`.
 *
 * A BLE notification is limited by the negotiated MTU, so FCL splits each measurement across two
 * characteristics: a 20-byte primary packet with the measurement itself, and a 14-byte extended
 * packet with environment and identity data. The pair must arrive in that order.
 *
 * ### Primary packet (20 bytes, little-endian)
 * ```
 * 0-1   header       uint16: magic nibble (0xF) | version nibble (2) | sequence byte
 * 2     status flags uint8, see FclStatusFlags
 * 3     battery      uint8, percent
 * 4-7   azimuth      float, degrees
 * 8-11  inclination  float, degrees
 * 12-15 distance     float, metres
 * 16-17 shot quality uint16, thousandths
 * 18-19 CRC-16/CCITT over bytes 0..17
 * ```
 *
 * ### Extended packet (14 bytes, little-endian)
 * ```
 * 0-1   current magnetic field   uint16, tenths of a microtesla
 * 2-3   expected magnetic field  uint16, tenths of a microtesla
 * 4-5   current magnetic dip     int16,  hundredths of a degree  (signed)
 * 6-7   expected magnetic dip    int16,  hundredths of a degree  (signed)
 * 8-9   temperature              int16,  hundredths of a degree C (signed)
 * 10-11 roll angle               int16,  hundredths of a degree  (signed)
 * 12-13 measurement id           uint16
 * ```
 * The two magnetic *field* strengths are unsigned and the four angles signed, which is easy to get
 * backwards: a dip of -45 degrees read as unsigned would come out as +610 degrees.
 */
object FclProtocol {

    const val PRIMARY_PACKET_SIZE = 20
    const val EXTENDED_PACKET_SIZE = 14

    /** Only version 2 is understood; anything else fails header validation. */
    const val PROTOCOL_VERSION = 2

    /** Top nibble of the header, a fixed sanity value. */
    const val MAGIC_NIBBLE = 0xF

    /** CRC-16/CCITT-FALSE: initial value 0xFFFF, polynomial 0x1021, MSB-first, no final xor. */
    const val CRC16_INIT = 0xFFFF
    const val CRC16_POLYNOMIAL = 0x1021

    /** The DistoX acknowledgement base, which FCL also uses. */
    const val ACK = 0x55

    /** The Java gives the extended packet one second to follow its primary. */
    const val PACKET_TIMEOUT_MS = 1000L

    /**
     * CRC-16/CCITT-FALSE over the first [length] bytes.
     *
     * Bit-at-a-time, most significant first, no input or output reflection and no final xor, which
     * is the variant that produces 0x29B1 for the ASCII string "123456789". Ported verbatim from
     * `FCLBLE.calculateCRC16`, including the mask back to 16 bits after every shift.
     */
    fun crc16Ccitt(data: ByteArray, length: Int = data.size): Int {
        var crc = CRC16_INIT
        for (index in 0 until length) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc =
                    if (crc and 0x8000 != 0) {
                        (crc shl 1) xor CRC16_POLYNOMIAL
                    } else {
                        crc shl 1
                    }
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /**
     * Parses a primary packet, or returns null if it is the wrong length or its header magic or
     * version is wrong. A packet that parses but fails its CRC or range checks comes back with
     * [FclPrimaryPacket.isValid] false, which the decoder treats as a protocol error.
     */
    fun parsePrimary(data: ByteArray): FclPrimaryPacket? {
        if (data.size != PRIMARY_PACKET_SIZE) return null

        val header = data.uint16LE(0)
        val magic = (header shr 12) and 0xF
        val version = (header shr 8) and 0xF
        val sequence = header and 0xFF

        if (magic != MAGIC_NIBBLE || version != PROTOCOL_VERSION) return null

        val azimuth = data.floatLE(4)
        val inclination = data.floatLE(8)
        val distance = data.floatLE(12)
        val shotQuality = data.uint16LE(16) / 1000.0f

        val receivedCrc = data.uint16LE(18)
        val calculatedCrc = crc16Ccitt(data, PRIMARY_PACKET_SIZE - 2)

        return FclPrimaryPacket(
            sequenceNumber = sequence,
            statusFlags = data.uint8(2),
            batteryLevel = data.uint8(3),
            azimuth = azimuth,
            inclination = inclination,
            distance = distance,
            shotQuality = shotQuality,
            protocolVersion = version,
            isValid =
            receivedCrc == calculatedCrc &&
                isMeasurementInRange(azimuth, inclination, distance, shotQuality),
        )
    }

    /** Parses an extended packet, or returns null if it is the wrong length. */
    fun parseExtended(data: ByteArray): FclExtendedPacket? {
        if (data.size != EXTENDED_PACKET_SIZE) return null

        return FclExtendedPacket(
            currentMagneticField = data.uint16LE(0) / 10.0f,
            expectedMagneticField = data.uint16LE(2) / 10.0f,
            currentMagneticDip = data.int16LE(4) / 100.0f,
            expectedMagneticDip = data.int16LE(6) / 100.0f,
            temperature = data.int16LE(8) / 100.0f,
            rollAngle = data.int16LE(10) / 100.0f,
            measurementId = data.uint16LE(12),
        )
    }

    /**
     * The FCL's own plausibility check, from `validateMeasurementRanges`.
     *
     * Note that it admits an azimuth of exactly 360 (the bound is inclusive) even though the
     * survey model rejects it, and caps distance at 999.9 m — both reproduced as written.
     */
    fun isMeasurementInRange(
        azimuth: Float,
        inclination: Float,
        distance: Float,
        quality: Float,
    ): Boolean =
        azimuth in 0.0f..360.0f &&
            inclination in -90.0f..90.0f &&
            distance in 0.0f..999.9f &&
            quality in 0.0f..1.0f

    fun combine(primary: FclPrimaryPacket, extended: FclExtendedPacket): FclEnhancedLeg =
        FclEnhancedLeg(
            azimuth = primary.azimuth,
            inclination = primary.inclination,
            distance = primary.distance,
            shotQuality = primary.shotQuality,
            currentMagneticField = extended.currentMagneticField,
            expectedMagneticField = extended.expectedMagneticField,
            currentMagneticDip = extended.currentMagneticDip,
            expectedMagneticDip = extended.expectedMagneticDip,
            temperature = extended.temperature,
            rollAngle = extended.rollAngle,
            batteryLevel = primary.batteryLevel,
            measurementId = extended.measurementId,
            statusFlags = primary.statusFlags,
            sequenceNumber = primary.sequenceNumber,
            protocolVersion = primary.protocolVersion,
            isValid = primary.isValid,
        )

    /**
     * The acknowledgement byte for a completed measurement: `0x55 + sequence`, truncated to eight
     * bits. As with SAP6 this is addition rather than the DistoX's bitwise OR, so for sequence
     * numbers above 0xAA it wraps — sequence 0xAB acknowledges as 0x00. Preserved as written.
     */
    fun acknowledgementByte(sequenceNumber: Int): Byte = (ACK + (sequenceNumber and 0xFF)).toByte()

    /**
     * Builds a primary packet with a correct header and CRC. Not in the Java; needed so tests and
     * simulators can produce packets the real parser accepts.
     */
    fun encodePrimary(
        sequenceNumber: Int,
        statusFlags: Int,
        batteryLevel: Int,
        azimuth: Float,
        inclination: Float,
        distance: Float,
        shotQuality: Float,
    ): ByteArray {
        val packet = ByteArray(PRIMARY_PACKET_SIZE)
        val header = (MAGIC_NIBBLE shl 12) or (PROTOCOL_VERSION shl 8) or (sequenceNumber and 0xFF)
        packet.putUint16LE(0, header)
        packet[2] = (statusFlags and 0xFF).toByte()
        packet[3] = (batteryLevel and 0xFF).toByte()
        packet.putFloatLE(4, azimuth)
        packet.putFloatLE(8, inclination)
        packet.putFloatLE(12, distance)
        packet.putUint16LE(16, (shotQuality * 1000f).toInt())
        packet.putUint16LE(18, crc16Ccitt(packet, PRIMARY_PACKET_SIZE - 2))
        return packet
    }

    /** Builds an extended packet. Not in the Java; the counterpart to [encodePrimary]. */
    fun encodeExtended(
        currentMagneticField: Float,
        expectedMagneticField: Float,
        currentMagneticDip: Float,
        expectedMagneticDip: Float,
        temperature: Float,
        rollAngle: Float,
        measurementId: Int,
    ): ByteArray {
        val packet = ByteArray(EXTENDED_PACKET_SIZE)
        packet.putUint16LE(0, (currentMagneticField * 10f).toInt())
        packet.putUint16LE(2, (expectedMagneticField * 10f).toInt())
        packet.putUint16LE(4, (currentMagneticDip * 100f).toInt())
        packet.putUint16LE(6, (expectedMagneticDip * 100f).toInt())
        packet.putUint16LE(8, (temperature * 100f).toInt())
        packet.putUint16LE(10, (rollAngle * 100f).toInt())
        packet.putUint16LE(12, measurementId)
        return packet
    }
}

/** What [FclDecoder] made of one notification. */
sealed interface FclDecodeResult {
    /** A primary packet was accepted; waiting for its extended half. */
    data object AwaitingExtended : FclDecodeResult

    /** Both halves arrived and agree. [acknowledgement] should be written back. */
    data class Complete(val leg: FclEnhancedLeg, val acknowledgement: ByteArray) : FclDecodeResult {
        override fun equals(other: Any?): Boolean =
            other is Complete &&
                leg == other.leg &&
                acknowledgement.contentEquals(other.acknowledgement)

        override fun hashCode(): Int = 31 * leg.hashCode() + acknowledgement.contentHashCode()
    }

    /** The packet was unusable, or arrived out of order; the state machine has been reset. */
    data class Error(val reason: String) : FclDecodeResult
}

/**
 * Reassembles an FCL measurement from its two notifications.
 *
 * The Java runs a four-state machine (IDLE, PRIMARY_RECEIVED, COMPLETE, ERROR) with a one-second
 * Android `Handler` timeout on the extended packet. Scheduling is a platform concern, so here the
 * timeout is [onTimeout], to be called by whatever the platform uses for delayed work;
 * [FclProtocol.PACKET_TIMEOUT_MS] carries the interval.
 *
 * The acknowledgement is only sent once *both* halves have arrived — so a shot whose extended
 * packet goes missing is never acknowledged, and the FCL is free to resend it.
 */
class FclDecoder {

    private var primary: FclPrimaryPacket? = null

    /** Whether a primary packet is waiting for its extended half. */
    val isAwaitingExtended: Boolean get() = primary != null

    fun feedPrimary(data: ByteArray): FclDecodeResult {
        val parsed = FclProtocol.parsePrimary(data)
        if (parsed == null || !parsed.isValid) {
            primary = null
            return FclDecodeResult.Error("Primary packet parsing failed")
        }
        primary = parsed
        return FclDecodeResult.AwaitingExtended
    }

    fun feedExtended(data: ByteArray): FclDecodeResult {
        val held = primary
            ?: return FclDecodeResult.Error("Extended packet out of sequence")

        val parsed = FclProtocol.parseExtended(data)
        if (parsed == null) {
            primary = null
            return FclDecodeResult.Error("Extended packet parsing failed")
        }

        primary = null
        val combined = FclProtocol.combine(held, parsed)
        return FclDecodeResult.Complete(
            combined,
            byteArrayOf(FclProtocol.acknowledgementByte(combined.sequenceNumber)),
        )
    }

    /** Routes a frame by channel; anything not marked EXTENDED is treated as a primary packet. */
    fun feed(channel: FrameChannel, data: ByteArray): FclDecodeResult =
        when (channel) {
            FrameChannel.EXTENDED -> feedExtended(data)
            else -> feedPrimary(data)
        }

    /** Call when the extended packet has not arrived within [FclProtocol.PACKET_TIMEOUT_MS]. */
    fun onTimeout(): FclDecodeResult {
        primary = null
        return FclDecodeResult.Error("Packet timeout")
    }

    fun reset() {
        primary = null
    }
}
