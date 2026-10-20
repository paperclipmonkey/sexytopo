package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Android app's `CalibrationCoverageTest`, ported, and a gap the Java test leaves out. */
class CalibrationCoverageTest {

    @Test
    fun aFullCalibrationMeetsEveryTarget() {
        val coverage = CalibrationCoverage.of(ExampleCalibration.READINGS)

        assertEquals(14, coverage.setCount)
        assertEquals(14, coverage.completeSetCount)
        for (pointing in ReadingDirection.Pointing.entries) {
            assertTrue(coverage.isTargetMet(pointing), "$pointing")
        }
    }

    @Test
    fun aRepeatedDirectionCountsOnce() {
        val readings = ExampleCalibration.READINGS
        // Shoot the first horizontal set (readings 9 to 12) again instead of the next one.
        val repeated = readings.toMutableList()
        for (i in 0 until 4) repeated[12 + i] = readings[8 + i]

        val coverage = CalibrationCoverage.of(repeated)

        assertEquals(13, coverage.setCount)
        assertEquals(3, coverage.directionCount(ReadingDirection.Pointing.HORIZONTAL))
        assertFalse(coverage.isTargetMet(ReadingDirection.Pointing.HORIZONTAL))
    }

    /** A retake waiting to be shot splits its set, but the direction is still covered. */
    @Test
    fun aGapSplitsASetWithoutLosingTheDirection() {
        val withGap: MutableList<CalibrationReading?> = ExampleCalibration.READINGS.toMutableList()
        withGap[1] = null

        val coverage = CalibrationCoverage.of(withGap)

        assertEquals(15, coverage.setCount)
        assertEquals(13, coverage.completeSetCount)
        assertTrue(ReadingDirection.Pointing.entries.all(coverage::isTargetMet))
    }
}
