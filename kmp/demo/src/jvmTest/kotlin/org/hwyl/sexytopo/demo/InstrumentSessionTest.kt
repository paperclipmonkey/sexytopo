package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentFamily
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.distox.DistoXBlePackets
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session with a radio on the end of it.
 *
 * The simulated instrument and a real one now go through one code path, so this exercises the
 * half a cave would: frames arrive, get decoded, get acknowledged, and build the survey.
 */
class InstrumentSessionTest {

    /** A transport that records what was written back and lets a test push frames in. */
    private class FakeInstrument : BaseInstrumentTransport() {
        val written = mutableListOf<ByteArray>()
        private var open = false

        override val isConnected: Boolean get() = open

        override fun connect() {
            open = true
            emitConnected()
        }

        override fun disconnect() {
            open = false
            emitDisconnected()
        }

        override fun send(bytes: ByteArray) {
            written += bytes
        }

        fun arrive(bytes: ByteArray, channel: FrameChannel = FrameChannel.DEFAULT) =
            emitFrame(bytes, channel)
    }

    private fun distoXBleFrame(distanceMillimetres: Int): ByteArray =
        ByteArray(17).also {
            it[0] = DistoXBlePackets.MEASUREMENT_IDENTIFIER
            it[1] = 0x01
            it[2] = (distanceMillimetres and 0xFF).toByte()
            it[3] = ((distanceMillimetres shr 8) and 0xFF).toByte()
        }

    /**
     * Three agreeing readings from a real instrument make a station, exactly as three typed ones
     * do — because by the time they reach [org.hwyl.sexytopo.shared.survey.SurveyUpdater] there is
     * nothing to tell them apart.
     */
    @Test
    fun readingsFromAnInstrumentBuildTheSurvey() {
        val survey = Survey("T")
        val session = SurveySession(survey)
        val instrument = FakeInstrument()
        session.attachForTest(instrument, InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE))

        session.connect()
        assertTrue(session.connected)

        repeat(3) { instrument.arrive(distoXBleFrame(10000)) }

        assertEquals(3, session.readingsTaken)
        assertEquals(2, survey.getAllStations().size)
        assertEquals(10.0f, session.lastReading?.distance)
    }

    /**
     * The failure that is invisible until it is too late. Four of these instruments wait for an
     * acknowledgement before sending the next shot, so a session that decodes but never replies
     * takes one reading and then looks exactly like a flat battery.
     */
    @Test
    fun everyMeasurementIsAcknowledged() {
        val session = SurveySession(Survey("T"))
        val instrument = FakeInstrument()
        session.attachForTest(instrument, InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE))
        session.connect()

        instrument.arrive(distoXBleFrame(5000))

        assertEquals(1, instrument.written.size)
        assertTrue(instrument.written.single().isNotEmpty())
    }

    @Test
    fun anUnreadableFrameIsSurvived() {
        val session = SurveySession(Survey("T"))
        val instrument = FakeInstrument()
        session.attachForTest(instrument, InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE))
        session.connect()

        instrument.arrive(ByteArray(3))
        instrument.arrive(distoXBleFrame(7000))

        assertEquals(1, session.readingsTaken)
    }

    /**
     * Swapping instruments has to unhook the old one. A transport left observed goes on feeding
     * readings into the survey from something the surveyor believes they have put away — which,
     * with two instruments in a bag, is a survey quietly gaining legs nobody shot.
     */
    @Test
    fun theOldInstrumentStopsFeedingTheSurvey() {
        val session = SurveySession(Survey("T"))
        val first = FakeInstrument()
        val second = FakeInstrument()
        session.attachForTest(first, InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE))
        session.connect()
        session.attachForTest(second, InstrumentDecoder.forProfile(InstrumentProfile.DISTOX_BLE))
        session.connect()

        first.arrive(distoXBleFrame(9000))

        assertEquals(0, session.readingsTaken)
    }

    @Test
    fun theSimulatorStillWorksThroughTheSamePath() {
        val survey = Survey("T")
        val session = SurveySession(survey)

        repeat(3) { session.takeReading() }

        assertEquals(3, session.readingsTaken)
        assertEquals(2, survey.getAllStations().size)
    }
    /**
     * A decoder that throws does not take the app with it.
     *
     * Every byte from a radio reaches `onFrame`, on the main thread, and none of it is under
     * anybody's control. The decoders' guards are written against each protocol *as documented*,
     * and the instruments this port has never met are exactly the ones likely to send something
     * the documentation does not cover — a truncated notification, a firmware revision with a
     * field more, a device whose advertised name matched a profile it does not really speak. On
     * iOS a Kotlin exception raised inside a CoreBluetooth callback ends the process.
     *
     * Losing the app is not the same as losing a packet. The survey is written on every change,
     * so a crash costs at most the shot in flight — and also the connection, the screen, and a
     * surveyor's confidence in the thing holding their trip. A line in the log costs a re-shoot.
     */
    @Test
    fun aPacketTheDecoderCannotReadIsLoggedRatherThanFatal() {
        val survey = Survey("Bad packets")
        val session = SurveySession(survey)
        val radio = FakeInstrument()
        session.attachForTest(radio, ThrowingDecoder())
        session.connect()

        radio.arrive(byteArrayOf(0x01, 0x02, 0x03))

        assertTrue(session.connected, "one unreadable packet closed the link")
        assertEquals(0, session.readingsTaken, "an unreadable packet became a reading")
        assertTrue(
            session.log.any { it.contains("could not read a packet") },
            "nothing in the log says a packet was dropped: ${session.log}",
        )
    }

    /** Stands in for an instrument speaking a dialect no decoder here knows. */
    private class ThrowingDecoder : InstrumentDecoder() {
        override val driverName = "throws"

        override val family = InstrumentFamily.DISTOX_BLE

        override fun decode(channel: FrameChannel, bytes: ByteArray): List<InstrumentPacket> =
            error("a field this firmware revision added")
    }
}
