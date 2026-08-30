package org.hwyl.sexytopo.shared.calibration

/**
 * One calibration shot: the raw accelerometer and magnetometer triples, as the instrument reports
 * them.
 *
 * Raw 16-bit counts, not physical units — [CalibrationAlgorithm.readingsToVectors] divides by
 * [CalibrationAlgorithm.FV].
 */
data class CalibrationReading(
    val gx: Int,
    val gy: Int,
    val gz: Int,
    val mx: Int,
    val my: Int,
    val mz: Int,
)

/**
 * Assembles a [CalibrationReading] from the two packets the instrument sends for it.
 *
 * A DistoX reports the gravity triple and the magnetic triple as separate frames, always in that
 * order. This is the Java's `CalibrationReading` state machine, kept because the ordering check is
 * the useful part of it: a magnetic frame arriving first means frames have been dropped or
 * reordered, and pairing it with the wrong gravity frame would corrupt the calibration in a way no
 * later step could detect. Better to fail where the mistake happens.
 *
 * The assembled reading is immutable, so a completed one cannot be half-overwritten by the next
 * shot's first frame.
 */
class CalibrationReadingAccumulator {

    enum class State {
        /** Waiting for the gravity frame. */
        AWAITING_ACCELERATION,

        /** Gravity received; waiting for the magnetic frame. */
        AWAITING_MAGNETIC,

        /** Both received; [reading] is available. */
        COMPLETE,
    }

    var state: State = State.AWAITING_ACCELERATION
        private set

    private var gx = 0
    private var gy = 0
    private var gz = 0

    /** The completed reading, or null until [state] is [State.COMPLETE]. */
    var reading: CalibrationReading? = null
        private set

    fun updateAcceleration(x: Int, y: Int, z: Int) {
        checkStateIs(State.AWAITING_ACCELERATION)
        gx = x
        gy = y
        gz = z
        state = State.AWAITING_MAGNETIC
    }

    fun updateMagnetic(x: Int, y: Int, z: Int) {
        checkStateIs(State.AWAITING_MAGNETIC)
        reading = CalibrationReading(gx, gy, gz, x, y, z)
        state = State.COMPLETE
    }

    private fun checkStateIs(expected: State) {
        check(state == expected) {
            "Calibration state error: state is $state but should be $expected"
        }
    }
}
