package org.hwyl.sexytopo.shared.comms.sap6

import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.ShotDetail
import org.hwyl.sexytopo.shared.comms.floatLE
import org.hwyl.sexytopo.shared.model.survey.Leg

/**
 * One notification from a SAP6 (or DiscoX, which speaks the same protocol).
 *
 * Note the field order: azimuth, inclination, roll, distance — not the distance-first order the
 * DistoX and BRIC use. Getting this wrong produces plausible-looking nonsense rather than an
 * error, which is why the order is spelled out here and asserted in the tests.
 */
data class Sap6Reading(
    /** Byte 0, kept signed as `ByteBuffer.get()` returns it — see [Sap6Protocol.acknowledgementByte]. */
    val acknowledgementSeed: Byte,
    val azimuth: Float,
    val inclination: Float,
    val roll: Float,
    val distance: Float,
) {
    /**
     * The survey leg. Throws if the reading is out of range, as `SAP6Communicator.legCallback`
     * does — it constructs a `Leg` with no guard at all, unlike the BRIC handler.
     */
    fun toLeg(): Leg = Leg(distance, azimuth, inclination)

    /** The leg, or null if the instrument reported something the survey model will not accept. */
    fun toLegOrNull(): Leg? =
        try {
            toLeg()
        } catch (exception: IllegalArgumentException) {
            null
        }
}

/**
 * The SAP6 "CaveBLE" protocol. Ported from `comms/sap6/CaveBLE.kt`.
 *
 * A single 17-byte notification on the leg characteristic carries the whole shot:
 * ```
 * 0     acknowledgement seed (the DistoX sequence bit, in the top bit)
 * 1-4   azimuth      float, degrees
 * 5-8   inclination  float, degrees
 * 9-12  roll         float, degrees
 * 13-16 distance     float, metres
 * ```
 * all little-endian. Commands are the same single bytes the DistoX uses, written bare to the
 * command characteristic — no framing.
 */
object Sap6Protocol {

    const val PACKET_SIZE = 17

    const val ACKNOWLEDGEMENT_SEED_INDEX = 0
    const val AZIMUTH_OFFSET = 1
    const val INCLINATION_OFFSET = 5
    const val ROLL_OFFSET = 9
    const val DISTANCE_OFFSET = 13

    /** The DistoX acknowledgement base, 0b01010101. */
    const val ACK = 0x55

    fun decode(bytes: ByteArray): Sap6Reading {
        require(bytes.size >= PACKET_SIZE) {
            "A SAP6 leg notification is $PACKET_SIZE bytes; got ${bytes.size}"
        }
        return Sap6Reading(
            acknowledgementSeed = bytes[ACKNOWLEDGEMENT_SEED_INDEX],
            azimuth = bytes.floatLE(AZIMUTH_OFFSET),
            inclination = bytes.floatLE(INCLINATION_OFFSET),
            roll = bytes.floatLE(ROLL_OFFSET),
            distance = bytes.floatLE(DISTANCE_OFFSET),
        )
    }

    /**
     * Decodes to a generic packet, or null when the reading is out of range.
     *
     * The Java would throw here rather than return; the survey model's own validation is the only
     * thing standing between a garbled notification and a corrupt survey, so a caller that wants
     * the Java's exact behaviour should use [Sap6Reading.toLeg] instead.
     */
    fun decodeToPacket(bytes: ByteArray): InstrumentPacket? {
        val reading = decode(bytes)
        val leg = reading.toLegOrNull() ?: return null
        return InstrumentPacket.Measurement(leg, ShotDetail(roll = reading.roll))
    }

    /**
     * The byte to write back to acknowledge a notification.
     *
     * The Java is `sendCommand(ACK + ack_bit)` where `ack_bit` is a **signed** byte straight from
     * a `ByteBuffer`, and the result is then written as a `FORMAT_UINT8`, i.e. truncated to eight
     * bits. So this is arithmetic addition, not the bitwise OR the DistoX protocol uses. The two
     * agree on the only values that occur in practice: seed 0x00 gives 0x55, and seed 0x80 (-128)
     * gives 85 - 128 = -43, which truncates to 0xD5 — the same as `0x80 or 0x55`. Any other seed
     * value would diverge. Reproduced as written.
     */
    fun acknowledgementByte(seed: Byte): Byte = (ACK + seed.toInt()).toByte()

    /** The one-byte frame to write to the command characteristic. */
    fun acknowledgementFor(bytes: ByteArray): ByteArray =
        byteArrayOf(acknowledgementByte(bytes[ACKNOWLEDGEMENT_SEED_INDEX]))
}
