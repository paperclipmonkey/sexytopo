package org.hwyl.sexytopo.shared.calibration

/**
 * Readings grouped by the direction they were shot in, each set being one direction taken at
 * several rolls.
 *
 * Ported from the static half of `CalibrationCalculator` as of 1.14.0. Beat Heeb's solver treats
 * its first sixteen readings as four sets of four shot in one direction (see
 * [CalibrationAlgorithm.optimise]), and used to be given the readings in the order they were taken,
 * so a surveyor who shot the directions in another order, repeated one, or fired a stray extra shot
 * misaligned those groups without anything saying so. The sets are now found from which way each
 * reading points, and the four groups are taken from them.
 */
object CalibrationSets {

    /** The algorithm expects its first sixteen readings to be four sets of four. */
    const val GROUPED_SET_COUNT = 4
    const val READINGS_PER_GROUPED_SET = 4

    /**
     * Uncalibrated readings in one set can wander by 40 degrees on a poorly behaved instrument,
     * while consecutive directions in a calibration are at least about 70 degrees apart.
     */
    private const val SET_TOLERANCE = 50.0

    /**
     * Groups consecutive readings that point the same way, returning the indices of each set's
     * readings. Uses uncalibrated directions, so it works before there is a calibration. A gap (a
     * reading deleted and waiting to be retaken) ends a set.
     */
    fun find(readings: List<CalibrationReading?>): List<List<Int>> {
        val sets = mutableListOf<MutableList<Int>>()
        var current: MutableList<Int>? = null
        var sum = Vector.ZERO
        for ((index, reading) in readings.withIndex()) {
            if (reading == null) {
                current = null
                continue
            }
            val direction = uncalibratedDirection(reading).toVector()
            if (current != null && angleBetween(direction, sum.normalised()) <= SET_TOLERANCE) {
                current.add(index)
                sum += direction
            } else {
                current = mutableListOf(index)
                sets.add(current)
                sum = direction
            }
        }
        return sets
    }

    /**
     * A reading's direction from its raw sensor values. Without a calibration this is only
     * approximate, but close enough to tell which way the instrument was pointing.
     */
    fun uncalibratedDirection(reading: CalibrationReading): ReadingDirection =
        ReadingDirection.fromVectors(
            Vector(reading.gx.toFloat(), reading.gy.toFloat(), reading.gz.toFloat()),
            Vector(reading.mx.toFloat(), reading.my.toFloat(), reading.mz.toFloat()),
        )

    /** The set, counting from one, that each reading belongs to; zero for a gap. */
    fun numbers(readings: List<CalibrationReading?>): List<Int> {
        val numbers = MutableList(readings.size) { 0 }
        for ((set, indices) in find(readings).withIndex()) {
            for (index in indices) numbers[index] = set + 1
        }
        return numbers
    }

    /**
     * The order to give the readings to the algorithm: the first four sets of at least four
     * readings (four from each), then the rest in the order taken. For readings taken in the usual
     * order that is the order taken. Falls back to the order taken if there are not enough sets of
     * four.
     */
    internal fun algorithmOrder(sets: List<List<Int>>, count: Int): List<Int> {
        val grouped =
            sets.filter { it.size >= READINGS_PER_GROUPED_SET }.take(GROUPED_SET_COUNT)
        if (grouped.size < GROUPED_SET_COUNT) return (0 until count).toList()

        val order = grouped.flatMap { it.take(READINGS_PER_GROUPED_SET) }.toMutableList()
        val placed = order.toSet()
        (0 until count).filterTo(order) { it !in placed }
        return order
    }
}
