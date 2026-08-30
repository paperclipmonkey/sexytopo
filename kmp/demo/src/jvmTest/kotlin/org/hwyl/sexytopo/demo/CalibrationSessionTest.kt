package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import org.hwyl.sexytopo.shared.model.survey.Survey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Calibrating, driven end to end against the simulated instrument.
 *
 * The last of the app's big screens to be reachable, and the one whose absence mattered most: an
 * uncalibrated DistoX can be several degrees out, a survey is a chain of bearings, and the error
 * accumulates along the passage. The cave comes back the wrong shape and nothing in the numbers
 * says so.
 *
 * Everything below this was ported and tested long before anything could reach it. What these
 * cover is the part that was missing — the command going out, the two frames of each shot being
 * paired into a reading, calibration readings being kept out of the survey, and the coefficients
 * going back.
 */
class CalibrationSessionTest {

    private fun session(): SurveySession {
        val session = SurveySession(Survey("Cal"))
        session.connect()
        return session
    }

    /** Starting sends the command the instrument is waiting for, not just a flag. */
    @Test
    fun startingSendsTheCommandAndStoppingSendsTheOther() {
        val session = session()

        assertTrue(session.startCalibration())
        assertTrue(session.calibrating)
        assertTrue(session.simulator.isCalibrating, "the instrument was never told")

        session.stopCalibration()
        assertFalse(session.calibrating)
        assertFalse(session.simulator.isCalibrating)
    }

    /** The two frames of a shot become one reading, in the order the instrument sends them. */
    @Test
    fun eachShotArrivesAsTwoFramesAndBecomesOneReading() {
        val session = session()
        session.startCalibration()

        repeat(3) { assertTrue(session.simulateCalibrationReading()) }

        assertEquals(3, session.calibration.count)
        assertEquals(ExampleCalibration.READINGS[2], session.calibration.last)
    }

    /**
     * A calibration reading must never reach the survey.
     *
     * They are raw sensor counts, not a shot; letting one through would put a leg of nonsense
     * length and bearing into the cave, and the surveyor is not looking at the plan while
     * calibrating.
     */
    @Test
    fun calibrationReadingsNeverBecomeSurveyLegs() {
        val session = session()
        session.startCalibration()

        repeat(10) { session.simulateCalibrationReading() }

        assertEquals(10, session.calibration.count)
        assertEquals(0, session.readingsTaken)
        assertEquals(1, session.survey.getAllStations().size, "only the origin should exist")
    }

    /** The checklist advances as shots arrive, so the screen always says what to do next. */
    @Test
    fun theNextPositionFollowsTheReadings() {
        val session = session()
        session.startCalibration()

        assertEquals(CalibrationPositions.ALL[0], session.calibration.next)
        session.simulateCalibrationReading()
        assertEquals(CalibrationPositions.ALL[1], session.calibration.next)
    }

    /** A whole calibration, from the first shot to the coefficients going out over the radio. */
    @Test
    fun awholeCalibrationEndsWithCoefficientsOnTheInstrument() {
        val session = session()
        session.startCalibration()

        while (session.simulateCalibrationReading()) {
            // the 56 readings of a real calibration
        }

        assertTrue(session.calibration.isComplete)
        val result = session.calibration.solve()

        val before = session.simulator.commandsReceived.size
        val blocks = session.writeCalibration(result)

        assertEquals(12, blocks)
        assertTrue(
            session.simulator.commandsReceived.size > before,
            "the coefficients never left the phone",
        )
    }

    /** Undo and clear reach the instrument's readings, not just the screen. */
    @Test
    fun aShotCanBeUndoneAndTheRunCleared() {
        val session = session()
        session.startCalibration()
        repeat(4) { session.simulateCalibrationReading() }

        session.deleteLastCalibrationReading()
        assertEquals(3, session.calibration.count)

        session.clearCalibration()
        assertEquals(0, session.calibration.count)
    }

    /**
     * The simulate button is for the simulator.
     *
     * With a real instrument attached it does nothing, because a button that invented readings
     * while a DistoX was connected would be putting somebody else's calibration on their device.
     */
    @Test
    fun theSimulatedShotButtonDoesNothingWithARealInstrumentAttached() {
        val session = session()
        session.attachForTest(SimulatedInstrument(), InstrumentDecoder.classicDistoX())
        session.connect()
        session.startCalibration()

        // A different transport from `session.simulator`, so this is the "not the demo" path.
        assertFalse(session.simulateCalibrationReading())
        assertEquals(0, session.calibration.count)
    }

    /** FCL exposes no calibration commands at all, and the screen has to be able to say so. */
    @Test
    fun anInstrumentWithNoCalibrationCommandsSaysSo() {
        val fcl =
            InstrumentDecoder.forProfile(
                org.hwyl.sexytopo.shared.comms.InstrumentProfile.ALL.first {
                    it.namePrefix.startsWith("FCL")
                },
            )
        assertEquals(null, fcl.encodeCommand(InstrumentCommand.START_CALIBRATION))
        // ...while the ones that can, do.
        assertTrue(
            InstrumentDecoder.classicDistoX()
                .encodeCommand(InstrumentCommand.START_CALIBRATION)!!
                .isNotEmpty(),
        )
    }
}
