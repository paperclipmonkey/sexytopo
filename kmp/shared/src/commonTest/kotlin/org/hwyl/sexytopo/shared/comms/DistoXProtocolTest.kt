package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.distox.DistoXCalibrationDecoder
import org.hwyl.sexytopo.shared.comms.distox.DistoXMeasurementDecoder
import org.hwyl.sexytopo.shared.comms.distox.DistoXPacketType
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.model.survey.Leg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `app/src/test/.../comms/MeasurementProtocolTest`, `DistoXProtocolTest` and
 * `CalibrationProtocolTest`, keeping their real captured packets and their expected values.
 */
class DistoXProtocolTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    // "i for ignore", as the Java test puts it.
    private val i: Byte = 0

    /** A real flat shot captured from a DistoX; the Java test asserts 2.017 m / 71.2 / 4.5. */
    private val flatShotPacket = byteArrayOf(1, -31, 7, -94, 50, 58, 3, -5)

    /** A real steeply-down shot; the Java test asserts 0.852 m / 238.3 / -75.0. */
    private val downShotPacket = byteArrayOf(1, 84, 3, 113, -87, -83, -54, -13)

    @Test
    fun flatMeasurementIsParsedCorrectly() {
        assertTrue(DistoXProtocol.isDataPacket(flatShotPacket))
        val leg = DistoXProtocol.parseMeasurement(flatShotPacket)
        assertClose(2.017f, leg.distance, 0.001f)
        assertClose(71.2f, leg.azimuth, 0.1f)
        assertClose(4.5f, leg.inclination, 0.1f)
    }

    @Test
    fun downMeasurementIsParsedCorrectly() {
        assertTrue(DistoXProtocol.isDataPacket(downShotPacket))
        val leg = DistoXProtocol.parseMeasurement(downShotPacket)
        assertClose(0.852f, leg.distance, 0.001f)
        assertClose(238.3f, leg.azimuth, 0.1f)
        assertClose(-75.0f, leg.inclination, 0.1f)
    }

    @Test
    fun fixedPointScalingIsExact() {
        // azimuth reading 12962 counts * 180 / 32768 = 71.202392578125
        assertClose(71.202393f, DistoXProtocol.parseMeasurement(flatShotPacket).azimuth, 0.00001f)
        // inclination reading 826 counts * 90 / 16384
        assertClose(4.5373535f, DistoXProtocol.parseMeasurement(flatShotPacket).inclination, 0.00001f)
    }

    @Test
    fun distanceOverflowBitIsWorth65536Millimetres() {
        // Admin bit 6 set, all distance bytes zero: the whole distance is the overflow bit.
        val packet = byteArrayOf(0b0100_0001, 0, 0, 0, 0, 0, 0, 0)
        assertClose(65.536f, DistoXProtocol.parseMeasurement(packet).distance, 0.0001f)
    }

    @Test
    fun distanceCombinesOverflowBitWithTheSixteenBitField() {
        // 0x0201 = 513 mm plus the 65536 mm overflow bit.
        val packet = byteArrayOf(0b0100_0001, 0x01, 0x02, 0, 0, 0, 0, 0)
        assertClose(66.049f, DistoXProtocol.parseMeasurement(packet).distance, 0.0001f)
    }

    @Test
    fun inclinationReadingsAtOrAbove32768AreNegative() {
        // 65536 - 49152 = 16384 counts, i.e. exactly -90 degrees: straight down.
        val packet = byteArrayOf(1, 0, 0, 0, 0, 0x00, 0xC0.toByte(), 0)
        assertClose(-90f, DistoXProtocol.parseMeasurement(packet).inclination, 0.0001f)
    }

    @Test
    fun theSequenceAndDistanceBitsDoNotDisturbTypeDetection() {
        val packet = byteArrayOf(0b1100_0001.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertEquals(DistoXPacketType.MEASUREMENT, DistoXPacketType.of(packet))
        assertTrue(DistoXProtocol.isDataPacket(packet))
    }

    @Test
    fun packetTypesAreReadFromTheLowSixBits() {
        assertEquals(DistoXPacketType.MEASUREMENT, DistoXPacketType.of(byteArrayOf(0x01)))
        assertEquals(DistoXPacketType.CALIBRATION_ACCELERATION, DistoXPacketType.of(byteArrayOf(0x02)))
        assertEquals(DistoXPacketType.CALIBRATION_MAGNETIC, DistoXPacketType.of(byteArrayOf(0x03)))
        assertEquals(DistoXPacketType.READ_REPLY, DistoXPacketType.of(byteArrayOf(0x38)))
        assertEquals(DistoXPacketType.UNKNOWN, DistoXPacketType.of(byteArrayOf(0x00)))
        assertEquals(DistoXPacketType.UNKNOWN, DistoXPacketType.of(byteArrayOf(0x1F)))
    }

    @Test
    fun acknowledgementIs0x55WhenTheSequenceBitIsClear() {
        val packet = byteArrayOf(0b0000_0001, i, i, i, i, i, i, i)
        assertContentEquals(
            byteArrayOf(0b0101_0101),
            DistoXProtocol.createAcknowledgementPacket(packet),
        )
    }

    @Test
    fun acknowledgementIs0xD5WhenTheSequenceBitIsSet() {
        val packet = byteArrayOf(0b1000_0001.toByte(), i, i, i, i, i, i, i)
        assertContentEquals(
            byteArrayOf(0xD5.toByte()),
            DistoXProtocol.createAcknowledgementPacket(packet),
        )
        assertTrue(DistoXProtocol.hasSequenceBit(packet))
    }

    @Test
    fun acknowledgementIgnoresEverythingButBitSeven() {
        // The Java test uses a type-2 packet with the sequence bit clear and still expects 0x55.
        val packet = byteArrayOf(0b0000_0010, i, i, i, i, i, i, i)
        assertEquals(0x55.toByte(), DistoXProtocol.createAcknowledgementPacket(packet)[0])
    }

    /** From `CalibrationProtocolTest`; byte 0 is ignored by the field readers. */
    private val accelerationPacket = byteArrayOf(0, -102, -1, 86, -3, -52, 96, 11)
    private val magneticPacket = byteArrayOf(0, 48, 31, -43, -7, -56, 62, 1)

    @Test
    fun accelerationCountsAreSignedSixteenBit() {
        val reading = DistoXProtocol.parseAcceleration(accelerationPacket)
        // The Java test allows a delta of 80 against reference values read via PocketTopo;
        // these are the exact values the arithmetic produces.
        assertEquals(-102, reading.gx)
        assertEquals(-682, reading.gy)
        assertEquals(24780, reading.gz)
        // ...and they are all inside the Java test's tolerance of its reference readings.
        assertTrue(abs(reading.gx - (-79)) < 80)
        assertTrue(abs(reading.gy - (-603)) < 80)
        assertTrue(abs(reading.gz - 24785) < 80)
    }

    @Test
    fun magneticCountsAreSignedSixteenBit() {
        val reading = DistoXProtocol.parseMagnetic(magneticPacket)
        assertEquals(7984, reading.mx)
        assertEquals(-1579, reading.my)
        assertEquals(16072, reading.mz)
        assertTrue(abs(reading.mx - 7978) < 80)
        assertTrue(abs(reading.my - (-1607)) < 80)
        assertTrue(abs(reading.mz - 16090) < 80)
    }

    @Test
    fun theSignedFoldIsOffByOneAtTheBoundary() {
        // The Java folds only when the value is strictly greater than 32768, so 0x8000 stays
        // positive where two's complement would make it -32768. Deliberate; see the port note.
        val at = byteArrayOf(0, 0x00, 0x80.toByte(), 0, 0, 0, 0, 0)
        assertEquals(32768, DistoXProtocol.readSignedDoubleByte(at, 1, 2))

        val justAbove = byteArrayOf(0, 0x01, 0x80.toByte(), 0, 0, 0, 0, 0)
        assertEquals(-32767, DistoXProtocol.readSignedDoubleByte(justAbove, 1, 2))

        val justBelow = byteArrayOf(0, 0xFF.toByte(), 0x7F, 0, 0, 0, 0, 0)
        assertEquals(32767, DistoXProtocol.readSignedDoubleByte(justBelow, 1, 2))
    }

    @Test
    fun doubleByteFieldsAreLittleEndian() {
        val packet = byteArrayOf(0, 0x34, 0x12, 0, 0, 0, 0, 0)
        assertEquals(0x1234, DistoXProtocol.readDoubleByte(packet, 1, 2))
    }

    @Test
    fun encodedMeasurementsSurviveTheRoundTrip() {
        // One count is 90/16384 degrees, so half a count of rounding is the tightest bound here.
        for (leg in listOf(
            Leg(0f, 0f, 0f),
            Leg(2.017f, 71.2f, 4.5f),
            Leg(0.852f, 238.3f, -75.0f),
            Leg(100.0f, 359.99f, 90f),
            Leg(70.5f, 180f, -90f),
        )) {
            val decoded = DistoXProtocol.parseMeasurement(DistoXProtocol.encodeMeasurement(leg))
            assertClose(leg.distance, decoded.distance, 0.001f)
            assertClose(leg.azimuth, decoded.azimuth, 0.01f)
            assertClose(leg.inclination, decoded.inclination, 0.01f)
        }
    }

    @Test
    fun encodingSetsTheOverflowBitPastSixtyFiveMetres() {
        val packet = DistoXProtocol.encodeMeasurement(Leg(70.5f, 0f, 0f))
        assertEquals(
            DistoXProtocol.DISTANCE_BIT_MASK,
            packet[0].toInt() and DistoXProtocol.DISTANCE_BIT_MASK,
        )
        assertClose(70.5f, DistoXProtocol.parseMeasurement(packet).distance, 0.001f)
    }

    @Test
    fun encodingSetsTheRequestedSequenceBit() {
        val withBit = DistoXProtocol.encodeMeasurement(Leg(1f, 0f, 0f), sequenceBit = true)
        assertTrue(DistoXProtocol.hasSequenceBit(withBit))
        assertEquals(0xD5.toByte(), DistoXProtocol.createAcknowledgementPacket(withBit)[0])

        val withoutBit = DistoXProtocol.encodeMeasurement(Leg(1f, 0f, 0f), sequenceBit = false)
        assertFalse(DistoXProtocol.hasSequenceBit(withoutBit))
        assertEquals(0x55.toByte(), DistoXProtocol.createAcknowledgementPacket(withoutBit)[0])
    }

    @Test
    fun everyPacketIsAcknowledgedEvenWhenItIsNotDecoded() {
        val decoder = DistoXMeasurementDecoder()
        val result = decoder.receive(byteArrayOf(0x38, 0, 0, 0, 0, 0, 0, 0))
        assertContentEquals(byteArrayOf(0x55), result.acknowledgement)
        assertNull(result.packet)
    }

    @Test
    fun anIdenticalRepeatIsSuppressed() {
        val decoder = DistoXMeasurementDecoder()

        val first = decoder.receive(flatShotPacket)
        assertNotNull(first.packet)
        assertEquals(0, decoder.duplicateCount)

        val repeat = decoder.receive(flatShotPacket)
        assertNull(repeat.packet, "a retransmitted packet must not be recorded twice")
        assertEquals(1, decoder.duplicateCount)
        assertContentEquals(byteArrayOf(0x55), repeat.acknowledgement)

        val next = decoder.receive(downShotPacket)
        assertNotNull(next.packet)
        assertEquals(0, decoder.duplicateCount)
    }

    @Test
    fun aCalibrationReadingNeedsBothHalves() {
        val decoder = DistoXCalibrationDecoder()

        val acceleration = decoder.receive(byteArrayOf(0x02, -102, -1, 86, -3, -52, 96, 11))
        assertNull(acceleration.packet, "half a reading is not a reading")
        assertFalse(decoder.isAwaitingAcceleration)

        val magnetic = decoder.receive(byteArrayOf(0x03, 48, 31, -43, -7, -56, 62, 1))
        assertEquals(
            InstrumentPacket.CalibrationReading(-102, -682, 24780, 7984, -1579, 16072),
            magnetic.packet,
        )
        assertTrue(decoder.isAwaitingAcceleration, "the decoder is ready for the next reading")
    }

    @Test
    fun fiveDuplicatedHalvesAskForADisconnect() {
        val decoder = DistoXCalibrationDecoder()
        val repeated = byteArrayOf(0x02, -102, -1, 86, -3, -52, 96, 11)

        decoder.receive(repeated) // accepted; now waiting for the magnetic half
        repeat(4) { attempt ->
            val result = decoder.receive(repeated)
            assertFalse(result.disconnect, "gave up after only ${attempt + 1} duplicates")
        }
        assertTrue(decoder.receive(repeated).disconnect)
        assertEquals(5, decoder.accelerationDuplicated)
    }

    @Test
    fun calibrationIsWrittenFourBytesAtATimeFrom0x8010() {
        val coefficients = ByteArray(8) { (it + 1).toByte() }
        val commands = DistoXProtocol.createWriteCalibrationCommands(coefficients)

        assertEquals(2, commands.size)
        assertContentEquals(byteArrayOf(0x39, 0x10, 0x80.toByte(), 1, 2, 3, 4), commands[0])
        assertContentEquals(byteArrayOf(0x39, 0x14, 0x80.toByte(), 5, 6, 7, 8), commands[1])
        assertEquals(0x8010, DistoXProtocol.addressOfWriteCommand(commands[0]))
        assertEquals(0x8014, DistoXProtocol.addressOfWriteCommand(commands[1]))
    }

    @Test
    fun theFullCoefficientBlockIsThirteenWrites() {
        // The calibration block runs 0x8010..0x8043 inclusive: 52 bytes, four at a time.
        val commands = DistoXProtocol.createWriteCalibrationCommands(ByteArray(52))
        assertEquals(13, commands.size)
        assertEquals(0x8040, DistoXProtocol.addressOfWriteCommand(commands.last()))
    }

    @Test
    fun aWriteReplyMustEchoItsAddress() {
        assertTrue(
            DistoXProtocol.isCalibrationWriteReplyValid(
                0x8010,
                byteArrayOf(0x38, 0x10, 0x80.toByte(), 0, 0, 0, 0, 0),
            ),
        )
        assertFalse(
            DistoXProtocol.isCalibrationWriteReplyValid(
                0x8010,
                byteArrayOf(0x38, 0x14, 0x80.toByte(), 0, 0, 0, 0, 0),
            ),
            "an address mismatch means the write did not land where we asked",
        )
        assertFalse(
            DistoXProtocol.isCalibrationWriteReplyValid(
                0x8010,
                byteArrayOf(0x01, 0x10, 0x80.toByte(), 0, 0, 0, 0, 0),
            ),
            "only a 0x38 read reply counts as an acceptance",
        )
    }
}
