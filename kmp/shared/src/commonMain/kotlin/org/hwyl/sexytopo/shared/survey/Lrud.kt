package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * Left, Right, Up and Down splays, synthesised from a distance alone.
 *
 * LRUDs are the traditional way of recording passage size from a paper book: four numbers per
 * station rather than four full compass-and-clino shots.
 *
 * They are ordinary splays once created, which is what makes the cross-section machinery work on
 * manually-entered surveys too.
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
     * an azimuth of literally 0: a vertical shot's bearing is meaningless, but it is a real 0 in the
     * data and will be exported as such.
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
 * Backed in the Android app by the `pref_lrud_direction` preference (default `"survey"`).
 */
enum class LrudMode(
    /** What the settings screen's chip says. */
    val label: String,
) {
    /** Square to the passage: the cross-section bearing, which bisects the corner at a bend. */
    SURVEY("The passage"),

    /** Square to the outgoing shot alone, ignoring the way in. */
    SHOT("The next leg"),
    ;

    /**
     * The reference bearing.
     *
     * The original indexes the first connected onward leg directly, throwing on a station with no
     * way on — safe there only because `pref_lrud_direction` is declared in no Android preference
     * screen. Offering [SHOT] as a real choice makes that reachable at the worst moment: a station
     * at the working end of a survey, with nothing beyond it yet. On Kotlin/Native that is not an
     * exception, it is the process ending — so a station with no outgoing leg falls back to
     * [SURVEY] instead of throwing.
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
         * Uses the locale-invariant [uppercase], unlike the Java's default-locale uppercase — the
         * same for these ASCII names except on a Turkish device, where the Java would mis-case a
         * hypothetical "i" and fall back.
         */
        fun fromPreferenceValue(value: String): LrudMode =
            entries.firstOrNull { it.name == value.uppercase() } ?: DEFAULT
    }
}
