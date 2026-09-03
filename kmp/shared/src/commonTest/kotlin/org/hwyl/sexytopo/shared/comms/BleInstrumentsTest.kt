package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.bric.Bric4Decoder
import org.hwyl.sexytopo.shared.comms.bric.Bric4Error
import org.hwyl.sexytopo.shared.comms.bric.Bric4Protocol
import org.hwyl.sexytopo.shared.comms.cavway.CavwayX1Protocol
import org.hwyl.sexytopo.shared.comms.sap6.Sap6Protocol
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-level tests for the three BLE instruments that do not speak DistoX packets: BRIC4/BRIC5,
 * SAP6/DiscoX and the Cavway X1. The Android project has no tests for any of these.
 */
class BleInstrumentsTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    private fun bricPrimary(
        distance: Float,
        azimuth: Float,
        inclination: Float,
        year: Int = 2026,
        month: Int = 8,
        day: Int = 29,
    ): ByteArray {
        val packet = ByteArray(Bric4Protocol.PRIMARY_PACKET_SIZE)
        packet.putUint16LE(0, year)
        packet[2] = month.toByte()
        packet[3] = day.toByte()
        packet[4] = 14 // hour
        packet[5] = 5 // minute
        packet[6] = 32 // second
        packet[7] = 75 // centisecond, which SexyTopo discards
        packet.putFloatLE(8, distance)
        packet.putFloatLE(12, azimuth)
        packet.putFloatLE(16, inclination)
        return packet
    }

    private fun bricMetadata(reference: Int): ByteArray {
        val packet = ByteArray(Bric4Protocol.METADATA_PACKET_SIZE)
        packet.putInt32LE(0, reference)
        packet.putFloatLE(4, 1.5f) // dip
        packet.putFloatLE(8, 33.25f) // roll
        packet.putFloatLE(12, 7.5f) // temperature
        packet.putUint16LE(16, 64) // samples mean
        packet[18] = 1
        return packet
    }

    private fun bricErrors(firstCode: Int = 0, secondCode: Int = 0): ByteArray {
        val packet = ByteArray(Bric4Protocol.ERRORS_PACKET_SIZE)
        packet[0] = firstCode.toByte()
        packet.putFloatLE(1, 1.25f)
        packet.putFloatLE(5, 2.5f)
        packet[9] = secondCode.toByte()
        packet.putFloatLE(10, 3.75f)
        packet.putFloatLE(14, 5.0f)
        return packet
    }

    @Test
    fun bricMeasurementsAreLittleEndianFloatsInFinalUnits() {
        val measurement = Bric4Protocol.parsePrimary(bricPrimary(12.345f, 187.5f, -22.25f))
        assertClose(12.345f, measurement.leg.distance)
        assertClose(187.5f, measurement.leg.azimuth)
        assertClose(-22.25f, measurement.leg.inclination)
        assertTrue(measurement.isLegal)
        assertEquals(2026, measurement.timestamp.year)
        assertEquals(8, measurement.timestamp.month)
        assertEquals(29, measurement.timestamp.day)
        assertEquals(75, measurement.timestamp.centisecond)
        assertTrue(measurement.timestamp.isValidDate())
    }

    @Test
    fun anOutOfRangeBricReadingBecomesAnEmptyLeg() {
        // 400 degrees is not an azimuth; the Java catches the exception and keeps EMPTY_LEG.
        val measurement = Bric4Protocol.parsePrimary(bricPrimary(1f, 400f, 0f))
        assertFalse(measurement.isLegal)
        assertClose(0f, measurement.leg.distance)
        assertClose(400f, measurement.rawAzimuth, 0.01f)
    }

    @Test
    fun aGarbledBricTimestampIsFlaggedRatherThanThrown() {
        val packet = bricPrimary(1f, 0f, 0f, year = 2026, month = 0, day = 40)
        val measurement = Bric4Protocol.parsePrimary(packet)
        assertFalse(measurement.timestamp.isValidDate())
    }

    @Test
    fun aBricShotIsOnlyEmittedOnTheThirdNotification() {
        val decoder = Bric4Decoder()

        assertTrue(decoder.feed(bricPrimary(9.5f, 45f, 3f)).isEmpty())
        assertTrue(decoder.feed(bricMetadata(4242)).isEmpty())

        val emitted = decoder.feed(bricErrors())
        assertEquals(1, emitted.size)
        val packet = emitted.single()
        assertIs<InstrumentPacket.Measurement>(packet)
        assertClose(9.5f, packet.leg.distance)
        assertEquals("4242", packet.detail.reference)
    }

    @Test
    fun aBricShotWithErrorsNeverReachesTheSurvey() {
        val decoder = Bric4Decoder()
        decoder.feed(bricPrimary(9.5f, 45f, 3f))
        decoder.feed(bricMetadata(1))

        val emitted = decoder.feed(bricErrors(firstCode = 8, secondCode = 5))
        assertEquals(2, emitted.size)
        assertTrue(emitted.none { it is InstrumentPacket.Measurement })

        val first = emitted[0]
        assertIs<InstrumentPacket.DeviceFailure>(first)
        assertEquals("target didn't reflect", first.description)
        assertTrue(first.showToUser, "the first error of a pair is shown to the surveyor")
        assertClose(1.25f, first.data1)

        val second = emitted[1]
        assertIs<InstrumentPacket.DeviceFailure>(second)
        assertEquals("accelerometer disparity", second.description)
        assertFalse(second.showToUser, "the second is logged only")
    }

    @Test
    fun theBricDecoderCyclesThroughItsThreeCharacteristics() {
        val decoder = Bric4Decoder()
        repeat(2) { round ->
            decoder.feed(bricPrimary(1f + round, 10f, 0f))
            decoder.feed(bricMetadata(round))
            val emitted = decoder.feed(bricErrors())
            val packet = emitted.single()
            assertIs<InstrumentPacket.Measurement>(packet)
            assertClose(1f + round, packet.leg.distance)
            assertEquals(round.toString(), packet.detail.reference)
        }
    }

    @Test
    fun aNullBricNotificationHoldsTheCycleInPlace() {
        // Faithful to the Java, whose null guard returns before advancing the state machine.
        val decoder = Bric4Decoder()
        assertEquals("MEASUREMENT", decoder.expecting)
        assertTrue(decoder.feed(null).isEmpty())
        assertEquals("MEASUREMENT", decoder.expecting)
    }

    @Test
    fun theDuplicateBricErrorCodeResolvesToTheLastDeclaration() {
        // Code 10 is declared twice in the Java enum; the HashMap keeps the later entry.
        assertEquals(Bric4Error.TIMEOUT, Bric4Error.fromCode(10))
        // ...and code 11 is simply missing from the table.
        assertEquals(Bric4Error.UNRECOGNISED_ERROR, Bric4Error.fromCode(11))
        assertEquals(Bric4Error.UNRECOGNISED_ERROR, Bric4Error.fromCode(99))
        assertEquals(Bric4Error.AZIMUTH_ERROR, Bric4Error.fromCode(15))
    }

    @Test
    fun bricMetadataReferenceIsSigned() {
        // NumberTools.getUint32 is really getInt, so a high counter reads back negative.
        val packet = bricMetadata(-1)
        assertEquals(-1, Bric4Protocol.parseMetadata(packet).reference)
    }

    private fun sap6Packet(
        seed: Byte,
        azimuth: Float,
        inclination: Float,
        roll: Float,
        distance: Float,
    ): ByteArray {
        val packet = ByteArray(Sap6Protocol.PACKET_SIZE)
        packet[0] = seed
        packet.putFloatLE(1, azimuth)
        packet.putFloatLE(5, inclination)
        packet.putFloatLE(9, roll)
        packet.putFloatLE(13, distance)
        return packet
    }

    @Test
    fun theSap6PacketIsAzimuthInclinationRollDistance() {
        // The field order differs from every other instrument here; getting it wrong yields
        // plausible nonsense rather than an error, so it is pinned down explicitly.
        val reading = Sap6Protocol.decode(sap6Packet(0, 123.5f, -8.25f, 44.0f, 7.75f))
        assertClose(123.5f, reading.azimuth)
        assertClose(-8.25f, reading.inclination)
        assertClose(44.0f, reading.roll)
        assertClose(7.75f, reading.distance)

        val leg = reading.toLeg()
        assertClose(7.75f, leg.distance)
        assertClose(123.5f, leg.azimuth)
        assertClose(-8.25f, leg.inclination)
    }

    @Test
    fun theSap6AcknowledgementAddsRatherThanOrs() {
        // 0x55 + 0x00 = 0x55, and 0x55 + (-128) = -43, which truncates to 0xD5: the same two
        // values the DistoX gets by ORing, reached a different way.
        assertEquals(0x55.toByte(), Sap6Protocol.acknowledgementByte(0x00))
        assertEquals(0xD5.toByte(), Sap6Protocol.acknowledgementByte(0x80.toByte()))
        // Any other seed diverges from a bitwise OR: 0x55 + 1 = 0x56, but 0x01 or 0x55 = 0x55.
        assertEquals(0x56.toByte(), Sap6Protocol.acknowledgementByte(0x01))
    }

    @Test
    fun aSap6ReadingIsDecodedToAGenericPacket() {
        val packet = Sap6Protocol.decodeToPacket(sap6Packet(0, 10f, 0f, 90f, 3f))
        assertIs<InstrumentPacket.Measurement>(packet)
        assertClose(90f, packet.detail.roll!!)
        assertNull(Sap6Protocol.decodeToPacket(sap6Packet(0, 999f, 0f, 0f, 3f)))
    }

    private fun cavwayPacket(millimetres: Int, azimuth: Int, inclination: Int, roll: Int): ByteArray {
        val packet = ByteArray(64)
        packet[0] = CavwayX1Protocol.PACKET_TYPE_NORMAL
        packet[1] = 0x00 // flags
        // Distance is split 16-23 / 0-7 / 8-15 across bytes 2, 3 and 4 respectively.
        packet[2] = ((millimetres shr 16) and 0xFF).toByte()
        packet[3] = (millimetres and 0xFF).toByte()
        packet[4] = ((millimetres shr 8) and 0xFF).toByte()
        packet.putUint16LE(5, azimuth)
        packet.putUint16LE(7, inclination)
        packet.putUint16LE(9, roll)
        return packet
    }

    @Test
    fun cavwayDistanceIsTwentyFourBitsInAnOddByteOrder() {
        val packet = cavwayPacket(millimetres = 0x012345, azimuth = 0, inclination = 0, roll = 0)
        assertClose(74.565f, CavwayX1Protocol.parseMeasurement(packet).distance)
    }

    @Test
    fun cavwayAnglesScaleByThreeSixtyOverSixtyFiveFiveThreeFive() {
        // Half a turn is 0x8000 counts here, which maps to 180.0027 - not exactly 180, because
        // the Java divides by 0xFFFF rather than 0x10000.
        val packet = cavwayPacket(1000, azimuth = 0x8000, inclination = 0, roll = 0x4000)
        val leg = CavwayX1Protocol.parseMeasurement(packet)
        assertClose(180.0027f, leg.azimuth, 0.001f)
        assertClose(90.0014f, CavwayX1Protocol.parseAngle(packet, CavwayX1Protocol.ROLL_INDEX), 0.001f)
    }

    @Test
    fun aShortCavwayNotificationIsDropped() {
        assertNull(CavwayX1Protocol.decode(ByteArray(63)))
    }

    @Test
    fun cavwayCalibrationPacketsAreRecognisedButNotDecoded() {
        val packet = ByteArray(64)
        packet[0] = CavwayX1Protocol.PACKET_TYPE_CALIBRATION
        assertNull(CavwayX1Protocol.decode(packet))
    }

    @Test
    fun theCavwayAcknowledgementOrsTheWholeFlagsByte() {
        // Unlike DistoX BLE, which masks with 0x80 first. Preserved from the Java as written.
        assertEquals(0x55.toByte(), CavwayX1Protocol.acknowledgementByte(0x00))
        assertEquals(0xD5.toByte(), CavwayX1Protocol.acknowledgementByte(0x80.toByte()))
        assertEquals(0x57.toByte(), CavwayX1Protocol.acknowledgementByte(0x02))
    }

    @Test
    fun cavwayReusesTheDistoXCommandVocabulary() {
        assertContentEquals(
            byteArrayOf(0x64, 0x61, 0x74, 0x61, 0x3a, 0x01, 0x38, 0x0d, 0x0a),
            org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming.createWriteCommandPacket(
                InstrumentCommand.TAKE_SHOT.byte,
            ),
        )
    }
}
