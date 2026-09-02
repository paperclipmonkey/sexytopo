package org.hwyl.sexytopo.shared.survey

/**
 * How the surveyor is holding the instrument, which decides what a run of repeated readings means:
 *  - [FORWARD]: shots are taken from the current station towards the next one.
 *  - [BACKWARD]: shots are taken standing at the *next* station looking back, so a promoted leg is
 *    reversed before being hung on the tree.
 *  - [COMBO]: a foresight followed by a backsight down the same leg; failing that, plain repeats.
 *  - [CALIBRATION_CHECK]: readings are being taken to check the instrument, so nothing is ever
 *    promoted — the shots are kept as splays for inspection.
 */
enum class InputMode {
    FORWARD,
    BACKWARD,
    COMBO,
    CALIBRATION_CHECK,
    ;

    companion object {
        val DEFAULT = FORWARD
    }
}
