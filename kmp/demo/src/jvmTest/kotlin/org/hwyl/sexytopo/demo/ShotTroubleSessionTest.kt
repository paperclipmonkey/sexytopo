package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.ShotTrouble
import org.hwyl.sexytopo.shared.comms.bric.Bric4Error
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A BRIC refusing to shoot, from the frame on the wire to the words on the screen.
 *
 * Written from a real session: a BRIC4 connected, beeping high-low at every shot, and a log full of
 * *magnetometer 1 high magnitude*. Everything under this worked - the radio, the routing by
 * characteristic, the decoder, the error table - and the app still told the surveyor nothing they
 * could act on, because the only thing it did with a refusal was print the instrument's own word
 * for it into a log four taps away.
 */
class ShotTroubleSessionTest {

    private class FakeInstrument : BaseInstrumentTransport() {
        private var open = false
        override val isConnected: Boolean get() = open
        override fun connect() { open = true; emitConnected() }
        override fun disconnect() { open = false; emitDisconnected() }
        override fun send(bytes: ByteArray) = Unit
        fun arrive(bytes: ByteArray, channel: FrameChannel) = emitFrame(bytes, channel)
    }

    /**
     * The 58d3 frame: `uint8 code, float, float` at offset 0, and the same again at offset 9.
     *
     * Twenty bytes and not eighteen, which is the length the *parser* needs: `BricDecoder` drops
     * anything shorter than twenty on purpose, so a test frame that is merely long enough to parse
     * is one the app would throw away.
     */
    private fun errorFrame(
        first: Bric4Error,
        second: Bric4Error? = null,
        firstValue: Float = 0f,
        secondValue: Float = 0f,
    ): ByteArray =
        ByteArray(BRIC_FRAME).also {
            it[0] = first.code.toByte()
            putFloat(it, 1, firstValue)
            if (second != null) {
                it[9] = second.code.toByte()
                putFloat(it, 10, secondValue)
            }
        }

    private fun putFloat(into: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (i in 0 until 4) into[at + i] = ((bits shr (8 * i)) and 0xFF).toByte()
    }

    private fun goodShotFrames(instrument: FakeInstrument) {
        instrument.arrive(ByteArray(BRIC_FRAME), FrameChannel.PRIMARY)
        instrument.arrive(ByteArray(BRIC_FRAME), FrameChannel.EXTENDED)
        instrument.arrive(ByteArray(BRIC_FRAME), FrameChannel.TERTIARY)
    }

    private companion object {
        // `BricDecoder.MINIMUM_FRAME`: shorter indications are dropped rather than parsed.
        const val BRIC_FRAME = 20
    }

    private fun connectedToABric(): Pair<SurveySession, FakeInstrument> {
        val session = SurveySession(Survey("T"))
        val instrument = FakeInstrument()
        session.attachForTest(
            instrument,
            InstrumentDecoder.forProfile(InstrumentProfile.BRIC4),
            profile = InstrumentProfile.BRIC4,
        )
        session.connect()
        return session to instrument
    }

    @Test
    fun anInstrumentThatHasNotComplainedHasNothingToSay() {
        val (session, _) = connectedToABric()

        assertNull(session.trouble, "a fresh connection is not a problem")
    }

    /** A magnetometer complaint and the azimuth failure it causes, arriving in one frame. */
    @Test
    fun aRefusedShotSaysWhatIsWrongInWordsASurveyorCanAct0n() {
        val (session, instrument) = connectedToABric()

        instrument.arrive(
            errorFrame(Bric4Error.MAGNETOMETER_1_HIGH_MAGNITUDE, Bric4Error.AZIMUTH_ERROR),
            FrameChannel.TERTIARY,
        )

        assertEquals(ShotTrouble.MAGNETIC, session.trouble)
        assertTrue(
            "phone" in (session.trouble?.whatToDo ?: ""),
            "the advice has to name the magnet the surveyor is holding",
        )
    }

