package org.hwyl.sexytopo.shared.calibration

/**
 * The 56 positions a DistoX has to be held in to calibrate it, and the order to work through them.
 *
 * Ported from `DistoXCalibrationActivity`'s `CalibrationDirection`, `Orientation` and the
 * `positions` list its static initialiser builds. This is not presentation: which shots a
 * calibration needs, and in what order, is what makes the solver's answer meaningful. Beat Heeb's
 * algorithm fits sensor errors to a set of readings that between them point the instrument in every
 * direction and roll it about each one, so a calibration taken in a different set of positions is
 * not a worse calibration, it is a wrong one.
 *
 * Fourteen directions — the six axes plus the eight body diagonals — each shot four times with the
 * instrument rolled 90 degrees between shots. The labels are the Android app's own strings.
 */
enum class CalibrationDirection(val label: String) {
    FORWARD("Forward"),
    BACK("Back"),
    LEFT("Left"),
    RIGHT("Right"),
    UP("Up"),
    DOWN("Down"),
    FORWARD_LEFT_UP("Forward Left Up"),
    FORWARD_LEFT_DOWN("Forward Left Down"),
    FORWARD_RIGHT_UP("Forward Right Up"),
    FORWARD_RIGHT_DOWN("Forward Right Down"),
    BACK_LEFT_UP("Back Left Up"),
    BACK_LEFT_DOWN("Back Left Down"),
    BACK_RIGHT_UP("Back Right Up"),
    BACK_RIGHT_DOWN("Back Right Down"),
}

/** How far the instrument is rolled about the direction it is pointing. */
enum class CalibrationOrientation(val label: String) {
    FACE_UP("Face Up"),
    FACE_RIGHT("Face Right"),
    FACE_DOWN("Face Down"),
    FACE_LEFT("Face Left"),
}

/** One of the 56 shots: where to point the instrument, and which way up to hold it. */
data class CalibrationPosition(
    val direction: CalibrationDirection,
    val orientation: CalibrationOrientation,
) {
    /** "Forward Left Up, Face Right" — what the screen tells the surveyor to do next. */
    override fun toString(): String = "${direction.label}, ${orientation.label}"
}

object CalibrationPositions {

    /**
     * All 56, direction-major: every orientation of one direction before moving on.
     *
     * The order matters to the surveyor rather than to the solver — it is four shots without
     * putting the instrument down, then a new direction — and it is the order the app's own nested
     * loop produces.
     */
    val ALL: List<CalibrationPosition> =
        CalibrationDirection.entries.flatMap { direction ->
            CalibrationOrientation.entries.map { CalibrationPosition(direction, it) }
        }

    /** How many readings a full calibration takes. */
    val REQUIRED: Int get() = ALL.size

    /**
     * The largest error, in the solver's own units, that counts as a good calibration.
     *
     * `DistoXCalibrationActivity.MAX_ERROR`. Above this the app still offers to write the
     * coefficients — it is the surveyor's instrument — but says the calibration is poor.
     */
    const val MAX_ERROR: Double = 0.5

    /**
     * What to do next, given how many readings have been taken, or null when there are enough.
     *
     * Note this indexes the position list by count rather than tracking which positions have
     * actually been covered: the instrument does not report which way it was pointing, so neither
     * the app nor this can know. It is a checklist, not a validation.
     */
    fun next(readingsTaken: Int): CalibrationPosition? = ALL.getOrNull(readingsTaken)

    /** Whether [readingsTaken] is enough to compute a calibration from. */
    fun isComplete(readingsTaken: Int): Boolean = readingsTaken >= REQUIRED
}
