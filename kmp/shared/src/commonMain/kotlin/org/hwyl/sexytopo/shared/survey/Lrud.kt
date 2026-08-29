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
enum class LrudMode {
    /**
     * Square to the passage: the cross-section bearing, which bisects the corner at a bend. This is
     * what most cavers mean by a left-hand wall measurement.
     */
    SURVEY,

    /**
     * Square to the outgoing shot alone, ignoring the way in. Some surveyors book LRUDs relative to
     * the leg they are about to shoot.
     */
    SHOT,
    ;

    /**
     * The reference bearing.
     *
     * [SHOT] indexes the first connected onward leg directly, so calling it on a station with no
     * way on throws — reproduced from the original, which has the same unguarded `get(0)`. Callers
     * (the manual-entry dialog) only offer LRUD entry on stations that have one.
     */
    fun sideAzimuth(survey: Survey, station: Station): Float =
        when (this) {
            SURVEY -> CrossSectioner.angleOfSection(survey, station)
            SHOT -> station.getConnectedOnwardLegs()[0].azimuth
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
