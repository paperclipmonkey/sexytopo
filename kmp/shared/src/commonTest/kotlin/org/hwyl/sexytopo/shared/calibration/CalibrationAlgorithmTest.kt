package org.hwyl.sexytopo.shared.calibration

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Android app's own `CalibrationCalculatorTest`, ported.
 *
 * The iteration counts are the point. A calibration is an iterative fit that stops when the
 * coefficients move by less than 1e-6, so the number of rounds it takes is exquisitely sensitive to
 * the arithmetic: a single unit in the last place of difference, early on, compounds into a
 * different count. Asserting 43, 75 and 53 exactly — against real instrument data, with numbers
 * that came from PocketTopo — is therefore a far stronger check than comparing the coefficients
 * themselves would be.
 *
 * That is also what makes this suite worth running on every target rather than just the JVM. It is
 * the most numerically demanding thing in the port, so it is the best evidence that Kotlin/Native
 * and Kotlin/Wasm compute the same answers the Android app does.
 */
class CalibrationAlgorithmTest {

    private val allowedDelta = 0.0001f

    @Test
    fun optVectorsMatchTheJavaOnASample() {
        val gr = Vector(2.32360125f, 0.0007143398f, 0.124852136f)
        val mr = Vector(2.84201765f, 0.8729558f, 0.9116659f)
        val alpha = 0.399245948f

        val (gx, mx) = CalibrationAlgorithm.optVectors(gr, mr, alpha)

        assertVectorEquals(Vector(0.9988797f, -0.00771637028f, 0.04668929f), gx)
        assertVectorEquals(Vector(0.9106779f, 0.286284238f, 0.297837347f), mx)
    }

    @Test
    fun turnVectorsMatchTheJavaOnASample() {
        val gxp = Vector(0.9988797f, -0.00771637028f, 0.04668929f)
        val mxp = Vector(0.9106779f, 0.286284238f, 0.297837347f)
        val gr = Vector(0.5807441f, -0.00199999986f, 0.04895164f)
        val mr = Vector(0.713514864f, 0.229811013f, 0.239675581f)

        val (gx, mx) = CalibrationAlgorithm.turnVectors(gxp, mxp, gr, mr)

        assertVectorEquals(Vector(0.9988797f, -0.00767776743f, 0.0466956533f), gx)
        assertVectorEquals(Vector(0.9106779f, 0.286530375f, 0.297600567f), mx)
    }

    @Test
    fun exampleCalibrationIsAssessedCorrectly() {
        val readings = toReadings(EXAMPLE_ONE)

        val linear = CalibrationAlgorithm.calibrate(readings, useNonLinearity = false)
        assertEquals(43, linear.iterations)
        assertEqualsWithin(0.603272f, linear.delta)

        val nonLinear = CalibrationAlgorithm.calibrate(readings, useNonLinearity = true)
        assertEquals(75, nonLinear.iterations)
        assertEqualsWithin(0.5775869f, nonLinear.delta)
    }

    @Test
    fun secondExampleCalibrationIsAssessedCorrectly() {
        val readings = toReadings(EXAMPLE_TWO)

        val linear = CalibrationAlgorithm.calibrate(readings, useNonLinearity = false)
        assertEquals(53, linear.iterations)
        assertEqualsWithin(0.6157666f, linear.delta)

        // The Java's own comment: this takes 60 iterations where PocketTopo takes 64, and it
        // reaches the same answer, so the count is not asserted there and is not asserted here.
        val nonLinear = CalibrationAlgorithm.calibrate(readings, useNonLinearity = true)
        assertEqualsWithin(0.6132727f, nonLinear.delta)
    }

