package org.hwyl.sexytopo.shared.calibration

/**
 * What a fit says about each reading: which way it was really pointing once corrected, and how far
 * it disagrees with the rest of its set.
 *
 * Ported from `CalibrationCalculator.getReadingErrors` and `getReadingDirections`, and the
 * least-consistent list `DistoXCalibrationActivity` builds from them. The Java can only answer
 * "straight after calculate", because the coefficients it applies are statics the next calculation
 * overwrites; here the [result] is carried, so an analysis cannot be of a different fit from the
 * one it describes.
 */
class CalibrationAnalysis(
    /** The readings [result] was fitted to, without gaps, in the order taken. */
    val readings: List<CalibrationReading>,
    val result: CalibrationResult,
) {

    /** Each reading's direction using the calibration. */
    val directions: List<ReadingDirection> = readings.map(result::directionOf)

    /**
     * For each reading, the angle in degrees between its calibrated laser direction and the average
     * direction of its set, so a large angle marks a reading that disagrees with the rest of its
     * set. NaN for a reading that is alone in its set, which has nothing to disagree with.
     */
    val errors: List<Double> =
        run {
            val laser = directions.map { it.toVector() }
            val errors = MutableList(readings.size) { Double.NaN }
            for (set in CalibrationSets.find(readings)) {
                val average = set.fold(Vector.ZERO) { sum, i -> sum + laser[i] }.normalised()
                for (i in set) {
                    errors[i] = if (set.size > 1) angleBetween(laser[i], average) else Double.NaN
                }
            }
            errors
        }

    /**
     * The readings that disagree with the others shot in the same direction by more than
     * [GOOD_ERROR], worst first, at most [limit] of them.
     */
    fun leastConsistent(limit: Int = WORST_READINGS_SHOWN): List<Int> =
        errors.indices
            .filter { errors[it] > GOOD_ERROR } // false for NaN
            .sortedByDescending { errors[it] }
            .take(limit)

    companion object {
        /** Degrees. `CalibrationReadingView.GOOD_ERROR`. */
        const val GOOD_ERROR = 1.0

        /** `DistoXCalibrationActivity.WORST_READINGS_SHOWN`. */
        const val WORST_READINGS_SHOWN = 5
    }
}
