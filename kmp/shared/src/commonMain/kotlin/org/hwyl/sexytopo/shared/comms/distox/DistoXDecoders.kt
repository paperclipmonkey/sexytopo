package org.hwyl.sexytopo.shared.comms.distox

import org.hwyl.sexytopo.shared.comms.InstrumentPacket

data class DistoXDecodeResult(
    val acknowledgement: ByteArray?,
    val packet: InstrumentPacket?,
    val disconnect: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is DistoXDecodeResult &&
            (acknowledgement?.contentEquals(other.acknowledgement) ?: (other.acknowledgement == null)) &&
            packet == other.packet &&
            disconnect == other.disconnect

    override fun hashCode(): Int {
        var result = acknowledgement?.contentHashCode() ?: 0
        result = 31 * result + (packet?.hashCode() ?: 0)
        result = 31 * result + disconnect.hashCode()
        return result
    }
}

/**
 * The Java suppresses a repeat by comparing the whole eight bytes with the previous packet — not
 * by the sequence bit — which means two genuinely identical consecutive shots would also be
 * suppressed. That is deliberate: an identical repeat is far more likely to be a retransmission.
 */
class DistoXMeasurementDecoder {

    private var previousPacket: ByteArray = ByteArray(0)

    var duplicateCount: Int = 0
        private set

    fun receive(packet: ByteArray): DistoXDecodeResult {
        val acknowledgement = DistoXProtocol.createAcknowledgementPacket(packet)

        val decoded =
            if (DistoXProtocol.isDataPacket(packet)) {
                if (packet.contentEquals(previousPacket)) {
                    duplicateCount++
                    null
                } else {
                    duplicateCount = 0
                    InstrumentPacket.Measurement(DistoXProtocol.parseMeasurement(packet))
                }
            } else {
                null
            }

        previousPacket = packet.copyOf()
        return DistoXDecodeResult(acknowledgement, decoded)
    }

    fun reset() {
        previousPacket = ByteArray(0)
        duplicateCount = 0
    }
}

/**
 * A packet of the type we are *not* waiting for is treated as a duplicate rather than an error,
 * because the usual cause is a lost acknowledgement making the device resend the half we already
 * have. After five in a row, [DistoXDecodeResult.disconnect] reports that the streams should close.
 */
class DistoXCalibrationDecoder {

    companion object {
        const val MAX_DUPLICATES = 5
    }

    private var acceleration: InstrumentPacket.Acceleration? = null

    var accelerationDuplicated: Int = 0
        private set

    var magneticDuplicated: Int = 0
        private set

    val isAwaitingAcceleration: Boolean get() = acceleration == null

    fun receive(packet: ByteArray): DistoXDecodeResult {
        val acknowledgement = DistoXProtocol.createAcknowledgementPacket(packet)

        return when (DistoXPacketType.of(packet)) {
            DistoXPacketType.CALIBRATION_ACCELERATION ->
                if (isAwaitingAcceleration) {
                    acceleration = DistoXProtocol.parseAcceleration(packet)
                    accelerationDuplicated = 0
                    DistoXDecodeResult(acknowledgement, null)
                } else {
                    accelerationDuplicated++
                    DistoXDecodeResult(
                        acknowledgement,
                        null,
                        disconnect = accelerationDuplicated >= MAX_DUPLICATES,
                    )
                }

            DistoXPacketType.CALIBRATION_MAGNETIC -> {
                val held = acceleration
                if (held == null) {
                    magneticDuplicated++
                    DistoXDecodeResult(
                        acknowledgement,
                        null,
                        disconnect = magneticDuplicated >= MAX_DUPLICATES,
                    )
                } else {
                    val magnetic = DistoXProtocol.parseMagnetic(packet)
                    magneticDuplicated = 0
                    acceleration = null
                    DistoXDecodeResult(
                        acknowledgement,
                        InstrumentPacket.CalibrationReading(
                            held.gx,
                            held.gy,
                            held.gz,
                            magnetic.mx,
                            magnetic.my,
                            magnetic.mz,
                        ),
                    )
                }
            }

            // "(Not sure what this packet is)" — acknowledged, then dropped.
            else -> DistoXDecodeResult(acknowledgement, null)
        }
    }

    fun reset() {
        acceleration = null
        accelerationDuplicated = 0
        magneticDuplicated = 0
    }
}