    @Test
    fun bothExamplesConverge() {
        for (data in listOf(EXAMPLE_ONE, EXAMPLE_TWO)) {
            for (nonLinear in listOf(false, true)) {
                val result = CalibrationAlgorithm.calibrate(toReadings(data), nonLinear)
                assertTrue(result.converged, "fit hit the iteration ceiling")
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // The grouped loop
    // -------------------------------------------------------------------------------------

    /**
     * The first sixteen readings are processed as four groups of four, the rest individually.
     *
     * This is the part of the port most likely to be got wrong and least likely to complain: the
     * Java reassigns its `for` variable from two nested loops and then decrements it, which Kotlin
     * cannot express directly. Regrouping the samples throws nothing — it just produces a
     * different, quietly wrong answer. Reordering readings *within* a group must therefore change
     * nothing that matters, while moving one across a group boundary must.
     */
    @Test
    fun readingsAreGroupedInFours() {
        val readings = toReadings(EXAMPLE_ONE)
        val baseline = CalibrationAlgorithm.calibrate(readings, useNonLinearity = false)

        // Swapping two readings across a group boundary (index 3 and 4) changes the fit.
        val acrossBoundary = readings.toMutableList()
        acrossBoundary[3] = readings[4]
        acrossBoundary[4] = readings[3]
        val moved = CalibrationAlgorithm.calibrate(acrossBoundary, useNonLinearity = false)

        assertTrue(
            abs(moved.delta - baseline.delta) > 1e-6f || moved.iterations != baseline.iterations,
            "swapping readings across a group boundary should change the fit, " +
                "which it does not if the grouping is wrong",
        )
    }

    @Test
    fun tooFewReadingsIsRejectedRatherThanReadingOffTheEnd() {
        val readings = toReadings(EXAMPLE_ONE).take(12)
        assertFailsWith<IllegalArgumentException> {
            CalibrationAlgorithm.calibrate(readings, useNonLinearity = false)
        }
    }

    @Test
    fun mismatchedReadingCountsAreRejected() {
        val (g, m) = CalibrationAlgorithm.readingsToVectors(toReadings(EXAMPLE_ONE))
        assertFailsWith<IllegalArgumentException> {
            CalibrationAlgorithm.optimise(g, m.dropLast(1), useNonLinearity = false)
        }
    }

    // -------------------------------------------------------------------------------------
    // Encoding for the instrument
    // -------------------------------------------------------------------------------------

    @Test
    fun coefficientsPackToTheExpectedLength() {
        val readings = toReadings(EXAMPLE_ONE)
        assertEquals(48, CalibrationAlgorithm.calibrate(readings, false).toBytes().size)
        assertEquals(52, CalibrationAlgorithm.calibrate(readings, true).toBytes().size)
    }

    @Test
    fun nonLinearityBytesEndWithTheTerminator() {
        val bytes = CalibrationAlgorithm.calibrate(toReadings(EXAMPLE_ONE), true).toBytes()
        assertEquals(0xFF.toByte(), bytes[51])
    }

    @Test
    fun coefficientsAreLittleEndianSixteenBit() {
        val data = ByteArray(4)
        CalibrationAlgorithm.putCoefficient(data, 0, 0x1234.toFloat())
        assertEquals(0x34.toByte(), data[0])
        assertEquals(0x12.toByte(), data[1])

        CalibrationAlgorithm.putCoefficient(data, 2, -2f)
        assertEquals(0xFE.toByte(), data[2])
        assertEquals(0xFF.toByte(), data[3])
    }

    /**
     * `Math.round` rounds halves towards positive infinity, and so must `roundToInt`.
     *
     * The port has already been bitten once by assuming Kotlin rounds the way Java does: elsewhere
     * `kotlin.math.round` turned out to be ties-to-even where Java's `Formatter` is HALF_UP. Here
     * the encoding of every coefficient depends on it, so it is pinned rather than assumed.
     */
    @Test
    fun roundingMatchesJavaMathRound() {
        assertEquals(3, 2.5f.roundToInt())
        assertEquals(-2, (-2.5f).roundToInt())
        assertEquals(2, 2.4f.roundToInt())
        assertEquals(3, 2.6f.roundToInt())
    }

    // -------------------------------------------------------------------------------------
    // Assembling readings from the instrument's two frames
    // -------------------------------------------------------------------------------------

    @Test
    fun anAccumulatorPairsTheTwoFrames() {
        val accumulator = CalibrationReadingAccumulator()
        assertEquals(CalibrationReadingAccumulator.State.AWAITING_ACCELERATION, accumulator.state)

        accumulator.updateAcceleration(1, 2, 3)
        assertEquals(CalibrationReadingAccumulator.State.AWAITING_MAGNETIC, accumulator.state)

        accumulator.updateMagnetic(4, 5, 6)
        assertEquals(CalibrationReadingAccumulator.State.COMPLETE, accumulator.state)
        assertEquals(CalibrationReading(1, 2, 3, 4, 5, 6), accumulator.reading)
    }

    @Test
    fun aMagneticFrameArrivingFirstIsRejected() {
        val accumulator = CalibrationReadingAccumulator()
        assertFailsWith<IllegalStateException> { accumulator.updateMagnetic(4, 5, 6) }
    }

    @Test
    fun aSecondAccelerationFrameIsRejected() {
        val accumulator = CalibrationReadingAccumulator()
        accumulator.updateAcceleration(1, 2, 3)
        assertFailsWith<IllegalStateException> { accumulator.updateAcceleration(7, 8, 9) }
    }

    // -------------------------------------------------------------------------------------

    private fun assertVectorEquals(expected: Vector, actual: Vector) {
        assertEqualsWithin(expected.x, actual.x)
        assertEqualsWithin(expected.y, actual.y)
        assertEqualsWithin(expected.z, actual.z)
    }

    private fun assertEqualsWithin(expected: Float, actual: Float) {
        assertTrue(
            abs(expected - actual) <= allowedDelta,
            "expected $expected but was $actual",
        )
    }

    private fun toReadings(rows: Array<IntArray>): List<CalibrationReading> =
        rows.map { CalibrationReading(it[0], it[1], it[2], it[3], it[4], it[5]) }

    private companion object {

        /** A real 56-shot calibration, from the Android app's test suite. */
        val EXAMPLE_ONE =
            arrayOf(
            intArrayOf(12545, 155, 1529, 17916, 5305, 5435),
            intArrayOf(12563, -490, 660, 18069, -5257, 5596),
            intArrayOf(12529, 90, -95, 17831, -6762, -4037),
            intArrayOf(12558, 846, 475, 17559, 4644, -5383),
            intArrayOf(-15265, -256, 1275, -15908, -7485, 3364),
            intArrayOf(-15258, 1029, 1000, -15910, 3346, 7294),
            intArrayOf(-15250, 674, -217, -16244, 6953, -2846),
            intArrayOf(-15293, -394, 8, -16231, -3702, -7191),
            intArrayOf(-2256, 14202, 633, 6650, 17342, 419),
            intArrayOf(-2191, 2272, 14380, 7225, 2625, 17556),
            intArrayOf(-2288, -13659, 2137, 6899, -17969, 1800),
            intArrayOf(-2473, -1891, -13041, 6168, -3497, -17212),
            intArrayOf(-185, 1018, 14485, -4364, -295, 17751),
            intArrayOf(-320, 14126, -598, -5040, 17503, 331),
            intArrayOf(-366, 146, -13215, -5376, 677, -17361),
            intArrayOf(-443, -13747, 261, -5005, -18035, -2011),
            intArrayOf(-501, 14193, 556, 2643, 16880, 7923),
            intArrayOf(-350, 838, 14540, 3171, -6868, 17092),
            intArrayOf(-516, -13762, 681, 2425, -17529, -7635),
            intArrayOf(-633, 131, -13217, 1960, 6851, -16472),
            intArrayOf(-2126, 14229, 644, -1194, 17018, -5863),
            intArrayOf(-2023, 427, 14551, -408, 6673, 17513),
            intArrayOf(-2090, -13727, 1481, -531, -17288, 7172),
            intArrayOf(-2189, -94, -13173, -1229, -7523, -17129),
            intArrayOf(-12118, 836, 9421, -15525, 5225, 7209),
            intArrayOf(-12240, -8542, 916, -15400, -7474, 5173),
            intArrayOf(-12330, 1066, -7979, -15801, -4817, -7616),
            intArrayOf(-12401, 8971, 924, -15940, 6965, -4371),
            intArrayOf(9382, -81, 9566, 17469, -5886, 6897),
            intArrayOf(9434, 9073, 1468, 17352, 6354, 6137),
            intArrayOf(9322, 749, -8137, 16983, 5346, -6285),
            intArrayOf(9509, -8554, 133, 17201, -7039, -5651),
            intArrayOf(-8218, -1311, 12591, -11536, -7259, 11530),
            intArrayOf(-8315, -11840, -715, -12035, -12247, -6960),
            intArrayOf(-8452, 2007, -11186, -12306, 6859, -11071),
            intArrayOf(-8352, 12387, 2087, -11803, 11643, 7393),
            intArrayOf(5750, 112, 12714, 13993, 4513, 12914),
            intArrayOf(5527, -11988, 329, 13716, -13337, 4205),
            intArrayOf(5496, 1032, -11263, 13137, -4538, -12932),
            intArrayOf(5583, 12349, 1139, 13272, 12558, -3814),
            intArrayOf(-9520, -1257, 11869, -4428, -7482, 15834),
            intArrayOf(-9544, -11143, 376, -4929, -17271, -5698),
            intArrayOf(-9617, 1520, -10450, -5349, 6365, -15753),
            intArrayOf(-9672, 11460, 2362, -4926, 15805, 8037),
            intArrayOf(6595, -878, 12138, 6748, 2411, 17732),
            intArrayOf(6529, 11647, -813, 5896, 16263, -5711),
            intArrayOf(6491, -2406, -10443, 5805, -8548, -15896),
            intArrayOf(6631, -10996, 3212, 6761, -16322, 7469),
            intArrayOf(-10512, -165, 11212, -6355, 3673, 16712),
            intArrayOf(-10644, -10193, -353, -6572, -17075, 2599),
            intArrayOf(-10686, 797, -9668, -7297, -4341, -16321),
            intArrayOf(-10709, 10726, 1640, -7118, 16365, -2443),
            intArrayOf(7782, -10321, -376, 8261, -16015, -7317),
            intArrayOf(7631, -555, -9738, 7758, 3780, -16056),
            intArrayOf(7806, 10780, 805, 8383, 15902, 6079),
            intArrayOf(7683, -270, -9688, 7841, 4231, -15895),
            )

        val EXAMPLE_TWO =
            arrayOf(
            intArrayOf(12521, 865, 444, 18090, 5155, 5456),
            intArrayOf(12560, -86, 30, 17833, 4186, -5384),
            intArrayOf(12530, -409, 896, 17997, -5831, -4829),
            intArrayOf(12558, 358, 1252, 18219, -5474, 5347),
            intArrayOf(-15290, -445, 432, -16050, -5848, 4987),
            intArrayOf(-15294, 602, -71, -16422, -4072, -6388),
            intArrayOf(-15294, 903, 917, -16431, 6293, -3144),
            intArrayOf(-15257, -160, 1104, -16186, 3308, 6669),
            intArrayOf(-1433, 14135, 2283, 5674, 17436, -1439),
            intArrayOf(-1523, -1644, 14331, 6086, 1788, 17930),
            intArrayOf(-1395, -13789, -243, 6167, -18009, 2848),
            intArrayOf(-1461, 1676, -13236, 5461, -2863, -17445),
            intArrayOf(-1287, 1522, 14476, -3805, -3724, 17415),
            intArrayOf(-1285, 14213, -22, -4283, 17188, 5000),
            intArrayOf(-1401, 140, -13259, -4891, 4594, -16654),
            intArrayOf(-1056, -13778, -59, -4084, -17444, -6247),
            intArrayOf(-810, 14186, 10, 5386, 16988, 6251),
            intArrayOf(-652, 887, 14540, 5967, -5765, 17067),
            intArrayOf(-653, -13691, 2010, 5527, -17950, -4916),
            intArrayOf(-863, -1341, -13116, 4910, 3890, -17048),
            intArrayOf(-2095, -13688, -510, -3647, -17971, 3664),
            intArrayOf(-1975, -1177, 14451, -3453, 3570, 17807),
            intArrayOf(-2106, 14214, 1483, -4178, 17192, -3693),
            intArrayOf(-2041, 306, -13211, -4190, -5912, -17105),
            intArrayOf(-9355, 110, 11965, -14560, 424, 10252),
            intArrayOf(-9539, -11107, -329, -14847, -10336, -227),
            intArrayOf(-9580, 1350, -10585, -15141, -79, -9788),
            intArrayOf(-9443, 11740, 1069, -14877, 9969, 199),
            intArrayOf(6947, 1643, 11813, 16975, -185, 10038),
            intArrayOf(6826, -11167, 431, 16689, -10334, -1471),
            intArrayOf(6845, 1707, -10375, 16430, 1956, -9205),
            intArrayOf(6928, 11504, 353, 16657, 9522, 1321),
            intArrayOf(-11811, 15, 9803, -7370, -2691, 16189),
            intArrayOf(-11996, -8845, 398, -7832, -16637, -2806),
            intArrayOf(-11994, 1711, -8287, -8389, 4053, -15295),
            intArrayOf(-11988, 9454, 1220, -7855, 15983, 3650),
            intArrayOf(9235, 643, 9795, 10003, 1926, 16504),
            intArrayOf(9264, -8852, 646, 9728, -16897, 803),
            intArrayOf(9082, -994, -8204, 9044, -4003, -15955),
            intArrayOf(9319, 9243, 765, 9393, 16138, -488),
            intArrayOf(-11630, 1566, 9919, -11256, 8122, 11546),
            intArrayOf(-11919, 9566, 816, -11845, 11940, -5873),
            intArrayOf(-11753, -354, -8626, -11788, -7918, -11669),
            intArrayOf(-11807, -9012, 314, -11280, -12707, 5951),
            intArrayOf(9133, 231, 9922, 13847, -7527, 11576),
            intArrayOf(9146, -8989, 1389, 13345, -12707, -6573),
            intArrayOf(9113, -389, -8207, 13178, 6020, -11527),
            intArrayOf(9076, 9493, 41, 13393, 12115, 6937),
            intArrayOf(-10966, 558, 10709, -11506, -7099, 11606),
            intArrayOf(-11287, 10328, 1333, -11974, 10824, 8319),
            intArrayOf(-11173, 228, -9222, -12368, 6760, -10995),
            intArrayOf(-11215, -9650, -30, -12127, -11360, -8095),
            intArrayOf(8551, 1138, 10494, 14048, 7530, 11320),
            intArrayOf(8544, -9645, 1730, 13962, -11389, 7638),
            intArrayOf(8404, -848, -8939, 13419, -8478, -10920),
            intArrayOf(8579, 9633, -2124, 13359, 8883, -9204),
            )
    }
}
