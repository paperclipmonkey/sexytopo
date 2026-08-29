package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The slice of `control/util/SurveyUpdater` and `StationNamer` this proof of concept needs:
 * turning a shot into a new station, and hanging splays off a station.
 *
 * A full port would bring across the rest of SurveyUpdater — triple-shot promotion, the three
 * pluggable leg-amalgamation algorithms, splay upgrade/downgrade, leg editing and subtree-aware
 * deletion — all of which is plain logic in the Java original.
 */
object SurveyBuilder {

    /** Promotes a shot to a full leg pointing at a newly named station, which becomes active. */
    fun updateWithNewStation(survey: Survey, leg: Leg): Station {
        val from = survey.activeStation
        val newStation = Station(nextStationName(survey, from))
        val fullLeg = Leg(leg.distance, leg.azimuth, leg.inclination, newStation)
        fullLeg.comment = leg.comment
        from.addOnwardLeg(fullLeg)
        survey.addLegRecord(fullLeg)
        survey.activeStation = newStation
        return newStation
    }

    /** Records a splay: a leg with no destination station. */
    fun addSplay(survey: Survey, station: Station, leg: Leg) {
        station.addOnwardLeg(leg)
        survey.addLegRecord(leg)
    }

    /**
     * Advances the trailing number of the originating station's name, skipping names already in
     * use. The Android original also handles branch suffixes such as "2a1".
     */
    fun nextStationName(survey: Survey, from: Station): String {
        val existing = survey.getAllStations().map { it.name }.toSet()
        val trailing = Regex("(\\d+)$").find(from.name)
        if (trailing != null) {
            val prefix = from.name.dropLast(trailing.value.length)
            var number = trailing.value.toInt() + 1
            while ("$prefix$number" in existing) number++
            return "$prefix$number"
        }
        var suffix = 1
        while ("${from.name}$suffix" in existing) suffix++
        return "${from.name}$suffix"
    }
}
