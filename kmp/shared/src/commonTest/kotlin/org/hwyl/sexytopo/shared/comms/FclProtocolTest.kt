package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.fcl.FclDecodeResult
import org.hwyl.sexytopo.shared.comms.fcl.FclDecoder
import org.hwyl.sexytopo.shared.comms.fcl.FclProtocol
import org.hwyl.sexytopo.shared.comms.fcl.FclStatusFlags
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte-level tests for the FCL split-packet protocol. The Android project has none. */
class FclProtocolTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    private fun primary(
        sequence: Int = 7,
        azimuth: Float = 123.5f,
        inclination: Float = -12.25f,
        distance: Float = 18.75f,
        quality: Float = 0.925f,
        statusFlags: Int = FclStatusFlags.EXTENDED_DATA,
        battery: Int = 88,
    ) = FclProtocol.encodePrimary(
        sequenceNumber = sequence,
        statusFlags = statusFlags,
        batteryLevel = battery,
        azimuth = azimuth,
        inclination = inclination,
        distance = distance,
        shotQuality = quality,
    )

    private fun extended(
        currentField: Float = 48.6f,
        expectedField: Float = 48.0f,
        currentDip: Float = -66.5f,
        expectedDip: Float = -66.0f,
        temperature: Float = 9.25f,
        roll: Float = 31.5f,
        id: Int = 1234,
    ) = FclProtocol.encodeExtended(
        currentField,
        expectedField,
        currentDip,
        expectedDip,
        temperature,
        roll,
        id,
    )

    @Test
    fun theCrcIsCcittFalse() {
        // The canonical check value for CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, MSB-first,
        // no reflection, no final xor). Getting the variant wrong is the classic way to break
        // this protocol, so it is pinned to the published vector rather than to our own output.
        assertEquals(0x29B1, FclProtocol.crc16Ccitt("123456789".encodeToByteArray()))
    }

    @Test
    fun theCrcOfNothingIsTheInitialValue() {
        assertEquals(0xFFFF, FclProtocol.crc16Ccitt(ByteArray(0)))
        assertEquals(FclProtocol.CRC16_INIT, FclProtocol.crc16Ccitt(ByteArray(0)))
    }

    @Test
    fun theCrcCoversEverythingButItself() {
        val packet = primary()
        assertEquals(FclProtocol.crc16Ccitt(packet, 18), packet.uint16LE(18))
    }

    @Test
    fun theHeaderCarriesMagicVersionAndSequence() {
        val packet = primary(sequence = 0xAB)
        assertEquals(0xF2AB, packet.uint16LE(0))

        val parsed = assertNotNull(FclProtocol.parsePrimary(packet))
        assertEquals(0xAB, parsed.sequenceNumber)
        assertEquals(2, parsed.protocolVersion)
    }

    @Test
    fun aPrimaryPacketDecodesItsFields() {
        val parsed = FclProtocol.parsePrimary(primary())!!
        assertClose(123.5f, parsed.azimuth)
        assertClose(-12.25f, parsed.inclination)
        assertClose(18.75f, parsed.distance)
        assertClose(0.925f, parsed.shotQuality)
        assertEquals(88, parsed.batteryLevel)
        assertTrue(parsed.isValid)
        assertEquals(FclStatusFlags.EXTENDED_DATA, parsed.statusFlags)
    }

    @Test
    fun aWrongLengthOrWrongMagicPacketIsRejectedOutright() {
        assertNull(FclProtocol.parsePrimary(ByteArray(19)))
        assertNull(FclProtocol.parsePrimary(ByteArray(21)))

        val wrongMagic = primary()
        wrongMagic.putUint16LE(0, 0x02AB) // magic nibble 0 instead of 0xF
        assertNull(FclProtocol.parsePrimary(wrongMagic))

        val wrongVersion = primary()
        wrongVersion.putUint16LE(0, 0xF1AB) // version 1
        assertNull(FclProtocol.parsePrimary(wrongVersion))
    }

    @Test
    fun aCorruptedPacketParsesButIsInvalid() {
        val packet = primary()
        packet[8] = (packet[8] + 1).toByte() // flip a bit in the inclination
        val parsed = FclProtocol.parsePrimary(packet)!!
        assertFalse(parsed.isValid, "the CRC should have caught this")
    }

    @Test
    fun anOutOfRangeMeasurementIsInvalidEvenWithAGoodCrc() {
        val parsed = FclProtocol.parsePrimary(primary(distance = 1500f))!!
        assertFalse(parsed.isValid, "the FCL caps distance at 999.9 m")

        val quality = FclProtocol.parsePrimary(primary(quality = 1.5f))!!
        assertFalse(quality.isValid)
    }

    @Test
    fun theRangeCheckAdmitsAnAzimuthOfExactlyThreeSixty() {
        // Reproduced from the Java, whose bound is inclusive even though the survey model's is not.
        assertTrue(FclProtocol.isMeasurementInRange(360f, 0f, 1f, 1f))
        assertFalse(FclProtocol.isMeasurementInRange(360.1f, 0f, 1f, 1f))
    }

    @Test
    fun extendedFieldsUseDifferentScalesAndSigns() {
        val parsed = FclProtocol.parseExtended(extended())!!
        // Field strengths are unsigned tenths of a microtesla...
        assertClose(48.6f, parsed.currentMagneticField, 0.05f)
        assertClose(48.0f, parsed.expectedMagneticField, 0.05f)
        // ...while dip, temperature and roll are signed hundredths of a degree.
        assertClose(-66.5f, parsed.currentMagneticDip, 0.01f)
        assertClose(-66.0f, parsed.expectedMagneticDip, 0.01f)
        assertClose(9.25f, parsed.temperature, 0.01f)
        assertClose(31.5f, parsed.rollAngle, 0.01f)
        assertEquals(1234, parsed.measurementId)
    }

    @Test
    fun aWrongLengthExtendedPacketIsRejected() {
        assertNull(FclProtocol.parseExtended(ByteArray(13)))
        assertNull(FclProtocol.parseExtended(ByteArray(15)))
    }

    @Test
    fun bothHalvesAreNeededBeforeAnythingIsEmitted() {
        val decoder = FclDecoder()

        assertEquals(FclDecodeResult.AwaitingExtended, decoder.feedPrimary(primary()))
        assertTrue(decoder.isAwaitingExtended)

        val result = decoder.feedExtended(extended())
        assertIs<FclDecodeResult.Complete>(result)
        assertClose(123.5f, result.leg.azimuth)
        assertClose(9.25f, result.leg.temperature, 0.01f)
        assertEquals(1234, result.leg.measurementId)
        assertFalse(decoder.isAwaitingExtended)
    }

    @Test
    fun theAcknowledgementIsOnlySentOnceTheShotIsWhole() {
        // 0x55 + sequence 7 = 0x5C.
        val decoder = FclDecoder()
        decoder.feedPrimary(primary(sequence = 7))
        val result = decoder.feedExtended(extended())
        assertIs<FclDecodeResult.Complete>(result)
        assertContentEquals(byteArrayOf(0x5C), result.acknowledgement)
    }

    @Test
    fun theAcknowledgementWrapsForHighSequenceNumbers() {
        // Addition, not a bitwise OR: 0x55 + 0xAB = 0x100, which truncates to 0x00.
        assertEquals(0x00.toByte(), FclProtocol.acknowledgementByte(0xAB))
        assertEquals(0x55.toByte(), FclProtocol.acknowledgementByte(0))
    }

    @Test
    fun anExtendedPacketOutOfSequenceIsAnError() {
        val decoder = FclDecoder()
        val result = decoder.feedExtended(extended())
        assertIs<FclDecodeResult.Error>(result)
        assertEquals("Extended packet out of sequence", result.reason)
    }

    @Test
    fun aTimeoutDiscardsTheHeldPrimaryPacket() {
        val decoder = FclDecoder()
        decoder.feedPrimary(primary())
        assertTrue(decoder.isAwaitingExtended)

        assertIs<FclDecodeResult.Error>(decoder.onTimeout())
        assertFalse(decoder.isAwaitingExtended)
        assertIs<FclDecodeResult.Error>(decoder.feedExtended(extended()))
    }

    @Test
    fun anInvalidPrimaryPacketDoesNotArmTheStateMachine() {
        val decoder = FclDecoder()
        val corrupt = primary()
        corrupt[4] = (corrupt[4] + 1).toByte()
        assertIs<FclDecodeResult.Error>(decoder.feedPrimary(corrupt))
        assertFalse(decoder.isAwaitingExtended)
    }

    @Test
    fun framesAreRoutedByChannel() {
        val decoder = FclDecoder()
        decoder.feed(FrameChannel.PRIMARY, primary())
        assertIs<FclDecodeResult.Complete>(decoder.feed(FrameChannel.EXTENDED, extended()))
    }

    @Test
    fun magneticAnomaliesAreDescribedByDeviation() {
        val decoder = FclDecoder()
        decoder.feedPrimary(primary())
        val result = decoder.feedExtended(extended(currentField = 60.0f, expectedField = 48.0f))
        assertIs<FclDecodeResult.Complete>(result)
        assertClose(12.0f, result.leg.magneticFieldDeviation(), 0.05f)
        assertEquals("Significant anomaly", result.leg.magneticFieldDescription())
        assertEquals("Excellent", result.leg.qualityDescription())
    }

    @Test
    fun statusFlagsAreDecodedFromByteTwo() {
        val parsed =
            FclProtocol.parsePrimary(
                primary(statusFlags = FclStatusFlags.LOW_BATTERY or FclStatusFlags.HIGH_INTERFERENCE),
            )!!
        val decoder = FclDecoder()
        decoder.feedPrimary(
            primary(statusFlags = FclStatusFlags.LOW_BATTERY or FclStatusFlags.HIGH_INTERFERENCE),
        )
        val result = decoder.feedExtended(extended())
        assertIs<FclDecodeResult.Complete>(result)

        assertEquals(parsed.statusFlags, result.leg.statusFlags)
        assertTrue(result.leg.hasLowBattery())
        assertTrue(result.leg.hasInterferenceWarning())
        assertFalse(result.leg.hasVerticalWarning())
        assertEquals("Low Battery", result.leg.statusDescription())
    }

    @Test
    fun aCompletedShotBecomesAGenericMeasurementPacket() {
        val decoder = FclDecoder()
        decoder.feedPrimary(primary())
        val result = decoder.feedExtended(extended())
        assertIs<FclDecodeResult.Complete>(result)

        val packet = result.leg.toPacketOrNull()!!
        assertClose(18.75f, packet.leg.distance)
        assertEquals("1234", packet.detail.reference)
        assertEquals(88, packet.detail.batteryPercent)
        assertClose(0.925f, packet.detail.shotQuality!!)
    }
}
