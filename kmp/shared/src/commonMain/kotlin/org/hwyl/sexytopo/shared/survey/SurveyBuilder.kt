package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The deliberate, "I know where this leg goes" half of the survey engine: hanging a named station
 * off a shot, and recording splays. The reactive half — watching a stream of readings and deciding
 * when they amount to a new station — lives in [SurveyUpdater].
 */
object SurveyBuilder {

    /**
     * Promotes a shot to a full leg pointing at a newly named station, which becomes active.
     *
     * A shot that already carries a destination (the surveyor typed a station name, or is linking
     * to a known station) keeps it; only an unnamed shot gets a generated name.
     *
     * @return the station the leg now points at.
     */
    fun updateWithNewStation(survey: Survey, leg: Leg): Station {
        val activeStation = survey.activeStation
        val fullLeg =
            if (leg.hasDestination()) {
                leg
            } else {
                val newStation = Station(StationNamer.generateNextStationName(survey, activeStation))
                Leg.upgradeSplayToConnectedLeg(leg, newStation)
            }
        addLegFromStation(survey, activeStation, fullLeg)
        return fullLeg.destination
    }

    fun addLegFromStation(survey: Survey, fromStation: Station, leg: Leg) {
        fromStation.addOnwardLeg(leg)
        survey.isSaved = false
        survey.addLegRecord(leg)
        if (leg.hasDestination()) {
            survey.activeStation = leg.destination
        }
    }

    fun addSplay(survey: Survey, station: Station, leg: Leg) {
        station.addOnwardLeg(leg)
        survey.addLegRecord(leg)
    }

    fun nextStationName(survey: Survey, from: Station): String =
        StationNamer.generateNextStationName(survey, from)
}
