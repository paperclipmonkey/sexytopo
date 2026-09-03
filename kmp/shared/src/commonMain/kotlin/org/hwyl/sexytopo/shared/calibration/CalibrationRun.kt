package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentFamily
import org.hwyl.sexytopo.shared.comms.distox.DistoXBleFraming
import org.hwyl.sexytopo.shared.comms.distox.DistoXMemoryRange
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol

/**
 * A calibration in progress: the readings taken so far, and what to do with them.
 *
 * Ported from the parts of `DistoXCalibrationActivity` and `SurveyManager` that are not Android —
 * the reading list, the position checklist, and the step from a full set of readings to the bytes
 * that get written back to the instrument. The solver underneath is
 * [CalibrationAlgorithm.optimise].
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

    val isComplete: Boolean get() = CalibrationPositions.isComplete(taken.size)

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
     * Two shapes, not one, and which of them is right depends on [family] rather than on
     * anything about the coefficients themselves. The classic protocol
     * ([InstrumentFamily.DISTOX], reached only over an RFCOMM socket this port cannot open, but
     * still the shape the simulator and the shared tests exercise) dribbles the block out four
     * bytes at a time from address 0x8010 — `WriteCalibrationProtocol.go` — and the instrument
     * replies to each one, which [DistoXProtocol.isCalibrationWriteReplyValid] checks. DistoX-BLE
     * and Cavway X1 (which copies DistoX-BLE's framing verbatim) instead take the whole 52-byte
     * block in a single `data:`-framed memory write and send no reply at all — see
     * [DistoXBleFraming.createWriteMemoryPacket]'s own note. Sending the classic dribble to a BLE
     * instrument means twelve unframed packets its firmware has no reason to recognise as a
     * calibration write at all: nothing rejects them, so the app would report success while the
     * instrument's coefficients never changed.
     */
    fun writeCommands(
        result: CalibrationResult,
        family: InstrumentFamily = InstrumentFamily.DISTOX,
    ): List<ByteArray> =
        when (family) {
            InstrumentFamily.DISTOX_BLE, InstrumentFamily.CAVWAY_X1 ->
                listOf(
                    DistoXBleFraming.createWriteMemoryPacket(
                        DistoXMemoryRange.CALIBRATION_COEFFICIENTS,
                        result.toBytes(),
                    ),
                )
            else -> DistoXProtocol.createWriteCalibrationCommands(result.toBytes())
        }
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
