package org.hwyl.sexytopo.shared.calibration

import org.hwyl.sexytopo.shared.comms.InstrumentProfile

/**
 * Which fit to run: `pref_calibration_algorithm`'s three values, under their own names.
 *
 * The linear fit solves for the sensors' scale, offset and alignment. The non-linear one fits
 * three extra accelerometer coefficients on top, which a device whose accelerometer is not quite
 * linear needs and one whose is does not — and fitting them where they are not needed is fitting
 * noise. The Android app's own comment on the default says it plainly: *"linear probably safer as
 * default"*.
 *
 * [AUTO] is the interesting one, because the right answer is a property of the device rather than
 * of the surveyor: `DistoX.prefersNonLinearCalibration` says yes for the X310 and DistoX-BLE and
 * no for the A3 and for anything it does not recognise. That table is [InstrumentProfile
 * .prefersNonLinearCalibration] here, so the same fact lives with the rest of the device matrix
 * rather than in a switch statement in an activity.
 */
enum class CalibrationChoice(
    /** What `pref_calibration_algorithm` stores. */
    val key: String,
    /** What the chip says. */
    val label: String,
) {
    /** Ask the instrument. */
    AUTO("auto", "Auto"),

    /** Scale, offset and alignment only. */
    LINEAR("linear", "Linear"),

    /** Plus three accelerometer non-linearity terms. */
    NON_LINEAR("nonlinear", "Non-linear"),
    ;

    /**
     * Whether to fit the extra terms, given what is attached.
     *
     * A null profile is the simulated instrument or nothing at all, and reads as linear — which is
     * both the safe answer and what the Java does, since its `getDistox()` throws when nothing is
     * connected and the whole switch is wrapped in a `try` that leaves `useNonLinear` false.
     */
    fun useNonLinearity(attached: InstrumentProfile?): Boolean =
        when (this) {
            AUTO -> attached?.prefersNonLinearCalibration ?: false
            LINEAR -> false
            NON_LINEAR -> true
        }

    companion object {
        /** `getString("pref_calibration_algorithm", "linear")`. */
        val DEFAULT = LINEAR

        /** Unknown text reads as the default rather than throwing. */
        fun of(key: String?): CalibrationChoice? = entries.firstOrNull { it.key == key }
    }
}
