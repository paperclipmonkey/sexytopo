package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session chasing an instrument that has dropped out.
 *
 * `ReconnectionPolicyTest` has the decisions; this has the wiring, which is the half that has
 * historically been wrong in this port — a policy nothing calls, or a clock nothing turns.
 */
class ReconnectionTest {

    private class Flaky : BaseInstrumentTransport() {
        var attempts = 0
        private var open = false

        override val isConnected: Boolean get() = open

        override fun connect() {
            attempts++
            if (openOnConnect) {
                open = true
                emitConnected()
            } else {
                emitFailure("out of range")
            }
        }

        override fun disconnect() {
            open = false
            emitDisconnected()
        }

        override fun send(bytes: ByteArray) = Unit

        var openOnConnect = true

        fun dropOut() {
            open = false
            emitDisconnected("connection lost")
        }
    }

    private var clock = 0L

    private fun session(): Pair<SurveySession, Flaky> {
        val session = SurveySession(Survey("Test"), elapsedMillis = { clock })
        val radio = Flaky()
        // With a profile: the simulator is deliberately never chased, since it cannot drop.
        session.attachForTest(radio, InstrumentDecoder.classicDistoX(), InstrumentProfile.DISTOX_BLE)
        session.autoReconnect = AutoReconnect(enabled = true, windowMinutes = 15)
        return session to radio
    }

    @Test
    fun anInstrumentThatDropsOutIsPickedUpAgain() {
        val (session, radio) = session()
        session.connect()
        assertTrue(session.connected, "the fake radio did not come up")
        val afterFirst = radio.attempts

        radio.dropOut()
        assertFalse(session.connected)

        // Not yet: the app is not to hammer the radio the instant it goes.
        clock += 2_000L
        session.tick()
        assertEquals(afterFirst, radio.attempts, "retried before the interval was up")

        clock += 1_500L
        session.tick()
        assertEquals(afterFirst + 1, radio.attempts, "the instrument was never chased")
        assertTrue(session.connected, "it came back and the session did not notice")
    }

    @Test
    fun withTheSettingOffTheInstrumentIsLeftAlone() {
        val (session, radio) = session()
        session.autoReconnect = AutoReconnect(enabled = false)
        session.connect()
        val afterFirst = radio.attempts

        radio.dropOut()
        clock += 60_000L
        session.tick()

        assertEquals(afterFirst, radio.attempts)
    }

    @Test
    fun anInstrumentPutAwayOnPurposeIsNotChased() {
        val (session, radio) = session()
        session.connect()
        val afterFirst = radio.attempts

        session.disconnect()
        clock += 60_000L
        session.tick()

        assertEquals(afterFirst, radio.attempts, "the app went after an instrument put away")
    }

    /**
     * And it gives up, rather than keeping the radio going all the way out of the cave: a retry
     * every three seconds for two hours is a flat battery on the one device that has the survey.
     */
    @Test
    fun anInstrumentThatNeverComesBackIsGivenUpOn() {
        val (session, radio) = session()
        session.connect()
        radio.openOnConnect = false
        radio.dropOut()

        // Twenty minutes of ticking, which is longer than the fifteen-minute window.
        repeat(20 * 60 * 2) {
            clock += 500L
            session.tick()
        }
        val attemptsWhenTheWindowRanOut = radio.attempts

        // Another five minutes: nothing more should happen.
        repeat(5 * 60 * 2) {
            clock += 500L
            session.tick()
        }

        assertEquals(
            attemptsWhenTheWindowRanOut,
            radio.attempts,
            "still chasing after the window ran out",
        )
        assertTrue(attemptsWhenTheWindowRanOut > 1, "it never tried at all (${radio.attempts})")
    }

    /**
     * A run that succeeds resets the window, so the second bad patch of a long trip gets its own:
     * an instrument that dropped once at the entrance would otherwise be unchaseable hours later.
     */
    @Test
    fun anInstrumentThatComesBackGetsAFreshWindow() {
        val (session, radio) = session()
        session.connect()

        radio.dropOut()
        clock += 3_500L
        session.tick()
        assertTrue(session.connected, "the first drop was not recovered")

        // Four hours later.
        clock += 4L * 60 * 60 * 1000
        radio.dropOut()
        val afterSecondDrop = radio.attempts
        clock += 3_500L
        session.tick()

        assertEquals(
            afterSecondDrop + 1,
            radio.attempts,
            "the window from the first drop was still being counted hours later",
        )
    }
}
