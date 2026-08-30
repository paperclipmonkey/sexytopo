package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A calibration from the first shot to the coefficients going back on the instrument.
 *
 * The solver underneath has been tested against the Android app's own datasets since early in the
 * port — iteration counts and all, on three targets. What is new is everything around it: the
 * checklist of 56 positions, the reading list, the assessment, and the step to memory writes.
 *
 * Why it matters: an uncalibrated DistoX can be several degrees out, and a survey is a chain of
 * bearings, so the error accumulates along the passage. The cave comes back the wrong shape and
 * nothing in the numbers says so.
 */
class CalibrationRunTest {

    private fun run(readings: Int): CalibrationRun {
        val run = CalibrationRun()
        ExampleCalibration.READINGS.take(readings).forEach(run::add)
        return run
    }

    // ------------------------------------------------------------------------------------
    // The checklist
    // ------------------------------------------------------------------------------------

    /**
     * Fourteen directions rolled through four orientations each.
     *
     * Not presentation: Beat Heeb's algorithm fits sensor errors to readings that between them
     * point the instrument every way and roll it about each one. A calibration taken in a different
     * set of positions is not a worse calibration, it is a wrong one.
     */
    @Test
    fun thereAreFiftySixPositionsAndTheyAreAllDistinct() {
        assertEquals(56, CalibrationPositions.REQUIRED)
        assertEquals(56, CalibrationPositions.ALL.toSet().size)
        assertEquals(14, CalibrationDirection.entries.size)
        assertEquals(4, CalibrationOrientation.entries.size)
    }

    /** Direction-major: four shots without putting the instrument down, then a new direction. */
    @Test
    fun thePositionsAreGroupedByDirection() {
        val firstFour = CalibrationPositions.ALL.take(4)
        assertEquals(1, firstFour.map { it.direction }.toSet().size)
        assertEquals(4, firstFour.map { it.orientation }.toSet().size)
        assertEquals(
            CalibrationDirection.FORWARD,
            CalibrationPositions.ALL.first().direction,
        )
    }

    @Test
    fun theChecklistAdvancesWithTheReadingsAndThenStops() {
        assertEquals(CalibrationPositions.ALL[0], run(0).next)
        assertEquals(CalibrationPositions.ALL[5], run(5).next)
        assertNull(run(56).next)
        assertTrue(run(56).isComplete)
        assertFalse(run(55).isComplete)
    }

    /** "Forward Left Up, Face Right" — the app's own words, so the instructions match. */
    @Test
    fun aPositionReadsAsAnInstruction() {
        assertEquals(
            "Forward, Face Up",
            CalibrationPositions.ALL.first().toString(),
        )
    }

    // ------------------------------------------------------------------------------------
    // The readings
    // ------------------------------------------------------------------------------------

    @Test
    fun readingsAccumulateAndCanBeUndone() {
        val run = run(3)
        assertEquals(3, run.count)
        assertEquals(ExampleCalibration.READINGS[2], run.last)

        run.deleteLast()
        assertEquals(2, run.count)
        assertEquals(ExampleCalibration.READINGS[1], run.last)

        run.clear()
        assertEquals(0, run.count)
        assertNull(run.last)
    }

    /** Undoing an empty run is a no-op rather than a crash: it is a button somebody will press. */
    @Test
    fun undoingNothingIsHarmless() {
        val run = CalibrationRun()
        run.deleteLast()
        assertEquals(0, run.count)
    }

    /**
     * The solver's floor is 16, well below the 56 a proper calibration takes.
     *
     * Worth pinning because it is a trap: the fit will happily run on 16 readings and report a
     * number. It is the *positions* that make the answer meaningful, not the count.
     */
    @Test
    fun theSolverWillRunOnFarFewerReadingsThanACalibrationNeeds() {
        assertFalse(run(15).canSolve)
        assertTrue(run(16).canSolve)
        assertFalse(run(16).isComplete)
    }

