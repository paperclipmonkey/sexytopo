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
 * [CalibrationAlgorithm.calibrate].
 *
 * The readings are a [CalibrationReadingList], so one can be replaced (leaving a gap the next shot
 * fills) or deleted (closing up) from anywhere in the run, as the Android app allows from 1.14.0.
 */
class CalibrationRun {

    private val taken = CalibrationReadingList()

    /** Every position up to the last reading taken, with null for one waiting to be retaken. */
    val readings: List<CalibrationReading?> get() = taken.all

    /** Readings actually held, not counting gaps. */
    val count: Int get() = taken.count

    /** The reading shown as the last one taken. See [CalibrationReadingList.last]. */
    val last: CalibrationReading? get() = taken.last

    /** Where [last] sits in [readings], or -1 if there are none. */
    val lastIndex: Int get() = last?.let(taken::indexOfIdentity) ?: -1

    /** The position the next reading will fill: the first gap, or the end. */
    val nextIndex: Int get() = taken.nextIndex

    val isEmpty: Boolean get() = taken.isEmpty

    /** Whether a reading has been replaced and not yet retaken. */
    val hasGaps: Boolean get() = taken.hasGaps

    /**
     * Where to point the instrument next, or null once there are enough readings.
     *
     * Still the guided sequence, and still a suggestion: the readings themselves are now described
     * by the way they were measured pointing (see [CalibrationSets]), but the order to shoot them in
     * is the same fifty-six positions.
     */
    val next: CalibrationPosition?
        get() = if (isComplete) null else CalibrationPositions.next(nextIndex)

    /** Fifty-six readings at least, and no gaps waiting to be retaken. */
    val isComplete: Boolean get() = taken.isComplete(CalibrationPositions.REQUIRED)

    /**
     * Enough readings to fit, and none missing. A gap is a reading the surveyor has said is wrong,
     * so a fit across it would be of the readings they did not mean to keep.
     */
    val canSolve: Boolean get() = !hasGaps && count >= CalibrationAlgorithm.MINIMUM_READINGS

    /** How the readings so far cover the directions a calibration needs. */
    fun coverage(): CalibrationCoverage = CalibrationCoverage.of(readings)

    /** The set, counting from one, each position's reading falls in; zero for a gap. */
    fun setNumbers(): List<Int> = CalibrationSets.numbers(readings)

    fun add(reading: CalibrationReading) {
        taken.add(reading)
    }

    /** Leave a gap at [index] for the next reading to fill, keeping the rest in their places. */
    fun replace(index: Int) {
        taken.replace(index)
    }

    /** Remove the reading or gap at [index], moving the later readings up one place. */
    fun delete(index: Int) {
        taken.delete(index)
    }

    /**
     * Delete the reading shown as the last one — the surveyor moved, or the instrument fired twice.
     * A retake leaves its gap again rather than closing up.
     */
    fun deleteLast() {
        taken.deleteLastAdded()
    }

    fun clear() {
        taken.clear()
    }

    /** Replace the whole run, as loading a saved calibration does. Gaps are kept. */
    fun setAll(readings: List<CalibrationReading?>) {
        taken.setAll(readings)
    }

    /**
     * Fit the sensor corrections to the readings.
     *
     * [useNonLinearity] chooses Beat Heeb's non-linear variant, which fits three extra coefficients
     * for accelerometer non-linearity. The Android app defaults to the linear algorithm and its own
     * comment says why — "linear probably safer as default" — so that is the default here too.
     *
     * Throws unless [canSolve]. Note the floor is 16 readings, well below the 56 a proper
     * calibration takes: the solver will fit anything, and it is the *positions* that make the
     * answer meaningful, not the count.
     */
    fun solve(useNonLinearity: Boolean = false): CalibrationResult {
        require(!hasGaps) { "calibration has readings waiting to be retaken" }
        return CalibrationAlgorithm.calibrate(readings.filterNotNull(), useNonLinearity)
    }

    /**
     * What [result] says about each reading. Only meaningful for a result solved from these
     * readings as they stand; null if they have gaps, since a fit is never made across one.
     */
    fun analyse(result: CalibrationResult): CalibrationAnalysis? =
        if (hasGaps) null else CalibrationAnalysis(readings.filterNotNull(), result)

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
     * block in a single `data:`-framed memory write — see [DistoXBleFraming.createWriteMemoryPacket].
     * Sending the classic dribble to a BLE instrument means thirteen unframed packets its firmware
     * has no reason to recognise as a calibration write at all.
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

    /**
     * [writeCommands], and a record of the upload that can be settled by the instrument's reply.
     * Only a DistoX-BLE is known to send one.
     */
    fun write(
        result: CalibrationResult,
        family: InstrumentFamily = InstrumentFamily.DISTOX,
    ): CalibrationWrite =
        CalibrationWrite(
            coefficients = result.toBytes(),
            commands = writeCommands(result, family),
            awaitsConfirmation = family == InstrumentFamily.DISTOX_BLE,
        )
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
