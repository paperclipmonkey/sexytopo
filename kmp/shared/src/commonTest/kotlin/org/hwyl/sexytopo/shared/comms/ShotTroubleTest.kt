package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.bric.Bric4Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning the instrument's vocabulary into the surveyor's.
 *
 * Written against a real refusal rather than an imagined one: a BRIC4 on a bench next to an
 * iPhone, beeping high-low at every shot and reporting *magnetometer 1 high magnitude*,
 * *magnetometer 2 high magnitude*, *accelerometer 1 high magnitude* and *azimuth calculation
 * problem* in a run. What the app said about that was the four code descriptions, in a log behind
 * the overflow menu. What the surveyor needed was "move the phone away from it".
 */
class ShotTroubleTest {

    /** Every code the instrument can send has a cause, and none of them throws. */
    @Test
    fun everyErrorTheInstrumentCanSendHasAnAnswer() {
        for (error in Bric4Error.entries) {
            val trouble = ShotTrouble.ofBric(error.code)
            assertTrue(
                trouble.summary.isNotBlank() && trouble.whatToDo.isNotBlank(),
                "${error.description} should say something useful",
            )
        }
    }

    /** Including codes that are not in the table at all — 11 is missing from the Java's. */
    @Test
    fun aCodeFromNowhereIsNotACrash() {
        assertEquals(ShotTrouble.UNKNOWN, ShotTrouble.ofBric(11))
        assertEquals(ShotTrouble.UNKNOWN, ShotTrouble.ofBric(255))
        assertEquals(ShotTrouble.UNKNOWN, ShotTrouble.ofBric(-1))
    }

    /**
     * The run this was written for, in the order it arrived.
     *
     * Four codes, three of them naming a different sensor, and one answer: something magnetic is
     * next to the instrument. The accelerometer complaint in the middle must not win — a surveyor
     * told to hold the instrument stiller will hold it stiller, and it will refuse again.
     */
    @Test
    fun aRealRefusalReadsAsTheMagneticOne() {
        val reported =
            listOf(
                Bric4Error.AZIMUTH_ERROR,
                Bric4Error.ACCELEROMETER_1_HIGH_MAGNITUDE,
                Bric4Error.MAGNETOMETER_1_HIGH_MAGNITUDE,
                Bric4Error.MAGNETOMETER_2_HIGH_MAGNITUDE,
            ).map { ShotTrouble.ofBric(it.code) }

        assertEquals(ShotTrouble.MAGNETIC, ShotTrouble.worstOf(reported))
    }

    /** And an instrument that was only knocked says so, rather than sending anybody magnet-hunting. */
    @Test
    fun anInstrumentThatWasOnlyMovedSaysSo() {
        val reported = listOf(ShotTrouble.ofBric(Bric4Error.ACCELEROMETER_1_HIGH_MAGNITUDE.code))

        assertEquals(ShotTrouble.MOVED, ShotTrouble.worstOf(reported))
    }

    /** The laser cases are their own thing: nothing magnetic, nothing moved, aim somewhere else. */
    @Test
    fun aShotThatMissedIsAboutTheTargetAndNotTheCompass() {
        assertEquals(ShotTrouble.NO_RETURN, ShotTrouble.ofBric(Bric4Error.TOO_WEAK.code))
        assertEquals(ShotTrouble.NO_RETURN, ShotTrouble.ofBric(Bric4Error.TOO_REFLECTIVE.code))
    }

    @Test
    fun nothingReportedIsNothingToSay() {
        assertNull(ShotTrouble.worstOf(emptyList()))
    }

    /**
     * The magnetic advice has to name the two things worth doing, in order.
     *
     * Cheap first: move the metal. Only then the expensive one, which is calibrating - and it has
     * to say *where*, because this app cannot calibrate a BRIC at all: `InstrumentFamily.BRIC4`
     * has an empty command set, so there is no start-calibration command to send. Telling a
     * surveyor to calibrate without saying it happens on the instrument sends them into this app's
     * own calibration screen, which will never receive a reading from a BRIC.
     */
    @Test
    fun theMagneticAdviceSaysWhereCalibrationHappens() {
        val advice = ShotTrouble.MAGNETIC.whatToDo

        assertTrue("phone" in advice, "the phone is the magnet the surveyor is holding")
        assertTrue("calibrat" in advice, "calibration is the second suspect")
        assertTrue(
            "on the device" in advice || "on the instrument" in advice,
            "it must say a BRIC is calibrated on the instrument, not from this app",
        )
        assertNotNull(ShotTrouble.worstOf(listOf(ShotTrouble.MAGNETIC)))
    }
}