    // ------------------------------------------------------------------------------------
    // The fit
    // ------------------------------------------------------------------------------------

    /**
     * The full dataset converges to the answer it is known to produce — and the Android app would
     * call that answer *poor*.
     *
     * Both of the app's own reference calibrations fit at about 0.60, and its own threshold for a
     * good one (`DistoXCalibrationActivity.MAX_ERROR`) is 0.50. So the data the algorithm is
     * verified against would, if a surveyor had just taken it, be reported as a calibration to do
     * again. That is not a bug in this port — the deltas match the Java's to six figures — but it
     * is worth knowing before reading anything into the number: either the threshold is optimistic
     * or those two datasets are mediocre calibrations, and nothing in the app says which.
     */
    @Test
    fun theFullDatasetConvergesToTheAnswerItIsKnownToProduce() {
        val run = run(56)
        val result = run.solve(useNonLinearity = false)

        assertTrue(result.converged, "the fit hit its iteration ceiling")
        assertEquals(43, result.iterations)
        assertTrue(
            result.delta > 0.60f && result.delta < 0.61f,
            "expected the known delta of about 0.603, got ${result.delta}",
        )
        // Above 0.5, so the app's own assessment of its own test data is "poor".
        assertEquals(CalibrationQuality.POOR, run.assess(result))
    }

    /** The non-linear variant fits the same data a little better, and still not to 0.5. */
    @Test
    fun theNonLinearVariantFitsTheSameDataSlightlyBetter() {
        val run = run(56)
        val linear = run.solve(useNonLinearity = false)
        val nonLinear = run.solve(useNonLinearity = true)

        assertTrue(nonLinear.useNonLinearity)
        assertTrue(
            nonLinear.delta < linear.delta,
            "the extra coefficients did not improve the fit",
        )
        assertEquals(CalibrationQuality.POOR, run.assess(nonLinear))
    }

    /** A fit inside the threshold is called good, which is the other half of the same rule. */
    @Test
    fun aFitInsideTheThresholdIsCalledGood() {
        val run = run(56)
        val result = run.solve()
        val asIfBetter = result.copy(delta = 0.4f)

        assertEquals(CalibrationQuality.GOOD, run.assess(asIfBetter))
        assertEquals(
            CalibrationQuality.DID_NOT_SETTLE,
            run.assess(result.copy(iterations = CalibrationAlgorithm.MAX_IT, delta = 0.1f)),
            "a fit that never settled is not good however small its error",
        )
    }

    // ------------------------------------------------------------------------------------
    // Writing it back
    // ------------------------------------------------------------------------------------

    /**
     * The step that actually changes anything: until the coefficients are written, the instrument
     * is still using the ones it had.
     *
     * Four bytes per command from address 0x8010, which is `WriteCalibrationProtocol.go`.
     */
    @Test
    fun theCoefficientsGoBackAsFourByteMemoryWrites() {
        val run = run(56)
        val commands = run.writeCommands(run.solve())

        // 48 coefficient bytes, four at a time.
        assertEquals(12, commands.size)
        assertTrue(commands.all { it.size == 7 })
        assertEquals(
            DistoXProtocol.CALIBRATION_COEFFICIENTS_ADDRESS,
            DistoXProtocol.addressOfWriteCommand(commands.first()),
        )
        // Addresses advance by four, with no gaps.
        val addresses = commands.map { DistoXProtocol.addressOfWriteCommand(it) }
        assertEquals(
            (0 until 12).map { DistoXProtocol.CALIBRATION_COEFFICIENTS_ADDRESS + it * 4 },
            addresses,
        )
    }

    /** The non-linear fit writes four more bytes, and so one more command. */
    @Test
    fun theNonLinearFitWritesOneMoreBlock() {
        val run = run(56)
        assertEquals(13, run.writeCommands(run.solve(useNonLinearity = true)).size)
    }
}
