package org.hwyl.sexytopo.shared.calibration

/**
 * Fourteen directions — the six axes plus the eight body diagonals — each shot four times with the
 * instrument rolled 90 degrees between shots: a calibration taken in a different set of positions
 * is not a worse calibration, it is a wrong one.
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

data class CalibrationPosition(
    val direction: CalibrationDirection,
    val orientation: CalibrationOrientation,
) {
    override fun toString(): String = "${direction.label}, ${orientation.label}"
}

object CalibrationPositions {

    /** Direction-major: every orientation of one direction before moving on. */
    val ALL: List<CalibrationPosition> =
        CalibrationDirection.entries.flatMap { direction ->
            CalibrationOrientation.entries.map { CalibrationPosition(direction, it) }
        }

    val REQUIRED: Int get() = ALL.size

    const val MAX_ERROR: Double = 0.5

    /**
     * Indexes the position list by count rather than tracking which positions have actually been
     * covered: the instrument does not report which way it was pointing. A checklist, not a
     * validation.
     */
    fun next(readingsTaken: Int): CalibrationPosition? = ALL.getOrNull(readingsTaken)

    fun isComplete(readingsTaken: Int): Boolean = readingsTaken >= REQUIRED
}
