package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXBlePackets
import org.hwyl.sexytopo.shared.comms.distox.DistoXMemoryRange
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from `app/src/test/.../comms/DistoXBleManagerTest`, then taken a good deal further. */
class DistoXBleTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    // -----------------------------------------------------------------------------------------
    // Outbound framing
    // -----------------------------------------------------------------------------------------

    @Test
    fun aCommandFrameIsNineBytes() {
        // The Java test asserts exactly this length for the stop-calibration command.
        val packet = DistoXBleFraming.createWriteCommandPacket(InstrumentCommand.STOP_CALIBRATION.byte)
        assertEquals(9, packet.size)
    }

    @Test
    fun aCommandFrameIsDataColonLengthPayloadCrlf() {
        val packet = DistoXBleFraming.createWriteCommandPacket(0x30)
        assertContentEquals(
            byteArrayOf(
                0x64, 0x61, 0x74, 0x61, 0x3a, // "data:"
                0x01, //                        payload length
                0x30, //                        the command itself
                0x0d, 0x0a, //                  CRLF
            ),
            packet,
        )
        assertEquals("data:", packet.decodeToString(0, 5))
    }

    @Test
    fun theLengthByteCountsOnlyThePayload() {
        val packet = DistoXBleFraming.createWritePacket(ByteArray(20) { 0x7F })
        assertEquals(28, packet.size)
        assertEquals(20, packet.uint8(5))
    }

    @Test
    fun aMemoryWriteCarriesItsLengthTwice() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val packet =
            DistoXBleFraming.createWriteMemoryPacket(
                DistoXMemoryRange.CALIBRATION_COEFFICIENTS,
                payload,
            )

        // Outer data: frame, then the inner '>' payload with the address low byte first.
        assertContentEquals(
            byteArrayOf(
                0x64, 0x61, 0x74, 0x61, 0x3a, //  "data:"
                0x08, //                          outer length: the whole memory-write payload
                0x3e, //                          '>' introduces a memory write
                0x10, 0x80.toByte(), //           address 0x8010, low byte first
                0x04, //                          inner length: the coefficient bytes
                1, 2, 3, 4,
                0x0d, 0x0a,
            ),
            packet,
        )
    }

    @Test
    fun theCalibrationBlockStartsAt0x8010AndIsFiftyTwoBytes() {
        val range = DistoXMemoryRange.CALIBRATION_COEFFICIENTS
        assertEquals(0x8010, range.start)
        assertEquals(0x8043, range.end)
        assertEquals(52, range.sizeBytes)
        assertContentEquals(byteArrayOf(0x10, 0x80.toByte()), range.addressBytes)
    }

    @Test
    fun framesRoundTripThroughTheUnwrapper() {
        val payload = byteArrayOf(0x38, 0x01, 0x02)
        val frame = DistoXBleFraming.createWritePacket(payload)
        assertContentEquals(payload, DistoXBleFraming.payloadOrNull(frame))
        assertNull(DistoXBleFraming.payloadOrNull(byteArrayOf(0x38)))
        assertNull(DistoXBleFraming.payloadOrNull("wrong:".encodeToByteArray() + payload))
    }

    // -----------------------------------------------------------------------------------------
    // Inbound notifications
    // -----------------------------------------------------------------------------------------

    /** Identifier 0x01, then the flat shot packet from the DistoX test data. */
    private val measurementNotification =
        byteArrayOf(0x01, 1, -31, 7, -94, 50, 58, 3, -5, 0, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun aMeasurementNotificationDecodesLikeAClassicPacket() {
        val packet = DistoXBlePackets.decode(measurementNotification)
        assertIs<InstrumentPacket.Measurement>(packet)
        assertClose(2.017f, packet.leg.distance)
        assertClose(71.2f, packet.leg.azimuth, 0.1f)
        assertClose(4.5f, packet.leg.inclination, 0.1f)
    }

    @Test
    fun aCalibrationNotificationCarriesBothHalvesAtOnce() {
        // Acceleration packet at offset 1, magnetic at offset 9 — the same two captured packets
        // the classic protocol needs two round trips to collect.
        val notification =
            byteArrayOf(0x02) +
                byteArrayOf(0x02, -102, -1, 86, -3, -52, 96, 11) +
                byteArrayOf(0x03, 48, 31, -43, -7, -56, 62, 1)
        assertEquals(17, notification.size)

        assertEquals(
            InstrumentPacket.CalibrationReading(-102, -682, 24780, 7984, -1579, 16072),
            DistoXBlePackets.decode(notification),
        )
    }

    @Test
    fun aShortNotificationIsZeroPaddedRatherThanRejected() {
        // Arrays.copyOfRange pads; the Java relies on that, so a truncated frame decodes to zeros.
        val truncated = byteArrayOf(0x01, 1, 0, 0)
        val packet = DistoXBlePackets.decode(truncated)
        assertIs<InstrumentPacket.Measurement>(packet)
        assertClose(0f, packet.leg.distance)
        assertClose(0f, packet.leg.azimuth)
        assertClose(0f, packet.leg.inclination)
    }

    @Test
    fun anUnknownIdentifierIsKeptWholeForLogging() {
        val frame = byteArrayOf(0x7F, 1, 2, 3)
        assertEquals(InstrumentPacket.Unrecognised(frame), DistoXBlePackets.decode(frame))
        assertNull(DistoXBlePackets.decode(ByteArray(0)))
    }

    @Test
    fun theAcknowledgementReadsTheSequenceBitFromByteOne() {
        // Byte 0 is the BLE identifier; the DistoX admin byte, and so the sequence bit, is byte 1.
        val clear = byteArrayOf(0x01, 0b0000_0001, 0, 0, 0, 0, 0, 0, 0)
        assertContentEquals(
            DistoXBleFraming.createWriteCommandPacket(0x55),
            DistoXBlePackets.acknowledgementFor(clear),
        )

        val set = byteArrayOf(0x01, 0b1000_0001.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertContentEquals(
            DistoXBleFraming.createWriteCommandPacket(0xD5.toByte()),
            DistoXBlePackets.acknowledgementFor(set),
        )
    }
}
