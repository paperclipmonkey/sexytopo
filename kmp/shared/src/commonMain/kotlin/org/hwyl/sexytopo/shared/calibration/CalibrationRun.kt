package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol

/**
 * A calibration in progress: the readings taken so far, and what to do with them.
 *
 * Ported from the parts of `DistoXCalibrationActivity` and `SurveyManager` that are not Android —
 * the reading list, the position checklist, and the step from a full set of readings to the bytes
 * that get written back to the instrument. The solver underneath is
 * [CalibrationAlgorithm.optimise], which was ported and tested against the Android app's own two
 * 56-shot datasets long before anything could feed it.
 *
 * Why calibrating matters at all: an uncalibrated DistoX can be several degrees out, and a survey
 * is a chain of bearings, so the error accumulates along the passage. A cave surveyed on an
 * uncalibrated instrument comes back not quite the same shape as the cave, and nothing in the
 * numbers says so.
 */
class CalibrationRun {

    private val taken = mutableListOf<CalibrationReading>()

    /** The readings so far, oldest first. */
    val readings: List<CalibrationReading> get() = taken

    val count: Int get() = taken.size

    /** The most recent reading, which is the one the screen shows the raw values of. */
    val last: CalibrationReading? get() = taken.lastOrNull()

    /**
     * Where to point the instrument next, or null once there are enough readings.
     *
     * A checklist rather than a validation: the instrument does not report which way it was
     * pointing, so nothing here — or in the Android app — can tell whether the surveyor actually
     * held it that way.
     */
    val next: CalibrationPosition? get() = CalibrationPositions.next(taken.size)

    /** Whether a full set has been taken. */
    val isComplete: Boolean get() = CalibrationPositions.isComplete(taken.size)

    /** Whether the solver would accept what has been taken so far. */
    val canSolve: Boolean get() = taken.size >= CalibrationAlgorithm.MINIMUM_READINGS

    fun add(reading: CalibrationReading) {
        taken.add(reading)
    }

    /** Undo the last shot — the surveyor moved, or the instrument fired twice. */
    fun deleteLast() {
        taken.removeLastOrNull()
    }

    fun clear() {
        taken.clear()
    }

    /**
     * Fit the sensor corrections to the readings.
     *
     * [useNonLinearity] chooses Beat Heeb's non-linear variant, which fits three extra coefficients
     * for accelerometer non-linearity. The Android app defaults to the linear algorithm and its own
     * comment says why — "linear probably safer as default" — so that is the default here too.
     *
     * Throws if there are fewer than [CalibrationAlgorithm.MINIMUM_READINGS]; check [canSolve]
     * first. Note that is 16, well below the 56 a proper calibration takes: the solver will fit
     * anything, and it is the *positions* that make the answer meaningful, not the count.
     */
    fun solve(useNonLinearity: Boolean = false): CalibrationResult {
        val (g, m) = CalibrationAlgorithm.readingsToVectors(taken)
        return CalibrationAlgorithm.optimise(g, m, useNonLinearity)
    }

    /**
     * How good the fit is, in words a surveyor can act on.
     *
     * The threshold is `DistoXCalibrationActivity.MAX_ERROR`. A poor calibration is still offered
     * for writing — it is the surveyor's instrument, and a bad calibration they know about beats
     * carrying on with the factory one they do not — but it is not called good.
     */
    fun assess(result: CalibrationResult): CalibrationQuality =
        when {
            !result.converged -> CalibrationQuality.DID_NOT_SETTLE
            result.delta <= CalibrationPositions.MAX_ERROR -> CalibrationQuality.GOOD
            else -> CalibrationQuality.POOR
        }

    /**
     * The memory writes that store [result] on the instrument.
     *
     * Four coefficient bytes per command, from address 0x8010, which is
     * `WriteCalibrationProtocol.go`. The instrument replies to each one and
     * [DistoXProtocol.isCalibrationWriteReplyValid] says whether it took.
     */
    fun writeCommands(result: CalibrationResult): List<ByteArray> =
        DistoXProtocol.createWriteCalibrationCommands(result.toBytes())
}

/** What to tell the surveyor about a fit. */
enum class CalibrationQuality {
    /** Within `MAX_ERROR`: use it. */
    GOOD,

    /** It converged, but not well. Usually means a position was repeated or the shot moved. */
    POOR,

    /** The fit hit its iteration ceiling: the readings do not describe a consistent instrument. */
    DID_NOT_SETTLE,
}
