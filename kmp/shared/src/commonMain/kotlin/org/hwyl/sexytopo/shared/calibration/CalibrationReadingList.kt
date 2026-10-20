package org.hwyl.sexytopo.shared.calibration

/**
 * Calibration readings in the order they were taken, where a reading's place says which guided
 * position it was taken in.
 *
 * Ported from `model/calibration/CalibrationReadingList`. Replacing a reading leaves a gap (a null)
 * in its place, which the next reading taken fills, so a single bad shot can be retaken without
 * redoing everything after it; deleting one removes it and moves the later readings up one place.
 *
 * Readings are found by identity, never by value. The Java's `CalibrationReading` has no `equals`,
 * so its `indexOf` and `contains` are identity checks; here [CalibrationReading] is a data class,
 * and two shots with the same six counts — the same reading arriving twice, or a test's readings
 * of zeros — would otherwise be mistaken for each other, and deleting the one shown as last could
 * remove an earlier one instead.
 */
class CalibrationReadingList {

    private val readings = mutableListOf<CalibrationReading?>()

    private var lastAdded: CalibrationReading? = null

    fun add(reading: CalibrationReading) {
        val gap = readings.indexOf(null)
        if (gap >= 0) readings[gap] = reading else readings.add(reading)
        lastAdded = reading
    }

    /** Leaves a gap for the next reading taken to fill. */
    fun replace(index: Int) {
        readings[index] = null
        trimTrailingGaps()
    }

    /** Removes the reading (or gap), moving the later readings up one place. */
    fun delete(index: Int) {
        readings.removeAt(index)
        trimTrailingGaps()
    }

    /**
     * Deletes the reading shown as the last one. If it filled a gap it leaves the gap again, so the
     * later readings keep their places; otherwise it is simply removed.
     */
    fun deleteLastAdded() {
        val last = this.last ?: return
        replace(indexOfIdentity(last))
    }

    fun clear() {
        readings.clear()
        lastAdded = null
    }

    fun setAll(newReadings: List<CalibrationReading?>) {
        readings.clear()
        readings.addAll(newReadings)
        trimTrailingGaps()
        lastAdded = readings.lastOrNull { it != null }
    }

    /** All positions up to the last reading taken, with null for a deleted reading. */
    val all: List<CalibrationReading?>
        get() = readings

    /**
     * The reading most recently taken or, if that has since been deleted, the latest one still in
     * the list. Null if there are none.
     */
    val last: CalibrationReading?
        get() {
            val recent = lastAdded
            if (recent != null && indexOfIdentity(recent) >= 0) return recent
            return readings.lastOrNull { it != null }
        }

    /** Where [reading] sits, by identity; -1 if it is not in the list. */
    fun indexOfIdentity(reading: CalibrationReading): Int = readings.indexOfFirst { it === reading }

    /** The position the next reading will fill. */
    val nextIndex: Int
        get() = readings.indexOf(null).takeIf { it >= 0 } ?: readings.size

    val count: Int
        get() = readings.count { it != null }

    val isEmpty: Boolean
        get() = readings.isEmpty()

    val hasGaps: Boolean
        get() = null in readings

    /** Whether there are at least [count] readings and no gaps waiting to be retaken. */
    fun isComplete(count: Int): Boolean = !hasGaps && readings.size >= count

    private fun trimTrailingGaps() {
        while (readings.isNotEmpty() && readings.last() == null) readings.removeAt(readings.lastIndex)
    }
}
