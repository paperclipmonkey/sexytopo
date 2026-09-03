package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.distox.DistoXBlePackets
import org.hwyl.sexytopo.shared.comms.fcl.FclProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Four of these instruments will not send the next shot until the last is acknowledged, so a
 * decoder that quietly returns null for the reply looks like a flat battery after one reading.
 */
class InstrumentDecoderTest {

    /**
     * BRIC5 is `BRIC4.copy` and DiscoX is `SAP6.copy`; matching on identity would leave both
     * without a decoder at all.
     */
    @Test
    fun profileCopiesResolveToTheDriverTheySharedInTheApp() {
        val bric4 = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)
        val bric5 = InstrumentDecoder.forProfile(InstrumentProfile.BRIC5)
        val sap6 = InstrumentDecoder.forProfile(InstrumentProfile.SAP6)
        val discox = InstrumentDecoder.forProfile(InstrumentProfile.DISCOX)

        assertEquals(bric4::class, bric5::class)
        assertEquals(sap6::class, discox::class)
        assertTrue(bric4::class != sap6::class)
    }

    /**
     * A profile with no driver falls through to a decoder that hands the frame back verbatim; no
     * instrument in the table should reach it, or its readings would never arrive.
     */
    @Test
    fun everyProfileInTheTableHasADriverOfItsOwn() {
        for (profile in InstrumentProfile.ALL) {
            assertTrue(
                InstrumentDecoder.forProfile(profile).driverName !=
                    InstrumentDecoder.UNKNOWN_DRIVER,
                "${profile.name} fell through to the unknown decoder",
            )
        }
    }

    private fun distoXBleMeasurementFrame(): ByteArray {
        val frame = ByteArray(17)
        frame[0] = DistoXBlePackets.MEASUREMENT_IDENTIFIER
        frame[1] = 0x01 // admin: a data packet
        frame[2] = 0x10 // distance low
        frame[3] = 0x27 // distance high -> 0x2710 = 10000 -> 10.000 m
        return frame
    }

    @Test
    fun aDistoXBleMeasurementDecodesToALeg() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE)

        val legs = decoder.decode(FrameChannel.DEFAULT, distoXBleMeasurementFrame()).measurements()

        assertEquals(1, legs.size)
        assertEquals(10.0f, legs.single().distance)
    }

    /** Without this the instrument sends one shot and then waits for ever. */
    @Test
    fun aDistoXBleMeasurementIsAcknowledged() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE)
        val frame = distoXBleMeasurementFrame()

        val reply = decoder.acknowledgementFor(FrameChannel.DEFAULT, frame)

        assertNotNull(reply)
        assertTrue(reply.isNotEmpty())
    }

    /** A frame too short to hold an admin byte must not index past its end. */
    @Test
    fun aTruncatedFrameIsNotAcknowledgedAndDoesNotThrow() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE)

        assertNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, byteArrayOf(0x01)))
        decoder.decode(FrameChannel.DEFAULT, ByteArray(0))
    }

    @Test
    fun cavwayAcknowledgesFromTheFlagsByte() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.CAVWAY_X1)
        val packet = ByteArray(64).also { it[0] = 0x01 }

        assertNotNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, packet))
        assertNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, ByteArray(0)))
    }

    /**
     * `Bric4Manager` cannot tell BRIC's three characteristics apart and cycles blindly through
     * them; routing by channel means a repeated measurement frame stays in the measurement slot
     * instead of being mistaken for the next role.
     */
    @Test
    fun bricRoutesByChannelRatherThanCyclingBlindly() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)

        val emitted =
            listOf(
                FrameChannel.PRIMARY to ByteArray(32),
                FrameChannel.PRIMARY to ByteArray(32),
                FrameChannel.EXTENDED to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
            ).flatMap { (channel, bytes) -> decoder.decode(channel, bytes) }

        assertEquals(1, emitted.count { it is InstrumentPacket.Measurement })
    }

    /**
     * A stray all-clear before any measurement has arrived must not answer with whatever was last
     * in the measurement slot: a zero distance is a legal [Leg], so that would record a 0.00 m
     * splay at the surveyor's feet, indistinguishable from a wall they really measured.
     */
    @Test
    fun bricInventsNoShotFromAnAllClearAlone() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)

        val emitted = decoder.decode(FrameChannel.TERTIARY, ByteArray(32))

        assertEquals(emptyList(), emitted, "an all-clear before any measurement is not a shot")
    }

    /**
     * And it does not report the same shot twice: a repeated all-clear used to re-emit the
     * previous measurement, so one real shot plus two repeats of itself would *agree perfectly*
     * and promote to a station — cross-checked against nothing, since it is one reading counted
     * three times rather than three independent ones.
     */
    @Test
    fun bricDoesNotReportOneShotThreeTimes() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)

        val emitted =
            listOf(
                FrameChannel.PRIMARY to ByteArray(32),
                FrameChannel.EXTENDED to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
            ).flatMap { (channel, bytes) -> decoder.decode(channel, bytes) }

        assertEquals(
            1,
            emitted.count { it is InstrumentPacket.Measurement },
            "three all-clears over one measurement are one shot, not three",
        )
    }

    /** And the next real shot still gets through, which is what makes the guard a guard. */
    @Test
    fun bricStillReportsTheNextShot() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)

        val emitted =
            listOf(
                FrameChannel.PRIMARY to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
                FrameChannel.PRIMARY to ByteArray(32),
                FrameChannel.TERTIARY to ByteArray(32),
            ).flatMap { (channel, bytes) -> decoder.decode(channel, bytes) }

        assertEquals(2, emitted.count { it is InstrumentPacket.Measurement })
    }

    @Test
    fun sap6SurvivesAFrameItCannotParse() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.SAP6)

        assertEquals(emptyList(), decoder.decode(FrameChannel.DEFAULT, ByteArray(1)))
        assertNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, ByteArray(0)))
    }

    /**
     * No driver may throw, on any frame, on any channel: an out-of-bounds read is a catchable
     * exception on the JVM but not reliably one on Kotlin/Wasm or Kotlin/Native.
     */
    @Test
    fun noDriverThrowsOnAnyFrameOfAnyLength() {
        for (profile in InstrumentProfile.ALL) {
            val decoder = InstrumentDecoder.forProfile(profile)
            for (length in 0..70) {
                val frame = ByteArray(length) { (it * 7).toByte() }
                for (channel in FrameChannel.entries) {
                    decoder.decode(channel, frame)
                    decoder.acknowledgementFor(channel, frame)
                }
            }
        }
    }

    /** An extended packet with no primary before it yields nothing and no reply. */
    @Test
    fun fclDoesNotAcknowledgeHalfAShot() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.FCL)

        val outOfSequence = decoder.decode(FrameChannel.EXTENDED, ByteArray(14))
        assertEquals(emptyList(), outOfSequence)
        assertNull(decoder.acknowledgementFor(FrameChannel.EXTENDED, ByteArray(14)))
    }

    /**
     * `FclDecoderAdapter.decode` used to build the measurement from `toLegOrNull()`, discarding
     * the quality, battery, temperature and roll that `toPacketOrNull()` assembles instead.
     */
    @Test
    fun aCompleteFclShotCarriesItsTelemetry() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.FCL)

        decoder.decode(
            FrameChannel.PRIMARY,
            FclProtocol.encodePrimary(
                sequenceNumber = 1,
                statusFlags = 0,
                batteryLevel = 42,
                azimuth = 90f,
                inclination = 0f,
                distance = 5f,
                shotQuality = 0.95f,
            ),
        )
        val emitted =
            decoder.decode(
                FrameChannel.EXTENDED,
                FclProtocol.encodeExtended(
                    currentMagneticField = 48f,
                    expectedMagneticField = 48f,
                    currentMagneticDip = 66f,
                    expectedMagneticDip = 66f,
                    temperature = 21.5f,
                    rollAngle = 3.2f,
                    measurementId = 7,
                ),
            )

        val measurement = emitted.single() as InstrumentPacket.Measurement
        assertEquals(0.95f, measurement.detail.shotQuality)
        assertEquals(42, measurement.detail.batteryPercent)
        assertEquals(21.5f, measurement.detail.temperatureCelsius)
        assertEquals(3.2f, measurement.detail.roll)
        assertEquals("7", measurement.detail.reference)
    }

    @Test
    fun resettingClearsPartAssembledState() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.FCL)
        decoder.decode(FrameChannel.PRIMARY, ByteArray(20))
        decoder.reset()

        // After a reset the held primary is gone, so an extended packet is out of sequence again.
        assertEquals(emptyList(), decoder.decode(FrameChannel.EXTENDED, ByteArray(14)))
    }
}
