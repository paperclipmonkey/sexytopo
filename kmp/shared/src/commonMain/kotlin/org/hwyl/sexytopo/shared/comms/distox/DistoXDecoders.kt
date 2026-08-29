package org.hwyl.sexytopo.shared.comms.distox

import org.hwyl.sexytopo.shared.comms.InstrumentPacket

/**
 * The result of feeding one packet to [DistoXMeasurementDecoder] or [DistoXCalibrationDecoder].
 *
 * The Java protocols do three things per packet — acknowledge, decode, act — and interleave them
 * with the socket. Splitting the decision out means the transport can be told what to write back
 * without the decoder knowing anything about sockets.
 */
data class DistoXDecodeResult(
    /** The byte to write back, or null if this packet needs no acknowledgement. */
    val acknowledgement: ByteArray?,
    /** What was decoded, if anything. */
    val packet: InstrumentPacket?,
    /**
     * True when the decoder wants the link torn down. `CalibrationProtocol` closes both streams
     * after five consecutive duplicates, on the grounds that the device and the app have lost
     * step and reconnecting is the only way back.
     */
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
 * Measurement mode. Ported from `comms/distox/MeasurementProtocol.go`.
 *
 * The device keeps resending a packet until it is acknowledged, and the acknowledgement can be
 * lost, so the same shot often arrives several times. The Java suppresses a repeat by comparing
 * the whole eight bytes with the previous packet — not by the sequence bit — which means two
 * genuinely identical consecutive shots (same distance, azimuth and inclination to the last
 * count) would also be suppressed. That is deliberate: an identical repeat is far more likely to
 * be a retransmission than a real second shot.
 *
 * Order of operations matters and is preserved: every packet is acknowledged, whatever its type;
 * only measurement packets are decoded; and `previousPacket` is updated at the end regardless.
 */
class DistoXMeasurementDecoder {

    private var previousPacket: ByteArray = ByteArray(0)

    /** Consecutive identical packets seen. Reset on each fresh measurement. */
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
 * Calibration mode. Ported from `comms/distox/CalibrationProtocol.go`.
 *
 * A calibration reading is two packets: acceleration then magnetic, in that order. The decoder
 * holds a half-built reading and only emits once both halves have arrived — the Java models this
 * with `CalibrationReading.State`, whose setters throw if called out of order, so the protocol
 * checks the state before each update rather than letting it throw.
 *
 * A packet of the type we are *not* waiting for is treated as a duplicate rather than an error,
 * because the usual cause is a lost acknowledgement making the device resend the half we already
 * have. After five in a row the Java gives up and closes the streams; [DistoXDecodeResult.disconnect]
 * reports that instead.
 */
class DistoXCalibrationDecoder {

    /** `CalibrationProtocol.checkExcessiveDuplication` closes the streams at five. */
    companion object {
        const val MAX_DUPLICATES = 5
    }

    private var acceleration: InstrumentPacket.Acceleration? = null

    var accelerationDuplicated: Int = 0
        private set

    var magneticDuplicated: Int = 0
        private set

    /** Whether the next expected packet is the acceleration half of a reading. */
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
