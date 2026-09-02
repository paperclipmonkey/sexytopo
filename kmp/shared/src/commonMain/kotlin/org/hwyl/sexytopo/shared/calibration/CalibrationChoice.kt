package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentProfile

/** `pref_calibration_algorithm`'s three values, under their own names. */
enum class CalibrationChoice(
    val key: String,
    val label: String,
) {
    AUTO("auto", "Auto"),
    LINEAR("linear", "Linear"),
    NON_LINEAR("nonlinear", "Non-linear"),
    ;

    /**
     * A null profile is the simulated instrument or nothing at all, and reads as linear, which is
     * both the safe answer and what the Java does.
     */
    fun useNonLinearity(attached: InstrumentProfile?): Boolean =
        when (this) {
            AUTO -> attached?.prefersNonLinearCalibration ?: false
            LINEAR -> false
            NON_LINEAR -> true
        }

    companion object {
        val DEFAULT = LINEAR

        fun of(key: String?): CalibrationChoice? = entries.firstOrNull { it.key == key }
    }
}
