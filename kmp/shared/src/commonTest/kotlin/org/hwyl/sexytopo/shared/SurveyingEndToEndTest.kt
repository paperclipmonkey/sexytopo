package org.hwyl.sexytopo.shared

import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveySettings
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole surveying pipeline, end to end, with only the radio simulated: an instrument emits
 * DistoX packets, the ported protocol decodes them, and the ported survey engine turns three
 * agreeing readings into a station.
 */
class SurveyingEndToEndTest {

    /** Three readings of the same leg, disagreeing by the amount a real instrument does. */
    private fun triple(distance: Float, azimuth: Float, inclination: Float): List<Leg> =
        listOf(
            Leg(distance, azimuth, inclination),
            Leg(distance + 0.01f, azimuth + 0.3f, inclination + 0.2f),
            Leg(distance - 0.02f, azimuth - 0.4f, inclination + 0.1f),
        )

    private class Recorder(private val survey: Survey) : InstrumentTransportListener {
        var readings = 0
        var stationsCreated = 0

        override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
            if (!DistoXProtocol.isDataPacket(bytes)) return
            readings++
            val leg = DistoXProtocol.parseMeasurement(bytes)
            if (SurveyUpdater.update(survey, leg, settings = SurveySettings.DEFAULT)) {
                stationsCreated++
            }
        }
    }

    @Test
    fun threeAgreeingReadingsBuildAStation() {
        val survey = Survey("Test")
        val recorder = Recorder(survey)
        val instrument = SimulatedInstrument(script = triple(5.42f, 12.5f, -3.0f))
        instrument.observe(recorder)
        instrument.connect()

        while (instrument.hasMoreShots) instrument.emitNextShot()

        assertEquals(3, recorder.readings, "all three readings should decode")
        assertEquals(1, recorder.stationsCreated, "an agreeing triple makes exactly one station")
        assertEquals(2, survey.getAllStations().size, "origin plus the new station")
        assertEquals("2", survey.activeStation.name, "the new station becomes active")
    }

    @Test
    fun readingsThatDisagreeStaySplays() {
        val survey = Survey("Test")
        val recorder = Recorder(survey)
        // Genuinely different directions: a surveyor shooting the walls, not the same leg.
        val instrument =
            SimulatedInstrument(
                script =
                    listOf(
                        Leg(2.0f, 10f, 0f),
                        Leg(3.5f, 120f, 5f),
                        Leg(1.8f, 250f, -10f),
                    ),
            )
        instrument.observe(recorder)
        instrument.connect()

        while (instrument.hasMoreShots) instrument.emitNextShot()

        assertEquals(3, recorder.readings)
        assertEquals(0, recorder.stationsCreated, "disagreeing readings are splays, not a station")
        assertEquals(1, survey.getAllStations().size, "just the origin")
        assertEquals(3, survey.origin.getUnconnectedOnwardLegs().size, "kept as three splays")
    }

    @Test
    fun aWholeSurveyIsBuiltFromAScriptOfTriples() {
        val survey = Survey("Live")
        val recorder = Recorder(survey)
        val legs =
            listOf(
                Triple(5.42f, 12.5f, -3.0f),
                Triple(8.13f, 15.0f, 1.5f),
                Triple(3.97f, 88.0f, -12.0f),
                Triple(12.60f, 91.5f, 0.0f),
            )
        val script = legs.flatMap { (d, a, i) -> triple(d, a, i) }
        val instrument = SimulatedInstrument(script = script)
        instrument.observe(recorder)
        instrument.connect()

        while (instrument.hasMoreShots) instrument.emitNextShot()

        assertEquals(12, recorder.readings)
        assertEquals(4, recorder.stationsCreated, "four triples make four stations")
        assertEquals(5, survey.getAllStations().size, "origin plus four")
        assertEquals(listOf("1", "2", "3", "4", "5"), survey.getAllStations().map { it.name })

        val plan = Projection2D.PLAN.project(survey)
        assertEquals(5, plan.stationMap.size)
        assertTrue(plan.legMap.isNotEmpty())
    }

    @Test
    fun theSurveyBuiltThisWayRoundTripsThroughTheNativeFormat() {
        val survey = Survey("Live")
        val recorder = Recorder(survey)
        val instrument = SimulatedInstrument(script = triple(7.5f, 45f, 5f))
        instrument.observe(recorder)
        instrument.connect()
        while (instrument.hasMoreShots) instrument.emitNextShot()

        val json = org.hwyl.sexytopo.shared.io.SurveyJson.write(survey)
        val reloaded = org.hwyl.sexytopo.shared.io.SurveyJson.parse(json)

        assertEquals(survey.getAllStations().size, reloaded.getAllStations().size)
        assertEquals(survey.getAllLegs().size, reloaded.getAllLegs().size)
        assertEquals("2", reloaded.activeStation.name)
    }

    @Test
    fun theInstrumentIsToldToFireItsLaserAndShoot() {
        // The command vocabulary is shared by five instrument families, so it is worth pinning.
        val instrument = SimulatedInstrument()
        instrument.connect()
        instrument.send(byteArrayOf(InstrumentCommand.LASER_ON.code.toByte()))
        instrument.send(byteArrayOf(InstrumentCommand.TAKE_SHOT.code.toByte()))

        assertTrue(instrument.isLaserOn, "0x36 should switch the laser on")
        assertEquals(
            listOf(
                InstrumentCommand.LASER_ON.code.toByte(),
                InstrumentCommand.TAKE_SHOT.code.toByte(),
            ),
            instrument.commandsReceived,
        )
    }
}
