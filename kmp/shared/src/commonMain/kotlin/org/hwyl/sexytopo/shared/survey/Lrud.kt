package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Left, Right, Up and Down splays, synthesised from a distance alone.
 *
 * Ported from `model/table/LRUD`. LRUDs are the traditional way of recording passage size when
 * typing a survey up from a paper book: the surveyor writes down four numbers per station rather
 * than four full compass-and-clino shots, and the app invents the directions. Left and right are
 * taken square to the passage (90 degrees off the passage bearing, horizontal); up and down are
 * taken vertically.
 *
 * They are ordinary splays once created, which is what makes the cross-section machinery work on
 * manually-entered surveys as well as instrument-fed ones.
 */
enum class Lrud {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    ;

    /**
     * Builds the splay. [distance] is the only thing the surveyor supplies.
     *
     * Note that [UP] and [DOWN] ignore both the survey and the [mode] entirely and are written with
     * an azimuth of literally 0, as in the original: a vertical shot's bearing is meaningless, but
     * it is a real 0 in the data and will be exported as such.
     */
    fun createSplay(survey: Survey, station: Station, mode: LrudMode, distance: Float): Leg =
        when (this) {
            LEFT -> Leg(distance, adjustAngle(mode.sideAzimuth(survey, station), -90f), 0f)
            RIGHT -> Leg(distance, adjustAngle(mode.sideAzimuth(survey, station), 90f), 0f)
            UP -> Leg(distance, 0f, 90f)
            DOWN -> Leg(distance, 0f, -90f)
        }
}

/**
 * Which bearing left and right are taken square to. The names come from Therion's usage.
 *
 * Ported from `model/table/LRUD.Mode`, backed in the Android app by the `pref_lrud_direction`
 * preference (default `"survey"`).
 */
enum class LrudMode(
    /** What the settings screen's chip says. */
    val label: String,
) {
    /**
     * Square to the passage: the cross-section bearing, which bisects the corner at a bend. This is
     * what most cavers mean by a left-hand wall measurement.
     */
    SURVEY("The passage"),

    /**
     * Square to the outgoing shot alone, ignoring the way in. Some surveyors book LRUDs relative to
     * the leg they are about to shoot.
     */
    SHOT("The next leg"),
    ;

    /**
     * The reference bearing.
     *
     * ## One deliberate departure, and why it had to be made
     *
     * The original indexes the first connected onward leg directly — `getConnectedOnwardLegs()
     * .get(0)` — so on a station with no way on it throws an `IndexOutOfBoundsException`. This
     * port reproduced that faithfully, and could afford to, because nothing could *select* [SHOT]:
     * the Android app reads `pref_lrud_direction` and declares it in no preference screen, so on
     * Android the value is always `"survey"` and the unguarded index is never reached.
     *
     * Offering the choice makes it reachable, and reachable at the worst moment. The port books
     * passage size at the station the surveyor is standing at, which at the working end of a
     * survey is a station with nothing beyond it yet — so *the first time anybody chose this
     * setting and measured a wall*, the app would have thrown. On Kotlin/Native that is not an
     * exception, it is the process ending: see finding 70.
     *
     * So a station with no outgoing leg falls back to the passage bearing, which is [SURVEY] and
     * is the only other answer available. It is the same number the surveyor would have got before
     * they touched the setting, and it cannot be wrong in a way that loses a survey.
     */
    fun sideAzimuth(survey: Survey, station: Station): Float =
        when (this) {
            SURVEY -> CrossSectioner.angleOfSection(survey, station)
            SHOT ->
                station.getConnectedOnwardLegs().firstOrNull()?.azimuth
                    ?: CrossSectioner.angleOfSection(survey, station)
        }

    companion object {
        val DEFAULT = SURVEY

        /**
         * Parses the stored preference value, falling back to [DEFAULT] for anything unrecognised
         * as the original does (which logs and carries on rather than throwing).
         *
         * The original uppercases using the default locale; this uses the locale-invariant
         * [uppercase], which is the same for these ASCII names except on a Turkish device, where
         * the Java would mis-case a hypothetical "i" and fall back.
         */
        fun fromPreferenceValue(value: String): LrudMode =
            entries.firstOrNull { it.name == value.uppercase() } ?: DEFAULT
    }
}
