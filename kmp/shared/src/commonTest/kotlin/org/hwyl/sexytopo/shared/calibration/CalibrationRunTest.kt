package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentFamily
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXMemoryRange
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A calibration from the first shot to the coefficients going back on the instrument: the
 * checklist of 56 positions, the reading list, the assessment, and the step to memory writes —
 * everything around the solver itself, which is tested elsewhere against the Android app's data.
 * An uncalibrated DistoX can be several degrees out, and a survey is a chain of bearings, so the
 * error accumulates along the passage with nothing in the numbers to say so.
 */
class CalibrationRunTest {

    private fun run(readings: Int): CalibrationRun {
        val run = CalibrationRun()
        ExampleCalibration.READINGS.take(readings).forEach(run::add)
        return run
    }

    /**
     * Fourteen directions rolled through four orientations each — not presentation: Beat Heeb's
     * algorithm fits sensor errors to readings that point the instrument every way and roll it
     * about each one, so a calibration taken in different positions is not worse, it is wrong.
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
     * The solver's floor is 16, well below the 56 a proper calibration takes: a trap, since the fit
     * will happily run on 16 readings and report a number even though it is the *positions*, not
     * the count, that make the answer meaningful.
     */
    @Test
    fun theSolverWillRunOnFarFewerReadingsThanACalibrationNeeds() {
        assertFalse(run(15).canSolve)
        assertTrue(run(16).canSolve)
        assertFalse(run(16).isComplete)
    }

    /**
     * The full dataset converges to the answer it is known to produce — and the Android app's own
     * threshold for a good calibration (`DistoXCalibrationActivity.MAX_ERROR` = 0.50) would call
     * that answer *poor*. Not a bug in this port — the deltas match the Java's to six figures.
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

    /** Four bytes per command from address 0x8010, which is `WriteCalibrationProtocol.go`. */
    @Test
    fun theCoefficientsGoBackAsFourByteMemoryWrites() {
        val run = run(56)
        val commands = run.writeCommands(run.solve())

        assertEquals(12, commands.size)
        assertTrue(commands.all { it.size == 7 })
        assertEquals(
            DistoXProtocol.CALIBRATION_COEFFICIENTS_ADDRESS,
            DistoXProtocol.addressOfWriteCommand(commands.first()),
        )
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

    /**
     * DistoX-BLE and Cavway X1 expect the whole coefficient block in one `data:`-framed memory
     * write, not the per-four-byte dance above. Sending the classic shape to one of these was the
     * actual defect: twelve packets a BLE instrument has no reason to recognise, silently ignored.
     */
    @Test
    fun bleFamiliesGetOneFramedMemoryWriteInstead() {
        val run = run(56)
        val result = run.solve()

        for (family in listOf(InstrumentFamily.DISTOX_BLE, InstrumentFamily.CAVWAY_X1)) {
            val commands = run.writeCommands(result, family)

            assertEquals(1, commands.size, "$family should get one frame, not a dribble")
            val expected =
                DistoXBleFraming.createWriteMemoryPacket(
                    DistoXMemoryRange.CALIBRATION_COEFFICIENTS,
                    result.toBytes(),
                )
            assertTrue(
                commands.single().contentEquals(expected),
                "$family's single frame should be the real BLE memory-write packet",
            )
            val unwrapped = DistoXBleFraming.payloadOrNull(commands.single())
            assertTrue(unwrapped != null && unwrapped.size == result.toBytes().size + 4)
        }
    }

    /** Every other family keeps the classic shape - this is not "BLE good, everything else bad". */
    @Test
    fun otherFamiliesStillGetTheClassicDribble() {
        val run = run(56)
        val result = run.solve()

        for (family in listOf(InstrumentFamily.DISTOX, InstrumentFamily.BRIC4, InstrumentFamily.SAP6)) {
            assertEquals(12, run.writeCommands(result, family).size, "$family should keep the classic shape")
        }
    }
}
