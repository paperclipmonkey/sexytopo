package org.hwyl.sexytopo.shared.comms

import org.hwyl.sexytopo.shared.comms.bric.Bric4Error

/**
 * What an instrument's refusal to take a shot actually means, and what to do about it.
 *
 * ## The wording is hedged on purpose
 *
 * A magnetometer reading outside the expected range is *usually* a lump of steel, a head torch
 * battery, a phone or a MagSafe magnet within a few centimetres. It can also be an instrument that
 * has never been calibrated, or one calibrated in another country. The app cannot tell those
 * apart, so it says both and puts the cheap check first.
 *
 * One thing the app cannot see at all is worth knowing, because it decides which of those it is. A
 * BRIC's own screen prints the two magnetometer magnitudes beside the error - `Mag1 Low: 0.8235`,
 * `Mag2 Low: 0.8398` - and whether they *agree* is the tell. A lump of steel beside the instrument
 * is a local field, so it pulls the nearer sensor harder and the two disagree; two sensors reading
 * low by the same amount is the whole field being weak, which is a building, or a calibration made
 * somewhere the field was stronger. That is why the advice sends the surveyor outdoors before it
 * sends them to the calibration menu: going outside is the one cheap test that separates those.
 */
enum class ShotTrouble(
    /** One line, in the surveyor's terms rather than the instrument's. */
    val summary: String,
    /** What to try, cheapest thing first. */
    val whatToDo: String,
) {
    /**
     * The compass could not be trusted: a magnetic field where there should not be one.
     *
     * First in [order] because it is both the commonest and the one that silently ruins a survey
     * if the instrument decides to accept the reading rather than reject it.
     */
    MAGNETIC(
        "The instrument's compass is reading a magnetic field it does not expect.",
        "Move it away from anything magnetic first - the phone itself, a head torch battery, a " +
            "steel karabiner, a bolt or rebar - and shoot again. If that does not fix it, take " +
            "it outdoors, well away from buildings and cars: a steel-framed building weakens the " +
            "field enough to fail on its own, and shooting outside is what tells the two apart. " +
            "Still refusing in the open means the instrument's own calibration no longer matches " +
            "where you are, and a BRIC is calibrated on the device, from its own menu, not from " +
            "this app.",
    ),

    /** The instrument was moving when it fired. */
    MOVED(
        "The instrument moved while it was measuring.",
        "Rest it on something, or hold it against the rock, and shoot again. Cold hands and a " +
            "long shot are enough to cause this on their own.",
    ),

    /** The laser got nothing usable back. */
    NO_RETURN(
        "The laser did not get a usable reflection.",
        "Aim at something matt and solid rather than at wet flowstone, a puddle or open space. " +
            "A target card helps on a long shot, and so does a shorter shot.",
    ),

    /** The instrument and the app disagreed about a message. */
    CONVERSATION(
        "The instrument and the app lost track of each other.",
        "Usually harmless on its own: shoot again. If every shot does it, disconnect and " +
            "reconnect, which starts the conversation from the beginning.",
    ),

    /** A code this app has no reading for. */
    UNKNOWN(
        "The instrument reported a problem this app does not recognise.",
        "The code is in the log, which can be copied off the phone. Shooting again is worth a " +
            "try before anything else.",
    ),
    ;

    companion object {
        /**
         * Which trouble to report when several arrived together, most actionable first.
         *
         * A refused BRIC shot usually reports two or three codes at once - a magnetometer
         * complaint *and* an azimuth complaint, say - because one distrusted sensor makes the
         * calculation that uses it fail too. Reporting all of them is what the log already does;
         * this picks the one to act on.
         */
        val order = listOf(MAGNETIC, MOVED, NO_RETURN, CONVERSATION, UNKNOWN)

        /**
         * A BRIC4/BRIC5 error code as a cause.
         *
         * Note which side of the line the two *calculation* errors fall: the instrument reports
         * them when it cannot derive a bearing or an inclination from its sensors, which in
         * practice is the sensors having been distrusted a line earlier. They are grouped with
         * [MAGNETIC] rather than given a category of their own, because "azimuth calculation
         * problem" on its own is not a different thing to do.
         */
        fun ofBric(code: Int): ShotTrouble =
            when (Bric4Error.fromCode(code)) {
                Bric4Error.MAGNETOMETER_1_HIGH_MAGNITUDE,
                Bric4Error.MAGNETOMETER_2_HIGH_MAGNITUDE,
                Bric4Error.MAGNETOMETER_DISPARITY,
                Bric4Error.AZIMUTH_ERROR,
                Bric4Error.INCLINATION_ERROR,
                -> MAGNETIC

                Bric4Error.ACCELEROMETER_1_HIGH_MAGNITUDE,
                Bric4Error.ACCELEROMETER_2_HIGH_MAGNITUDE,
                Bric4Error.ACCELEROMETER_DISPARITY,
                Bric4Error.TOO_FAST,
                -> MOVED

                Bric4Error.TOO_WEAK,
                Bric4Error.TOO_REFLECTIVE,
                -> NO_RETURN

                Bric4Error.COMMUNICATION_ERROR,
                Bric4Error.TIMEOUT,
                Bric4Error.WRONG_MESSAGE,
                -> CONVERSATION

                // NO_ERROR should never reach here - a zero code is not reported as a failure -
                // but a code the table does not know certainly can, and must not throw.
                Bric4Error.NO_ERROR,
                Bric4Error.UNRECOGNISED_ERROR,
                -> UNKNOWN
            }

        /** The one to act on, out of everything reported since the last good shot. */
        fun worstOf(troubles: Collection<ShotTrouble>): ShotTrouble? =
            order.firstOrNull { it in troubles }
    }
}
