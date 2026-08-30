package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.distox.DistoXBlePackets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The layer between a radio and a survey.
 *
 * Two things matter here and only one of them is obvious. The first is that a frame decodes to the
 * right reading. The second is the acknowledgement: four of these instruments will not send the
 * next shot until the last one is acknowledged, so a decoder that quietly returns null for the
 * reply takes exactly one reading and then looks, from inside a cave, precisely like a flat
 * battery.
 */
class InstrumentDecoderTest {

    // -------------------------------------------------------------------------------------
    // The right driver for the profile
    // -------------------------------------------------------------------------------------

    /**
     * BRIC5 is `BRIC4.copy` and DiscoX is `SAP6.copy`, which is exactly how the Android app treats
     * them: same driver, another advertised prefix. Matching on identity would leave both without
     * a decoder at all.
     */
    @Test
    fun profileCopiesResolveToTheDriverTheySharedInTheApp() {
        val bric4 = InstrumentDecoder.forProfile(InstrumentProfile.BRIC4)
        val bric5 = InstrumentDecoder.forProfile(InstrumentProfile.BRIC5)
        val sap6 = InstrumentDecoder.forProfile(InstrumentProfile.SAP6)
        val discox = InstrumentDecoder.forProfile(InstrumentProfile.DISCOX)

        assertEquals(bric4::class, bric5::class)
        assertEquals(sap6::class, discox::class)
        // ...and they are not all the same fallback.
        assertTrue(bric4::class != sap6::class)
    }

    /**
     * A profile with no driver falls through to a decoder that hands the frame back verbatim. That
     * fallback is deliberate — better than silently dropping traffic — but no instrument in the
     * table should reach it, and one that did would be an instrument whose readings never arrive.
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

    // -------------------------------------------------------------------------------------
    // DistoX-BLE
    // -------------------------------------------------------------------------------------

    private fun distoXBleMeasurementFrame(): ByteArray {
        // Identifier byte, then a bare DistoX measurement packet: admin 0x01, then distance,
        // azimuth and inclination as the protocol lays them out.
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

    // -------------------------------------------------------------------------------------
    // The others
    // -------------------------------------------------------------------------------------

    @Test
    fun cavwayAcknowledgesFromTheFlagsByte() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.CAVWAY_X1)
        val packet = ByteArray(64).also { it[0] = 0x01 }

        assertNotNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, packet))
        assertNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, ByteArray(0)))
    }

    /**
     * BRIC's three characteristics are one logical stream, and its errors frame is what completes
     * a shot. `Bric4Manager` cannot tell the three apart, so it cycles blindly and desynchronises
     * if one is dropped or repeated; CoreBluetooth and Web Bluetooth both report which
     * characteristic fired, so routing by channel means a repeated measurement frame lands in the
     * measurement slot again rather than being mistaken for the next role.
     *
     * Four frames, one shot. The blind cycle would have called the second frame metadata and the
     * third errors, and emitted a shot a frame early.
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

    @Test
    fun sap6SurvivesAFrameItCannotParse() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.SAP6)

        assertEquals(emptyList(), decoder.decode(FrameChannel.DEFAULT, ByteArray(1)))
        assertNull(decoder.acknowledgementFor(FrameChannel.DEFAULT, ByteArray(0)))
    }

    /**
     * The property that matters more than any individual case: no driver may throw, on any frame,
     * on any channel.
     *
     * A truncated or garbled notification is an ordinary event on a marginal Bluetooth link, and a
     * decoder that throws takes the app down with the survey in it. This is also the check that
     * found the divergence worth knowing about: an out-of-bounds read is a catchable exception on
     * the JVM and is not reliably one on Kotlin/Wasm, and Kotlin/Native — the iOS build — is the
     * same family. So the drivers check lengths rather than catching afterwards, and this runs on
     * all three targets to say so.
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

    /**
     * FCL is only complete once both halves arrive, and is acknowledged only then — so an extended
     * packet with no primary before it yields nothing and no reply, leaving the instrument free to
     * resend the shot.
     */
    @Test
    fun fclDoesNotAcknowledgeHalfAShot() {
        val decoder = InstrumentDecoder.forProfile(InstrumentProfile.FCL)

        val outOfSequence = decoder.decode(FrameChannel.EXTENDED, ByteArray(14))
        assertEquals(emptyList(), outOfSequence)
        assertNull(decoder.acknowledgementFor(FrameChannel.EXTENDED, ByteArray(14)))
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
