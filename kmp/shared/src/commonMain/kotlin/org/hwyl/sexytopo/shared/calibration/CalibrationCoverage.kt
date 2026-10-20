package org.hwyl.sexytopo.shared.calibration

import kotlin.math.asin

/**
 * How well a set of calibration readings covers the directions a calibration needs: the corners
 * of a cube and the centres of its faces, each shot at several rolls.
 *
 * Ported from `control/calibration/CalibrationCoverage`. Works from uncalibrated directions, which
 * are plenty accurate enough to tell those directions apart, so it can be shown from the first
 * reading rather than only once there is a fit.
 */
class CalibrationCoverage private constructor(readings: List<CalibrationReading?>) {

    val setCount: Int

    /** Sets with enough readings (rolls) to be used as a group. */
    val completeSetCount: Int

    private val directionCounts =
        ReadingDirection.Pointing.entries.associateWith { 0 }.toMutableMap()

    init {
        val sets = CalibrationSets.find(readings)
        setCount = sets.size
        completeSetCount = sets.count { it.size >= READINGS_PER_SET }

        // Sets of the same direction taken at different times count once.
        val directions = mutableListOf<Vector>()
        for (set in sets) {
            val direction = averageDirection(readings, set)
            if (directions.all { angleBetween(it, direction) > SAME_DIRECTION }) {
                directions += direction
                val inclination = toDegrees(asin(-direction.z.toDouble()))
                val pointing = ReadingDirection.pointingOf(inclination)
                directionCounts[pointing] = directionCounts.getValue(pointing) + 1
            }
        }
    }

    /** The number of distinct directions covered that point this way. */
    fun directionCount(pointing: ReadingDirection.Pointing): Int = directionCounts.getValue(pointing)

    fun isTargetMet(pointing: ReadingDirection.Pointing): Boolean =
        directionCount(pointing) >= targetFor(pointing)

    companion object {

        const val READINGS_PER_SET = 4

        /** Degrees. */
        private const val SAME_DIRECTION = 30.0

        /** Gaps in the readings are ignored. */
        fun of(readings: List<CalibrationReading?>): CalibrationCoverage = CalibrationCoverage(readings)

        /** How many distinct directions pointing this way a full calibration has. */
        fun targetFor(pointing: ReadingDirection.Pointing): Int =
            when (pointing) {
                ReadingDirection.Pointing.HORIZONTAL,
                ReadingDirection.Pointing.CORNER_UP,
                ReadingDirection.Pointing.CORNER_DOWN,
                -> 4
                ReadingDirection.Pointing.UP, ReadingDirection.Pointing.DOWN -> 1
            }

        private fun averageDirection(readings: List<CalibrationReading?>, set: List<Int>): Vector =
            set.mapNotNull { readings[it] }
                .map { CalibrationSets.uncalibratedDirection(it).toVector() }
                .fold(Vector.ZERO) { sum, direction -> sum + direction }
                .normalised()
    }
}
