package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.LinkState
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the dot says.
 *
 * The trip that prompted this asked for the instrument's state to be visible somewhere, and every
 * one of these states was reachable before there was anywhere to show it: the app knew it was
 * reconnecting and the surveyor did not.
 */
class ConnectionStatusTest {

    private class Radio : BaseInstrumentTransport() {
        var open = false
        var reportedState: LinkState? = null

        override val isConnected: Boolean get() = open

        override val linkState: LinkState
            get() = reportedState ?: if (open) LinkState.CONNECTED else LinkState.IDLE

        override fun connect() {
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

    private fun session(): Pair<SurveySession, Radio> {
        val session = SurveySession(Survey("Test"), elapsedMillis = { clock })
        val radio = Radio()
        session.attachForTest(radio, InstrumentDecoder.classicDistoX(), InstrumentProfile.BRIC4)
        session.autoReconnect = AutoReconnect(enabled = true, windowMinutes = 15)
        return session to radio
    }

    @Test
    fun withNoInstrumentThereIsNothingToSay() {
        val session = SurveySession(Survey("Test"))
        assertEquals(ConnectionStatus.NONE, connectionStatusOf(session))
    }

    /** The demo's own instrument is not a radio, and must not be drawn as one that is working. */
    @Test
    fun theSimulatorIsNotMistakenForAnInstrument() {
        val session = SurveySession(Survey("Test"))
        session.useSimulator()
        session.connect()
        assertEquals(ConnectionStatus.SIMULATED, connectionStatusOf(session))
    }

    @Test
    fun aWorkingInstrumentReadsAsConnected() {
        val (session, _) = session()
        session.connect()
        assertEquals(ConnectionStatus.CONNECTED, connectionStatusOf(session))
    }

    /**
     * The distinction the surveyor is actually asking about: is the app dealing with this?
     *
     * "Reconnecting" means wait, and "Not connected" means do something. Drawing both as the same
     * red dot would be worse than drawing neither.
     */
    @Test
    fun aChasedInstrumentReadsDifferentlyFromAnAbandonedOne() {
        val (session, radio) = session()
        session.connect()
        radio.openOnConnect = false
        radio.dropOut()

        assertEquals(ConnectionStatus.RECONNECTING, connectionStatusOf(session))
        assertTrue(!connectionStatusOf(session).needsAttention, "asked for help while coping")

        // Twenty minutes of ticking: past the fifteen-minute window, so the chase is over.
        repeat(20 * 60 * 2) {
            clock += 500L
            session.tick()
        }

        val abandoned = connectionStatusOf(session)
        assertNotEquals(ConnectionStatus.RECONNECTING, abandoned, "still says it is trying")
        assertTrue(abandoned.needsAttention, "gave up without saying so")
    }

    /** With chasing turned off, one failed attempt is the surveyor's to deal with straight away. */
    @Test
    fun aFailureNobodyIsChasingAsksForAttention() {
        val (session, radio) = session()
        session.autoReconnect = AutoReconnect(enabled = false)
        radio.openOnConnect = false
        session.connect()

        assertEquals(ConnectionStatus.FAILED, connectionStatusOf(session))
        assertTrue(connectionStatusOf(session).needsAttention)
    }

    /** Every state has a colour, and the two that mean trouble do not share one with the rest. */
    @Test
    fun everyStateIsDrawnAndTroubleIsNotDrawnAsAnythingElse() {
        for (dark in listOf(false, true)) {
            val trouble =
                ConnectionStatus.entries.filter { it.needsAttention }.map { colourOf(it, dark) }
            val rest =
                ConnectionStatus.entries.filterNot { it.needsAttention }.map { colourOf(it, dark) }
            assertTrue(trouble.none { it in rest }, "trouble is the same colour as something else")
            assertEquals(ConnectionStatus.entries.size, trouble.size + rest.size)
        }
    }
}