    /**
     * And the accelerometer complaint that arrives alongside does not win: told to hold the
     * instrument stiller, a surveyor holds it stiller, and it refuses again, because the actual
     * problem is a phone lying next to it.
     */
    @Test
    fun theSensorThatSaysWhatToDoIsTheOneReported() {
        val (session, instrument) = connectedToABric()

        instrument.arrive(
            errorFrame(Bric4Error.ACCELEROMETER_1_HIGH_MAGNITUDE),
            FrameChannel.TERTIARY,
        )
        assertEquals(ShotTrouble.MOVED, session.trouble)

        instrument.arrive(
            errorFrame(Bric4Error.MAGNETOMETER_2_HIGH_MAGNITUDE),
            FrameChannel.TERTIARY,
        )
        assertEquals(
            ShotTrouble.MAGNETIC,
            session.trouble,
            "a magnetic complaint on top of a movement one is the one to act on",
        )
    }

    /**
     * And it goes when a shot gets through: a banner that stays up after the problem is fixed is
     * worse than no banner, because next time the instrument really does refuse, nobody looks at it.
     */
    @Test
    fun aShotThatGetsThroughTakesTheWarningAway() {
        val (session, instrument) = connectedToABric()

        instrument.arrive(
            errorFrame(Bric4Error.MAGNETOMETER_1_HIGH_MAGNITUDE),
            FrameChannel.TERTIARY,
        )
        assertEquals(ShotTrouble.MAGNETIC, session.trouble)

        goodShotFrames(instrument)

        assertNull(session.trouble, "a reading arrived, so the refusal is history")
        assertNull(session.troubleDetail, "and the numbers go with it")
        assertTrue(session.readingsTaken > 0, "and the check above must be about a real shot")
    }

    /**
     * The numbers the instrument sent reach the screen, because they are the ones that move: the
     * advice is "walk outside", and a code that reads *magnetometer 1 high magnitude* before and
     * after the walk tells the surveyor nothing about whether it worked. The number does.
     */
    @Test
    fun theInstrumentsOwnNumbersReachTheSurveyor() {
        val (session, instrument) = connectedToABric()

        instrument.arrive(
            errorFrame(
                Bric4Error.MAGNETOMETER_1_HIGH_MAGNITUDE,
                Bric4Error.MAGNETOMETER_2_HIGH_MAGNITUDE,
                firstValue = 0.8235f,
                secondValue = 0.8398f,
            ),
            FrameChannel.TERTIARY,
        )

        val detail = session.troubleDetail ?: ""
        assertTrue("0.8235" in detail, "the first magnitude should be shown: $detail")
        assertTrue("0.8398" in detail, "and the second: $detail")
        assertTrue(
            session.log.any { "0.8235" in it },
            "the log should record which reading was refused, not just that one was",
        )
    }

    @Test
    fun aBricCannotBeCalibratedFromTheApp() {
        val (session, _) = connectedToABric()

        assertTrue(
            !session.canCalibrate,
            "InstrumentFamily.BRIC4 has an empty command set, as Bric4Manager does",
        )
    }

    /**
     * The frame trace says whether anything arrived at all.
     *
     * An instrument that appears to be shooting while the survey stays empty looks, from the
     * surveyor's side, exactly like a radio that never connected — and which of those two it is
     * decides what to try next. It cannot be worked out afterwards from a survey with no legs in it.
     */
    @Test
    fun theFrameTraceSaysWhatArrivedAndWhetherItMeantAnything() {
        val (session, instrument) = connectedToABric()
        session.traceFrames = true

        // A metadata frame on its own: real bytes, correctly routed, and no packet out of it.
        instrument.arrive(ByteArray(BRIC_FRAME), FrameChannel.EXTENDED)

        assertTrue(
            session.log.any { "EXTENDED" in it && "20 bytes" in it },
            "the trace should say which characteristic and how much: ${session.log}",
        )
        assertTrue(
            session.log.any { "decoded to nothing" in it },
            "and that this one meant nothing, which is the whole point",
        )
    }

    @Test
    fun theTraceIsSilentUntilItIsAskedFor() {
        val (session, instrument) = connectedToABric()

        instrument.arrive(ByteArray(BRIC_FRAME), FrameChannel.EXTENDED)

        assertTrue(
            session.log.none { "bytes" in it },
            "nothing should be traced by default: ${session.log}",
        )
    }
}
